package com.ultracoresoft.ultrasmsgateway.device;

import java.io.IOException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

/** Live connection of a device. Safe to use from several threads. */
@Slf4j
public final class DeviceSession {

    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final UUID deviceId;
    private final WebSocketSession session;
    private final JsonMapper json;

    public DeviceSession(UUID deviceId, WebSocketSession session, JsonMapper json) {
        this.deviceId = deviceId;
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        this.json = json;
    }

    public UUID deviceId() {
        return deviceId;
    }

    public String sessionId() {
        return session.getId();
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    /** Returns false when the frame could not be written; the caller relies on timeouts to recover. */
    public boolean send(Object frame) {
        try {
            session.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write to device {}: {}", deviceId, e.getMessage());
            return false;
        }
    }

    public void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("Error while closing the session of device {}", deviceId, e);
        }
    }
}
