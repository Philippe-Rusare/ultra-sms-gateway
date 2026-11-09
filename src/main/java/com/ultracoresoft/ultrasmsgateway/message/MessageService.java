package com.ultracoresoft.ultrasmsgateway.message;

import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.ACCEPTED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.DELIVERED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.DISPATCHED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.EXPIRED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.FAILED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.QUEUED;
import static com.ultracoresoft.ultrasmsgateway.message.MessageStatus.SENT;

import com.ultracoresoft.ultrasmsgateway.common.InvalidRequestException;
import com.ultracoresoft.ultrasmsgateway.common.NotFoundException;
import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The queue and its state machine. Message text and recipients are never logged.
 */
@Slf4j
@Service
public class MessageService {

    private static final Set<MessageStatus> IN_FLIGHT = EnumSet.of(DISPATCHED, ACCEPTED);
    private static final Set<MessageStatus> WAITING = EnumSet.of(QUEUED, DISPATCHED);
    private static final int MAX_REPORT_ATTEMPTS = 3;
    private static final int MAX_REASON_LENGTH = 200;

    private final SmsMessageRepository messages;
    private final MessageEventRepository events;
    private final GatewayProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public MessageService(SmsMessageRepository messages, MessageEventRepository events, GatewayProperties properties,
                          Clock clock, TransactionTemplate transaction) {
        this.messages = messages;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.transaction = transaction;
    }

    // ---------------------------------------------------------------- client API

