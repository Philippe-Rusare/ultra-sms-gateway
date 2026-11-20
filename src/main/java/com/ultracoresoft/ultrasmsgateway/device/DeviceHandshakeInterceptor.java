package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Authenticates the device with {@code Authorization: Bearer <device token>} during the handshake.
 * The token is never accepted in the query string, where proxies would log it.
 */
@Component
@RequiredArgsConstructor
public class DeviceHandshakeInterceptor implements HandshakeInterceptor {

    static final String DEVICE_ID_ATTRIBUTE = "deviceId";
    private static final String BEARER = "Bearer ";
    /** The version of the app, sent by the app with the handshake (see docs/device-protocol.md). */
    static final String APP_VERSION_HEADER = "X-App-Version";
    /** Sent back with {@code 426 Upgrade Required} to a version the gateway no longer accepts. */
    static final String MINIMUM_VERSION_HEADER = "X-Minimum-App-Version";

    private final DeviceService devices;
    private final AppVersionPolicy versions;
    private final DeviceRepository deviceRepository;
    private final com.ultracoresoft.ultrasmsgateway.organization.OrganizationSuspensions suspensions;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String token = authorization != null && authorization.startsWith(BEARER)
                ? authorization.substring(BEARER.length()).trim()
                : null;
        Optional<UUID> deviceId = devices.authenticate(token);
        if (deviceId.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // A suspended organization: refused until it is reactivated; the phone keeps trying (docs/device-protocol.md).
        if (deviceRepository.findById(deviceId.get()).map(device -> suspensions.isSuspended(device.getOrganizationId())).orElse(false)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
        // Checked once the phone is known, so that the answer tells nothing to an unknown caller.
        if (versions.isTooOld(request.getHeaders().getFirst(APP_VERSION_HEADER))) {
            response.setStatusCode(HttpStatus.UPGRADE_REQUIRED);
            response.getHeaders().set(MINIMUM_VERSION_HEADER, versions.minimum());
            return false;
        }
        attributes.put(DEVICE_ID_ATTRIBUTE, deviceId.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
        // Nothing to do.
    }
}
