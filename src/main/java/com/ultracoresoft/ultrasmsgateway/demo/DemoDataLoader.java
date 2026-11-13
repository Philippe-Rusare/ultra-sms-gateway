package com.ultracoresoft.ultrasmsgateway.demo;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKey;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyRepository;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceRepository;
import com.ultracoresoft.ultrasmsgateway.message.MessageEvent;
import com.ultracoresoft.ultrasmsgateway.message.MessageEventRepository;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills an empty in-memory database with 14 days of fictitious traffic, to try the dashboard and take
 * screenshots without a phone. Active only with the {@code demo} profile, and only on H2: it never
 * touches a real database.
 *
 * <p>The demo device token and API key are fixed and public (see the README, "Try it without a phone"): never use this profile
 * on a server reachable by others.
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
class DemoDataLoader implements ApplicationRunner {

    static final String DEMO_API_KEY = "usg_demo_key_for_local_testing_only_0000000000";
    static final String DEMO_DEVICE_TOKEN = "usgd_demo_token_for_local_testing_only_00000000";

    private static final int DAYS = 14;
    private static final String[] FAILURES = {
            "RESULT_ERROR_NO_SERVICE", "RESULT_ERROR_GENERIC_FAILURE", "RESULT_ERROR_RADIO_OFF",
            "MAX_ATTEMPTS_REACHED", "REPORT_TIMEOUT"};