    /**
     * Queues a message. With a client reference already used by this API key, returns the earlier message
     * instead of queuing a second one, so that a client can safely retry after a timeout.
     */
    public SubmitResult submit(UUID apiKeyId, NewMessage request) {
        Duration ttl = resolveTtl(request.ttlSeconds());
        String reference = blankToNull(request.clientReference());
        if (reference != null) {
            Optional<SmsMessage> existing = messages.findByApiKeyIdAndClientReference(apiKeyId, reference);
            if (existing.isPresent()) {
                return new SubmitResult(existing.get(), false);
            }
        }
        try {
            return transaction.execute(status -> new SubmitResult(create(apiKeyId, reference, request, ttl), true));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent submissions with the same reference: the other one won.
            if (reference == null) {
                throw e;
            }
            return messages.findByApiKeyIdAndClientReference(apiKeyId, reference)
                    .map(message -> new SubmitResult(message, false))
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(readOnly = true)
    public SmsMessage get(UUID apiKeyId, UUID id) {
        return messages.findByIdAndApiKeyId(id, apiKeyId)
                .orElseThrow(() -> new NotFoundException("No message " + id + " for this API key."));
    }

    private SmsMessage create(UUID apiKeyId, String reference, NewMessage request, Duration ttl) {
        Instant now = clock.instant();
        SmsMessage message = new SmsMessage();
        message.setApiKeyId(apiKeyId);
        message.setClientReference(reference);
        message.setRecipient(request.to());
        message.setBody(request.body());
        message.setBodyLength(request.body().length());
        message.setStatus(QUEUED);
        message.setCreatedAt(now);
        message.setUpdatedAt(now);
        message.setExpiresAt(now.plus(ttl));
        SmsMessage saved = messages.saveAndFlush(message);
        events.save(MessageEvent.of(saved.getId(), QUEUED, null, null, now));
        return saved;
    }

    private Duration resolveTtl(Integer ttlSeconds) {
        if (ttlSeconds == null) {
            return properties.messages().defaultTtl();
        }
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        Duration max = properties.messages().maxTtl();
        if (ttl.compareTo(max) > 0) {
            throw new InvalidRequestException("ttlSeconds must not exceed " + max.toSeconds() + ".");
        }
        return ttl;
    }

    // ---------------------------------------------------------------- dispatch

    /** Takes up to {@code max} queued messages, oldest first, and assigns them to the device. */
    @Transactional
    public List<SmsMessage> claim(UUID deviceId, int max) {
        Instant now = clock.instant();
        List<SmsMessage> batch = messages.findByStatusAndExpiresAtAfterOrderByCreatedAtAsc(QUEUED, now, Limit.of(max));
        for (SmsMessage message : batch) {
            message.setDeviceId(deviceId);
            message.setDispatchedAt(now);
            message.setAttempts(message.getAttempts() + 1);
            transition(message, DISPATCHED, deviceId, "attempt " + message.getAttempts(), now);
        }
        return batch;
    }

    /** Messages pushed to the device but not acknowledged yet, to push again after a reconnection. */
    @Transactional(readOnly = true)
    public List<SmsMessage> awaitingAcknowledgement(UUID deviceId) {
        return messages.findByDeviceIdAndStatusAndExpiresAtAfter(deviceId, DISPATCHED, clock.instant());
    }

    @Transactional(readOnly = true)
    public long inFlight(UUID deviceId) {
        return messages.countByDeviceIdAndStatusIn(deviceId, IN_FLIGHT);
    }

    // ---------------------------------------------------------------- device reports

    public void onAccepted(UUID deviceId, UUID messageId) {
        report(deviceId, messageId, (message, now) -> {
            if (WAITING.contains(message.getStatus())) {
                message.setAcceptedAt(now);
                transition(message, ACCEPTED, deviceId, null, now);
            }
        });
    }

    /** Accepted from any earlier status: the device is the source of truth on what it actually sent. */
    public void onSent(UUID deviceId, UUID messageId) {
        report(deviceId, messageId, (message, now) -> {
            if (message.getStatus() != SENT && message.getStatus() != DELIVERED) {
                message.setSentAt(now);
                message.setFailureReason(null);
                transition(message, SENT, deviceId, null, now);
            }
        });
    }

    public void onDelivered(UUID deviceId, UUID messageId) {
        report(deviceId, messageId, (message, now) -> {
            if (message.getStatus() != DELIVERED) {
                if (message.getSentAt() == null) {
                    message.setSentAt(now);
                }
                message.setDeliveredAt(now);
                message.setFailureReason(null);
                transition(message, DELIVERED, deviceId, null, now);
            }
        });
    }

    /** Ignored once the message is sent: a late failure report must not hide a successful send. */
    public void onFailed(UUID deviceId, UUID messageId, String reason) {
        String cleanReason = sanitizeReason(reason);
        report(deviceId, messageId, (message, now) -> {
            if (WAITING.contains(message.getStatus()) || message.getStatus() == ACCEPTED) {
                message.setFailureReason(cleanReason);
                transition(message, FAILED, deviceId, cleanReason, now);
            }
        });
    }

    private void report(UUID deviceId, UUID messageId, ReportHandler handler) {
        for (int attempt = 1; ; attempt++) {
            try {
                transaction.executeWithoutResult(status -> messages.findById(messageId)
                        .filter(message -> deviceId.equals(message.getDeviceId()))
                        .ifPresentOrElse(
                                message -> handler.apply(message, clock.instant()),
                                () -> log.debug("Ignoring report from device {} for message {} not assigned to it",
                                        deviceId, messageId)));
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= MAX_REPORT_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }

    @FunctionalInterface
    private interface ReportHandler {
        void apply(SmsMessage message, Instant now);
    }

    // ---------------------------------------------------------------- maintenance

    /** Expires overdue messages, queues again unacknowledged ones and fails silent ones. */
    public void runMaintenance() {
        Instant now = clock.instant();
        GatewayProperties.Dispatch dispatch = properties.dispatch();
        inTransaction("expire", () -> messages.findByStatusInAndExpiresAtBefore(WAITING, now)
                .forEach(message -> transition(message, EXPIRED, null, "TTL_EXCEEDED", now)));
        inTransaction("requeue", () -> messages.findByStatusAndDispatchedAtBefore(DISPATCHED, now.minus(dispatch.ackTimeout()))
                .forEach(message -> {
                    if (message.getAttempts() >= dispatch.maxAttempts()) {
                        message.setFailureReason("MAX_ATTEMPTS_REACHED");
                        transition(message, FAILED, null, "MAX_ATTEMPTS_REACHED", now);
                    } else {
                        transition(message, QUEUED, null, "ACK_TIMEOUT", now);
                    }
                }));
        inTransaction("report timeout", () -> messages.findByStatusAndAcceptedAtBefore(ACCEPTED, now.minus(dispatch.reportTimeout()))
                .forEach(message -> {
                    message.setFailureReason("REPORT_TIMEOUT");
                    transition(message, FAILED, null, "REPORT_TIMEOUT", now);
                }));
    }

    private void inTransaction(String step, Runnable work) {
        try {
            transaction.executeWithoutResult(status -> work.run());
        } catch (OptimisticLockingFailureException e) {
            // A device report changed one of the messages meanwhile; the next run picks up what is left.
            log.debug("Maintenance step '{}' skipped after a concurrent update", step);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void transition(SmsMessage message, MessageStatus to, UUID deviceId, String detail, Instant now) {
        message.setStatus(to);
        message.setUpdatedAt(now);
        if (to.erasesBody()) {
            message.setBody(null);
        }
        events.save(MessageEvent.of(message.getId(), to, deviceId, detail, now));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Device reasons are short codes such as {@code RESULT_ERROR_NO_SERVICE}; anything else is stripped. */
    static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "UNKNOWN";
        }
        String clean = reason.replaceAll("[^A-Za-z0-9_ .:-]", "").trim();
        if (clean.isEmpty()) {
            return "UNKNOWN";
        }
        return clean.length() > MAX_REASON_LENGTH ? clean.substring(0, MAX_REASON_LENGTH) : clean;
    }

}
