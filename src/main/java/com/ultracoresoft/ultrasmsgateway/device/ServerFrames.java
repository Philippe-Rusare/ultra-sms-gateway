package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import java.time.Instant;
import java.util.UUID;

/** JSON frames sent by the gateway to a device. See docs/device-protocol.md. */
public final class ServerFrames {

    private ServerFrames() {
    }

    public record Hello(String type, UUID deviceId, String serverTime, int maxInFlight) {
    }

    public record Send(String type, UUID id, String to, String body, String expiresAt) {
    }

    public record Error(String type, String code, UUID id) {
    }

    public static Hello hello(UUID deviceId, Instant now, int maxInFlight) {
        return new Hello("hello", deviceId, now.toString(), maxInFlight);
    }

    public static Send send(SmsMessage message) {
        return new Send("send", message.getId(), message.getRecipient(), message.getBody(),
                message.getExpiresAt().toString());
    }

    public static Error error(String code, UUID id) {
        return new Error("error", code, id);
    }
}
