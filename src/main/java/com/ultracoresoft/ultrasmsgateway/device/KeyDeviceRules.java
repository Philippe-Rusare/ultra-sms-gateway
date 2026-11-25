package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * What an organization may do with its phones besides sending: tie an API key to some phones, and route each SMS
 * by operator. The open-source gateway allows both and declares no such bean; an edition that sells plans provides
 * one (for example: one phone per key on one plan, several phones and routing on another).
 */
public interface KeyDeviceRules {

    /** The most phones one API key may be tied to; empty: no limit; 0: keys cannot be tied (they use every phone). */
    OptionalInt maxDevicesPerKey(UUID organizationId);

    /** Whether routing by operator may be turned on. */
    boolean routing(UUID organizationId);

    /** The page where these can be opened (the plans), shown next to what is not allowed; empty when there is none. */
    default Optional<String> upgradePath() {
        return Optional.empty();
    }
}
