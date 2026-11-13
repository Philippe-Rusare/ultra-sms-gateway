package com.ultracoresoft.ultrasmsgateway.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** State machine and timeouts, driven directly through the service with the test clock. */
class MessageLifecycleTest extends IntegrationTest {

    @Autowired
    MessageService service;

    @Autowired
    DeviceService devices;

    ApiKeyPrincipal client;
    UUID deviceId;
    UUID otherDeviceId;

    @BeforeEach
    void setUp() {
        client = new ApiKeyPrincipal(
                apiKeys.create(Organization.DEFAULT_ID, "lifecycle").value().getId(), Organization.DEFAULT_ID, "lifecycle");
        deviceId = devices.create(Organization.DEFAULT_ID, "phone-a").value().getId();
        otherDeviceId = devices.create(Organization.DEFAULT_ID, "phone-b").value().getId();
    }

    private SmsMessage queue(Integer ttlSeconds) {
        return service.submit(client, new NewMessage("+243812345678", "Your code is 123456", ttlSeconds, null)).message();
    }

    private SmsMessage reload(UUID id) {
        return messageRepository.findById(id).orElseThrow();
    }

    @Test
    void fullSuccessfulLifecycleErasesTheText() {
        SmsMessage message = queue(null);

        List<SmsMessage> claimed = service.claim(deviceId, 3);
        assertThat(claimed).extracting(SmsMessage::getId).containsExactly(message.getId());
        assertThat(claimed.getFirst().getBody()).isEqualTo("Your code is 123456");

        service.onAccepted(deviceId, message.getId());
        assertThat(reload(message.getId()).getStatus()).isEqualTo(MessageStatus.ACCEPTED);

        service.onSent(deviceId, message.getId());
        SmsMessage sent = reload(message.getId());
        assertThat(sent.getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(sent.getBody()).isNull();

        service.onDelivered(deviceId, message.getId());
        assertThat(reload(message.getId()).getStatus()).isEqualTo(MessageStatus.DELIVERED);

        assertThat(eventRepository.findByMessageIdOrderByIdAsc(message.getId()))
                .extracting(MessageEvent::getStatus)
                .containsExactly(MessageStatus.QUEUED, MessageStatus.DISPATCHED, MessageStatus.ACCEPTED,
                        MessageStatus.SENT, MessageStatus.DELIVERED);
    }

    @Test
    void reportsFromAnotherDeviceAreIgnored() {
        SmsMessage message = queue(null);
        service.claim(deviceId, 1);

        service.onSent(otherDeviceId, message.getId());

        assertThat(reload(message.getId()).getStatus()).isEqualTo(MessageStatus.DISPATCHED);
    }

    @Test
    void unacknowledgedMessageGoesBackToTheQueue() {
        SmsMessage message = queue(null);
        service.claim(deviceId, 1);

        clock.advance(Duration.ofSeconds(61));
        service.runMaintenance();

        SmsMessage requeued = reload(message.getId());
        assertThat(requeued.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(requeued.getBody()).isNotNull();
        assertThat(service.claim(otherDeviceId, 1)).extracting(SmsMessage::getId).containsExactly(message.getId());
    }

    @Test
    void lateAcknowledgementFromThePreviousDeviceStillCounts() {
        SmsMessage message = queue(null);
        service.claim(deviceId, 1);
        clock.advance(Duration.ofSeconds(61));
        service.runMaintenance();

        service.onAccepted(deviceId, message.getId());

        assertThat(reload(message.getId()).getStatus()).isEqualTo(MessageStatus.ACCEPTED);
    }

    @Test
    void messageFailsAfterMaxAttempts() {
        SmsMessage message = queue(3600);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(service.claim(deviceId, 1)).hasSize(1);
            clock.advance(Duration.ofSeconds(61));
            service.runMaintenance();
        }
        SmsMessage failed = reload(message.getId());
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("MAX_ATTEMPTS_REACHED");
        assertThat(failed.getBody()).isNull();
    }

    @Test
    void expiredMessageIsNeverDispatched() {
        SmsMessage message = queue(30);

        clock.advance(Duration.ofSeconds(31));
        assertThat(service.claim(deviceId, 3)).isEmpty();

        service.runMaintenance();
        SmsMessage expired = reload(message.getId());
        assertThat(expired.getStatus()).isEqualTo(MessageStatus.EXPIRED);
        assertThat(expired.getBody()).isNull();
    }

    @Test
    void acceptedMessageWithoutReportFails() {
        SmsMessage message = queue(null);
        service.claim(deviceId, 1);
        service.onAccepted(deviceId, message.getId());

        clock.advance(Duration.ofMinutes(11));
        service.runMaintenance();

        assertThat(reload(message.getId()).getFailureReason()).isEqualTo("REPORT_TIMEOUT");
    }

    @Test
    void lateFailureDoesNotHideASuccessfulSend() {
        SmsMessage message = queue(null);
        service.claim(deviceId, 1);
        service.onSent(deviceId, message.getId());

        service.onFailed(deviceId, message.getId(), "RESULT_ERROR_GENERIC_FAILURE");

        assertThat(reload(message.getId()).getStatus()).isEqualTo(MessageStatus.SENT);
    }

    @Test
    void failureReasonIsSanitized() {
        assertThat(MessageService.sanitizeReason("RESULT_ERROR_NO_SERVICE")).isEqualTo("RESULT_ERROR_NO_SERVICE");
        assertThat(MessageService.sanitizeReason("<script>")).isEqualTo("script");
        assertThat(MessageService.sanitizeReason(null)).isEqualTo("UNKNOWN");
        assertThat(MessageService.sanitizeReason("x".repeat(500))).hasSize(200);
    }

    @Test
    void queueIsFirstInFirstOut() {
        SmsMessage first = queue(null);
        clock.advance(Duration.ofSeconds(1));
        SmsMessage second = queue(null);

        assertThat(service.claim(deviceId, 1)).extracting(SmsMessage::getId).containsExactly(first.getId());
        assertThat(service.claim(deviceId, 1)).extracting(SmsMessage::getId).containsExactly(second.getId());
    }
}
