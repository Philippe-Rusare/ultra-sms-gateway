package com.ultracoresoft.ultrasmsgateway.device;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A JSON frame sent by a device. {@code type} is one of {@code ack}, {@code sent}, {@code delivered},
 * {@code failed} (with {@code id}, and {@code reason} for failures) or {@code heartbeat} (with the device state).
 * See docs/device-protocol.md.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeviceFrame(
        String type,
        UUID id,
        String reason,
        Integer battery,
        Boolean charging,
        Integer signal,
        String operator,
        String network,
        String appVersion) {
}
