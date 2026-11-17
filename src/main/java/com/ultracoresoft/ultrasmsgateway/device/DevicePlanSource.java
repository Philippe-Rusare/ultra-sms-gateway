package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Optional;
import java.util.UUID;

/**
 * The plan of an organization, sent to its phones in the {@code hello} frame so that the app can show it.
 * The open-source gateway has no plans and declares no such bean; an edition that sells plans provides one.
 * Plans are identified in lower case: {@code free}, {@code pro}, {@code business}.
 */
public interface DevicePlanSource {

    Optional<String> plan(UUID organizationId);
}
