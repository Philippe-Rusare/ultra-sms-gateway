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

    private final DeviceService devices;

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
        attributes.put(DEVICE_ID_ATTRIBUTE, deviceId.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
        // Nothing to do.
    }
}
