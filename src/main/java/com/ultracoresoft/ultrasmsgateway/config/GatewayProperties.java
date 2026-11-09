package com.ultracoresoft.ultrasmsgateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under the {@code gateway.*} prefix. See {@code application.yaml} for defaults.
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @Valid @NotNull Admin admin,
        @Valid @NotNull Messages messages,
        @Valid @NotNull Dispatch dispatch,
        @Valid @NotNull Dashboard dashboard) {

    /** Single dashboard administrator. The password is mandatory and never has a default. */
    public record Admin(@NotBlank String username, String password) {
    }

    /** Lifetime of a queued message when the client does not send {@code ttlSeconds}, and its upper bound. */
    public record Messages(@NotNull Duration defaultTtl, @NotNull Duration maxTtl) {
    }

    /**
     * Dispatch loop tuning.
     *
     * @param interval              how often the maintenance and dispatch loop runs
     * @param ackTimeout            a dispatched message not acknowledged by the device within this delay goes back to the queue
     * @param reportTimeout         an acknowledged message with no sent/failed report within this delay is marked failed
     * @param maxInFlightPerDevice  messages a device may hold at once (dispatched or accepted)
     * @param maxAttempts           dispatch attempts before a message is marked failed
     */
    public record Dispatch(
            @NotNull Duration interval,
            @NotNull Duration ackTimeout,
            @NotNull Duration reportTimeout,
            @Min(1) int maxInFlightPerDevice,
            @Min(1) int maxAttempts) {
    }

    /** Time zone used to display dates in the dashboard, e.g. {@code Africa/Kinshasa}. */
    public record Dashboard(@NotBlank String timeZone) {
    }
}
