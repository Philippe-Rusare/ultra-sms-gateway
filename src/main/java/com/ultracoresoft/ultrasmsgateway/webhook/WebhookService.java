package com.ultracoresoft.ultrasmsgateway.webhook;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Webhook addresses of an organization, and the requests they receive.
 *
 * <p>When a message reaches SENT, DELIVERED, FAILED or EXPIRED, {@link #onStatusChange} writes one request per
 * address listening to that event, in the transaction of the status change: an event is recorded if and only if
 * the change is. {@link WebhookSender} then sends them. Message texts are never part of a webhook.
 */
@Service
@RequiredArgsConstructor
public class WebhookService {

    public static final String SECRET_PREFIX = "whsec_";
    static final int MAX_DESCRIPTION_LENGTH = 100;

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookAddresses addresses;
    private final JsonMapper json;
    private final Clock clock;

    public enum Problem { INVALID_URL, HTTPS_REQUIRED, PRIVATE_NETWORK, NO_EVENT, INVALID_DESCRIPTION, NOT_FOUND }

    /** The outcome of a change: the endpoint, or why it was refused. */
    public record Result<T>(T value, Problem problem) {

        static <T> Result<T> ok(T value) {
            return new Result<>(value, null);
        }

        static <T> Result<T> refused(Problem problem) {
            return new Result<>(null, problem);
        }

        public boolean ok() {
            return problem == null;
        }
    }

    // ---------------------------------------------------------------- addresses

    @Transactional(readOnly = true)
    public Page<WebhookEndpoint> page(UUID organizationId, Pageable pageable) {
        return endpoints.findByOrganizationIdOrderByCreatedAtDesc(organizationId, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<WebhookEndpoint> find(UUID organizationId, UUID id) {
        return endpoints.findByIdAndOrganizationId(id, organizationId);
    }

    /** Creates an address; the secret is returned in clear here, and shown once. */
    @Transactional
    public Result<Issued<WebhookEndpoint>> create(UUID organizationId, String url, String description, Collection<WebhookEvent> events) {
        Optional<Problem> problem = check(url, description, events);
        if (problem.isPresent()) {
            return Result.refused(problem.get());
        }
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setOrganizationId(organizationId);
        endpoint.setUrl(url.trim());
        endpoint.setDescription(blankToNull(description));
        endpoint.setEventList(events);
        endpoint.setSecret(Secrets.generate(SECRET_PREFIX));
        endpoint.setActive(true);
        endpoint.setCreatedAt(clock.instant());
        WebhookEndpoint saved = endpoints.save(endpoint);
        return Result.ok(new Issued<>(saved, saved.getSecret()));
    }

    @Transactional
    public Result<WebhookEndpoint> update(UUID organizationId, UUID id, String url, String description,
                                          Collection<WebhookEvent> events, boolean active) {
        Optional<Problem> problem = check(url, description, events);
        if (problem.isPresent()) {
            return Result.refused(problem.get());
        }
        return endpoints.findByIdAndOrganizationId(id, organizationId).map(endpoint -> {
            endpoint.setUrl(url.trim());
            endpoint.setDescription(blankToNull(description));
            endpoint.setEventList(events);
            endpoint.setActive(active);
            return Result.ok(endpoint);
        }).orElse(Result.refused(Problem.NOT_FOUND));
    }

    /** A new secret: requests are signed with it from now on. Returned in clear, once. */
    @Transactional
    public Optional<String> rotateSecret(UUID organizationId, UUID id) {
        return endpoints.findByIdAndOrganizationId(id, organizationId).map(endpoint -> {
            endpoint.setSecret(Secrets.generate(SECRET_PREFIX));
            return endpoint.getSecret();
        });
    }

    /** Deletes the address and its history. */
    @Transactional
    public boolean delete(UUID organizationId, UUID id) {
        return endpoints.findByIdAndOrganizationId(id, organizationId).map(endpoint -> {
            endpoints.delete(endpoint);
            return true;
        }).orElse(false);
    }

    /** Queues a test request to the address, whatever its events and even when it is paused. */
    @Transactional
    public boolean sendTest(UUID organizationId, UUID id) {
        return endpoints.findByIdAndOrganizationId(id, organizationId).map(endpoint -> {
            Instant now = clock.instant();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("message", "This is a test event sent from the dashboard.");
            queue(endpoint, WebhookEvent.TEST, null, data, now);
            return true;
        }).orElse(false);
    }

    // ---------------------------------------------------------------- history

    @Transactional(readOnly = true)
    public Page<WebhookDelivery> deliveries(UUID endpointId, Pageable pageable) {
        return deliveries.findByEndpointIdOrderByCreatedAtDesc(endpointId, pageable);
    }

    /** Sends a finished request again, with its original content, as soon as possible. */
    @Transactional
    public boolean retry(UUID organizationId, UUID deliveryId) {
        return deliveries.findByIdAndOrganizationId(deliveryId, organizationId)
                .filter(delivery -> delivery.getStatus() != WebhookDelivery.Status.PENDING)
                .map(delivery -> {
                    delivery.setStatus(WebhookDelivery.Status.PENDING);
                    delivery.setAttempts(0);
                    delivery.setNextAttemptAt(clock.instant());
                    delivery.setFinishedAt(null);
                    return true;
                }).orElse(false);
    }

    // ---------------------------------------------------------------- events

    /** Called by MessageService inside the transaction that changes the status. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onStatusChange(SmsMessage message, MessageStatus status, Instant now) {
        WebhookEvent event = switch (status) {
            case SENT -> WebhookEvent.MESSAGE_SENT;
            case DELIVERED -> WebhookEvent.MESSAGE_DELIVERED;
            case FAILED -> WebhookEvent.MESSAGE_FAILED;
            case EXPIRED -> WebhookEvent.MESSAGE_EXPIRED;
            default -> null;
        };
        if (event == null) {
            return;
        }
        List<WebhookEndpoint> listening = endpoints.findByOrganizationIdAndActiveTrue(message.getOrganizationId()).stream()
                .filter(endpoint -> endpoint.listensTo(event))
                .toList();
        if (listening.isEmpty()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", message.getId().toString());
        data.put("clientReference", message.getClientReference());
        data.put("to", message.getRecipient());
        data.put("status", status.name());
        data.put("attempts", message.getAttempts());
        data.put("failureReason", message.getFailureReason());
        data.put("createdAt", text(message.getCreatedAt()));
        data.put("sentAt", text(message.getSentAt()));
        data.put("deliveredAt", text(message.getDeliveredAt()));
        listening.forEach(endpoint -> queue(endpoint, event, message.getId(), data, now));
    }

    private void queue(WebhookEndpoint endpoint, WebhookEvent event, UUID messageId, Map<String, Object> data, Instant now) {
        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setId(UUID.randomUUID());
        delivery.setEndpointId(endpoint.getId());
        delivery.setOrganizationId(endpoint.getOrganizationId());
        delivery.setEvent(event.type());
        delivery.setMessageId(messageId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", delivery.getId().toString());
        body.put("type", event.type());
        body.put("createdAt", text(now));
        body.put("data", data);
        delivery.setPayload(json.writeValueAsString(body));
        delivery.setStatus(WebhookDelivery.Status.PENDING);
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(now);
        delivery.setCreatedAt(now);
        deliveries.save(delivery);
    }

    private Optional<Problem> check(String url, String description, Collection<WebhookEvent> events) {
        Optional<WebhookAddresses.Problem> address = addresses.checkForm(url);
        if (address.isPresent()) {
            return Optional.of(switch (address.get()) {
                case INVALID -> Problem.INVALID_URL;
                case HTTPS_REQUIRED -> Problem.HTTPS_REQUIRED;
                case PRIVATE_NETWORK -> Problem.PRIVATE_NETWORK;
            });
        }
        if (events == null || events.isEmpty() || !WebhookEvent.SUBSCRIBABLE.containsAll(events)) {
            return Optional.of(Problem.NO_EVENT);
        }
        if (description != null && description.trim().length() > MAX_DESCRIPTION_LENGTH) {
            return Optional.of(Problem.INVALID_DESCRIPTION);
        }
        return Optional.empty();
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
