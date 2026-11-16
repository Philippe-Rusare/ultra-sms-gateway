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
        @Valid @NotNull Dashboard dashboard,
        @Valid @NotNull Webhooks webhooks) {

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

    /**
     * Webhooks.
     *
     * @param interval              how often due webhook requests are sent
     * @param timeout               how long the client's application has to answer one request
     * @param maxAttempts           attempts before a request is given up
     * @param retention             how long finished requests stay in the history
     * @param allowPrivateNetworks  whether addresses of the local network (10.x, 192.168.x, localhost...) may receive
     *                              webhooks: true for a self-hosted gateway, false for a shared service, where they
     *                              would let a client reach the service's own network
     * @param requireHttps          whether addresses must use HTTPS
     */
    public record Webhooks(
            @NotNull Duration interval,
            @NotNull Duration timeout,
            @Min(1) int maxAttempts,
            @NotNull Duration retention,
            boolean allowPrivateNetworks,
            boolean requireHttps) {
    }

    /** Time zone used to display dates in the dashboard, e.g. {@code Africa/Kinshasa}. */
    public record Dashboard(@NotBlank String timeZone) {
    }
}
