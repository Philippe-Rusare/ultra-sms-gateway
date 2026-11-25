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

    /**
     * Whether the phone is paused: past the phones its organization's plan allows, connected but given no message.
     * Sent after {@code hello}, then each time it changes. {@code limit} and {@code active} (the phones that send)
     * are for the app's screen; {@code limit} is null without a limit.
     */
    public record Pause(String type, boolean paused, Integer limit, java.util.List<String> active) {
    }

    public record Send(String type, UUID id, String to, String body, String expiresAt) {
    }

    public record Error(String type, String code, UUID id) {
    }

    /** Sent before closing the connection of a version the gateway no longer accepts (close code 4002). */
    public record UpdateRequired(String type, String minimumVersion) {
    }

    public static UpdateRequired updateRequired(String minimumVersion) {
        return new UpdateRequired("update_required", minimumVersion);
    }

    public static Hello hello(UUID deviceId, Instant now, int maxInFlight, DeviceService.Profile profile) {
        return new Hello("hello", deviceId, now.toString(), maxInFlight,
                profile.deviceName(), profile.organizationName(), profile.plan());
    }

    public static Pause pause(DeviceService.PauseState state) {
        return new Pause("pause", state.paused(), state.limit(), state.active());
    }

    public static Send send(SmsMessage message) {
        return new Send("send", message.getId(), message.getRecipient(), message.getBody(),
                message.getExpiresAt().toString());
    }

    public static Error error(String code, UUID id) {
        return new Error("error", code, id);
    }
}
