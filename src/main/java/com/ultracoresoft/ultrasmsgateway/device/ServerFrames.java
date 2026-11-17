package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import java.time.Instant;
import java.util.UUID;

/** JSON frames sent by the gateway to a device. See docs/device-protocol.md. */
public final class ServerFrames {

    private ServerFrames() {
    }

    /**
     * Sent when a phone connects. {@code deviceName} and {@code organization} let the app show what it is linked
     * to; {@code plan} is null when the gateway has no plans (self-hosted).
     */
    public record Hello(String type, UUID deviceId, String serverTime, int maxInFlight,
                        String deviceName, String organization, String plan) {
    }

    public record Send(String type, UUID id, String to, String body, String expiresAt) {
    }

    public record Error(String type, String code, UUID id) {
    }

    public static Hello hello(UUID deviceId, Instant now, int maxInFlight, DeviceService.Profile profile) {
        return new Hello("hello", deviceId, now.toString(), maxInFlight,
                profile.deviceName(), profile.organizationName(), profile.plan());
    }

    public static Send send(SmsMessage message) {
        return new Send("send", message.getId(), message.getRecipient(), message.getBody(),
                message.getExpiresAt().toString());
    }

    public static Error error(String code, UUID id) {
        return new Error("error", code, id);
    }
}