    private final ApiKeyRepository apiKeys;
    private final DeviceRepository devices;
    private final SmsMessageRepository messages;
    private final MessageEventRepository events;
    private final Environment environment;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:h2:")) {
            log.warn("Demo profile ignored: it only fills an in-memory H2 database");
            return;
        }
        if (messages.count() > 0) {
            return;
        }
        Instant now = clock.instant();
        Random random = new Random(42);

        List<ApiKey> keys = List.of(
                apiKey("notification-service", DEMO_API_KEY, now.minus(Duration.ofDays(30)), now.minusSeconds(40)),
                apiKey("pos-terminal", Secrets.generate("usg_"), now.minus(Duration.ofDays(21)), now.minus(Duration.ofHours(3))),
                apiKey("customer-portal", Secrets.generate("usg_"), now.minus(Duration.ofDays(12)), now.minus(Duration.ofMinutes(25))));

        Device vodacom = device("Vodacom phone 1", DEMO_DEVICE_TOKEN, "Vodacom CD", "LTE", 87, true, 4, now, null);
        Device airtel = device("Airtel phone 1", Secrets.generate("usgd_"), "Airtel CD", "LTE", 64, true, 3, now.minusSeconds(20), null);
        Device orange = device("Orange phone 1", Secrets.generate("usgd_"), "Orange RDC", "HSPA", 31, false, 2,
                now.minus(Duration.ofHours(5)), null);
        device("Old test phone", Secrets.generate("usgd_"), "Africell", "EDGE", 12, false, 1,
                now.minus(Duration.ofDays(9)), now.minus(Duration.ofDays(8)));
        List<Device> senders = List.of(vodacom, vodacom, airtel, orange);
        String[] prefixes = {"+24381", "+24382", "+24397", "+24399", "+24384", "+24389"};

        List<SmsMessage> created = new ArrayList<>();
        List<MessageEvent> history = new ArrayList<>();
        for (int day = DAYS - 1; day >= 0; day--) {
            Instant dayStart = now.minus(Duration.ofDays(day)).truncatedTo(ChronoUnit.DAYS);
            int volume = 45 + random.nextInt(60) + (DAYS - day) * 3;
            long dayLength = day == 0 ? Duration.between(dayStart, now).toSeconds() : 86_400;
            if (day == 0) {
                volume = (int) (volume * dayLength / 86_400.0) + 3;
            }
            for (int i = 0; i < volume; i++) {
                Instant createdAt = dayStart.plusSeconds(6 * 3600 + (long) (random.nextDouble() * Math.max(dayLength - 6 * 3600, 600)));
                if (createdAt.isAfter(now)) {
                    createdAt = now.minusSeconds(random.nextInt(600));
                }
                String recipient = prefixes[random.nextInt(prefixes.length)] + String.format("%07d", random.nextInt(10_000_000));
                Device device = senders.get(random.nextInt(senders.size()));
                ApiKey key = keys.get(random.nextInt(10) < 7 ? 0 : random.nextInt(keys.size()));
                created.add(message(key, device, recipient, createdAt, random));
            }
        }
        // A few messages still waiting, to show the live states.
        for (int i = 0; i < 4; i++) {
            Instant createdAt = now.minusSeconds(5L + i * 7);
            String recipient = prefixes[i] + String.format("%07d", random.nextInt(10_000_000));
            SmsMessage waiting = base(keys.get(0), recipient, createdAt, i < 2 ? MessageStatus.QUEUED : MessageStatus.ACCEPTED);
            waiting.setBody("Demo message");
            waiting.setExpiresAt(createdAt.plus(Duration.ofMinutes(5)));
            if (i >= 2) {
                waiting.setDeviceId(vodacom.getId());
                waiting.setAttempts(1);
                waiting.setDispatchedAt(createdAt.plusSeconds(1));
                waiting.setAcceptedAt(createdAt.plusSeconds(2));
            }
            created.add(waiting);
        }
        messages.saveAll(created);
        for (SmsMessage message : created) {
            history.addAll(eventsFor(message));
        }
        events.saveAll(history);
        log.info("Demo data loaded: {} messages, {} devices, {} API keys", created.size(), devices.count(), keys.size());
    }

    private SmsMessage message(ApiKey key, Device device, String recipient, Instant createdAt, Random random) {
        int roll = random.nextInt(100);
        MessageStatus status = roll < 76 ? MessageStatus.DELIVERED
                : roll < 88 ? MessageStatus.SENT
                : roll < 95 ? MessageStatus.FAILED
                : MessageStatus.EXPIRED;
        SmsMessage message = base(key, recipient, createdAt, status);
        message.setExpiresAt(createdAt.plus(Duration.ofMinutes(5)));
        if (random.nextInt(4) == 0) {
            message.setClientReference("otp-" + Long.toHexString(random.nextLong() & 0xffffffL));
        }
        if (status == MessageStatus.EXPIRED) {
            message.setUpdatedAt(createdAt.plus(Duration.ofMinutes(5)));
            return message;
        }
        message.setDeviceId(device.getId());
        message.setAttempts(random.nextInt(10) == 0 ? 2 : 1);
        message.setDispatchedAt(createdAt.plusMillis(300 + random.nextInt(900)));
        message.setAcceptedAt(message.getDispatchedAt().plusMillis(200 + random.nextInt(600)));
        if (status == MessageStatus.FAILED) {
            message.setFailureReason(FAILURES[random.nextInt(FAILURES.length)]);
            message.setUpdatedAt(message.getAcceptedAt().plusSeconds(3 + random.nextInt(8)));
            return message;
        }
        message.setSentAt(message.getAcceptedAt().plusSeconds(2 + random.nextInt(6)));
        message.setUpdatedAt(message.getSentAt());
        if (status == MessageStatus.DELIVERED) {
            message.setDeliveredAt(message.getSentAt().plusSeconds(1 + random.nextInt(40)));
            message.setUpdatedAt(message.getDeliveredAt());
        }
        return message;
    }

    private SmsMessage base(ApiKey key, String recipient, Instant createdAt, MessageStatus status) {
        SmsMessage message = new SmsMessage();
        message.setOrganizationId(key.getOrganizationId());
        message.setApiKeyId(key.getId());
        message.setRecipient(recipient);
        message.setBodyLength(28 + recipient.hashCode() % 40 + 40);
        message.setStatus(status);
        message.setCreatedAt(createdAt);
        message.setUpdatedAt(createdAt);
        return message;
    }

    /** The audit trail the real state machine would have written. */
    private List<MessageEvent> eventsFor(SmsMessage message) {
        List<MessageEvent> trail = new ArrayList<>();
        trail.add(event(message, MessageStatus.QUEUED, null, null, message.getCreatedAt()));
        if (message.getDispatchedAt() != null) {
            if (message.getAttempts() > 1) {
                Instant first = message.getCreatedAt().plusMillis(200);
                trail.add(event(message, MessageStatus.DISPATCHED, message.getDeviceId(), "attempt 1", first));
                trail.add(event(message, MessageStatus.QUEUED, null, "ACK_TIMEOUT", first.plusSeconds(60)));
            }
            trail.add(event(message, MessageStatus.DISPATCHED, message.getDeviceId(), "attempt " + message.getAttempts(),
                    message.getDispatchedAt()));
        }
        if (message.getAcceptedAt() != null) {
            trail.add(event(message, MessageStatus.ACCEPTED, message.getDeviceId(), null, message.getAcceptedAt()));
        }
        if (message.getSentAt() != null) {
            trail.add(event(message, MessageStatus.SENT, message.getDeviceId(), null, message.getSentAt()));
        }
        if (message.getDeliveredAt() != null) {
            trail.add(event(message, MessageStatus.DELIVERED, message.getDeviceId(), null, message.getDeliveredAt()));
        }
        if (message.getStatus() == MessageStatus.FAILED) {
            trail.add(event(message, MessageStatus.FAILED, message.getDeviceId(), message.getFailureReason(), message.getUpdatedAt()));
        }
        if (message.getStatus() == MessageStatus.EXPIRED) {
            trail.add(event(message, MessageStatus.EXPIRED, null, "TTL_EXCEEDED", message.getUpdatedAt()));
        }
        return trail;
    }

    private static MessageEvent event(SmsMessage message, MessageStatus status, UUID deviceId, String detail, Instant at) {
        MessageEvent event = new MessageEvent();
        event.setMessageId(message.getId());
        event.setStatus(status);
        event.setDeviceId(deviceId);
        event.setDetail(detail);
        event.setCreatedAt(at);
        return event;
    }

    private ApiKey apiKey(String name, String secret, Instant createdAt, Instant lastUsedAt) {
        ApiKey key = new ApiKey();
        key.setOrganizationId(Organization.DEFAULT_ID);
        key.setName(name);
        key.setKeyPrefix(Secrets.displayPrefix(secret));
        key.setKeyHash(Secrets.hash(secret));
        key.setCreatedAt(createdAt);
        key.setLastUsedAt(lastUsedAt);
        return apiKeys.save(key);
    }

    private Device device(String name, String token, String operator, String network, int battery, boolean charging,
                          int signal, Instant lastSeen, Instant revokedAt) {
        Device device = new Device();
        device.setOrganizationId(Organization.DEFAULT_ID);
        device.setName(name);
        device.setTokenPrefix(Secrets.displayPrefix(token));
        device.setTokenHash(Secrets.hash(token));
        device.setCreatedAt(lastSeen.minus(Duration.ofDays(20)));
        device.setLastConnectedAt(lastSeen.minus(Duration.ofHours(2)));
        device.setLastSeenAt(lastSeen);
        device.setOperatorName(operator);
        device.setNetworkType(network);
        device.setBatteryLevel(battery);
        device.setCharging(charging);
        device.setSignalLevel(signal);
        device.setAppVersion("0.1.0");
        device.setRevokedAt(revokedAt);
        return devices.save(device);
    }
}
