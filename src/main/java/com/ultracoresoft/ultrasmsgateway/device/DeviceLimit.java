package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * How many phones an organization may use. The open-source gateway has no limit and declares no such bean; an
 * edition that sells plans provides one.
 *
 * <p>Past the limit, no phone can be added. When the limit goes down (a cheaper plan), the phones paired first
 * keep working and the others stay listed but are paused: they receive no messages until the limit allows them
 * again or another phone is revoked.
 */
public interface DeviceLimit {

    /** The most active phones the organization may have; empty when there is no limit. */
    OptionalInt maxDevices(UUID organizationId);

    /** The page where the limit can be raised (the plans), shown next to it; empty when there is none. */
    default Optional<String> upgradePath() {
        return Optional.empty();
    }
}
