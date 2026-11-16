package com.ultracoresoft.ultrasmsgateway.webhook;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the webhook requests that are due, signed, and retries failures later.
 *
 * <p>A request succeeds when the application answers 2xx within the timeout. Otherwise it is tried again after
 * 30 s, 2 min, 10 min, 30 min, 1 h, 3 h, then every 6 h, up to {@code gateway.webhooks.max-attempts}. Redirects are
 * not followed. No transaction is held while waiting for the application.
 */
@Slf4j
@Component
public class WebhookSender {

    static final String SIGNATURE_HEADER = "Usg-Signature";
    static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6));
    private static final int BATCH = 20;
    private static final int MAX_ERROR_LENGTH = 200;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookAddresses addresses;
    private final GatewayProperties.Webhooks settings;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final HttpClient http;
    private final ReentrantLock lock = new ReentrantLock();

    public WebhookSender(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, WebhookAddresses addresses,
                         GatewayProperties properties, Clock clock, TransactionTemplate transaction) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.addresses = addresses;
        this.settings = properties.webhooks();
        this.clock = clock;
        this.transaction = transaction;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${gateway.webhooks.interval}")
    public void tick() {
        try {
            sendDue();
            transaction.executeWithoutResult(status -> deliveries.deleteFinishedBefore(
                    WebhookDelivery.Status.PENDING, clock.instant().minus(settings.retention())));
        } catch (RuntimeException e) {
            log.error("Sending webhooks failed", e);
        }
    }

    /** Sends every due request, oldest first. Returns how many were tried. */
    public int sendDue() {
        if (!lock.tryLock()) {
            return 0;
        }
        try {
            int tried = 0;
            List<WebhookDelivery> due;
            do {
                due = deliveries.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        WebhookDelivery.Status.PENDING, clock.instant(), Limit.of(BATCH));
                for (WebhookDelivery delivery : due) {
                    send(delivery);
                    tried++;
                }
            } while (due.size() == BATCH);
            return tried;
        } finally {
            lock.unlock();
        }
    }

    private void send(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null) {
            return;
        }
        Integer code = null;
        String error = null;
        try {
            URI uri = URI.create(endpoint.getUrl());
            if (!addresses.mayReach(uri)) {
                error = "PRIVATE_NETWORK";
            } else {
                long timestamp = clock.instant().getEpochSecond();
                HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri)
                        .timeout(settings.timeout())
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "UltraSmsGateway-Webhooks/1")
                        .header("Usg-Event", delivery.getEvent())
                        .header("Usg-Delivery", delivery.getId().toString())
                        .header(SIGNATURE_HEADER, signature(endpoint.getSecret(), timestamp, delivery.getPayload()))
                        .POST(HttpRequest.BodyPublishers.ofString(delivery.getPayload(), StandardCharsets.UTF_8))
                        .build(), HttpResponse.BodyHandlers.discarding());
                code = response.statusCode();
                if (code < 200 || code >= 300) {
                    error = "HTTP_" + code;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "INTERRUPTED";
        } catch (Exception e) {
            error = e.getClass().getSimpleName();
        }
        record(delivery.getId(), endpoint.getId(), code, error);
    }

    private void record(UUID deliveryId, UUID endpointId, Integer code, String error) {
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> deliveries.findById(deliveryId).ifPresent(delivery -> {
            int attempts = delivery.getAttempts() + 1;
            delivery.setAttempts(attempts);
            delivery.setLastStatusCode(code);
            delivery.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH)));
            if (error == null) {
                delivery.setStatus(WebhookDelivery.Status.SUCCEEDED);
                delivery.setNextAttemptAt(null);
                delivery.setFinishedAt(now);
            } else if (attempts >= settings.maxAttempts() || "PRIVATE_NETWORK".equals(error)) {
                delivery.setStatus(WebhookDelivery.Status.FAILED);
                delivery.setNextAttemptAt(null);
                delivery.setFinishedAt(now);
            } else {
                delivery.setNextAttemptAt(now.plus(BACKOFF.get(Math.min(attempts - 1, BACKOFF.size() - 1))));
            }
            endpoints.findById(endpointId).ifPresent(endpoint -> {
                if (error == null) {
                    endpoint.setLastSuccessAt(now);
                } else {
                    endpoint.setLastFailureAt(now);
                }
            });
        }));
    }

    /**
     * {@code t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>" with the secret>}. The receiver recomputes it and
     * refuses a request whose time is too far from its own clock (see docs/api.md).
     */
    static String signature(String secret, long timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
