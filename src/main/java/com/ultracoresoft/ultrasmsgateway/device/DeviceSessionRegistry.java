package com.ultracoresoft.ultrasmsgateway.device;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/**
 * Devices connected to this instance. Sessions live in memory, which is why the gateway runs as a single
 * instance for now (see docs/architecture.md).
 */
@Component
public class DeviceSessionRegistry {

    static final CloseStatus REPLACED = new CloseStatus(4000, "Replaced by a newer connection");
    static final CloseStatus REVOKED = new CloseStatus(4001, "Device revoked");

    private final ConcurrentMap<UUID, DeviceSession> sessions = new ConcurrentHashMap<>();

    public void register(DeviceSession session) {
        DeviceSession previous = sessions.put(session.deviceId(), session);
        if (previous != null && !previous.sessionId().equals(session.sessionId())) {
            previous.close(REPLACED);
        }
    }

    /** Removes the session only if it is still the current one, so a newer connection is kept. */
    public void unregister(UUID deviceId, String sessionId) {
        sessions.computeIfPresent(deviceId, (id, current) -> current.sessionId().equals(sessionId) ? null : current);
    }

    public Optional<DeviceSession> get(UUID deviceId) {
        return Optional.ofNullable(sessions.get(deviceId));
    }

    public Collection<DeviceSession> all() {
        return List.copyOf(sessions.values());
    }

    public boolean isOnline(UUID deviceId) {
        DeviceSession session = sessions.get(deviceId);
        return session != null && session.isOpen();
    }

    public void disconnect(UUID deviceId) {
        DeviceSession session = sessions.remove(deviceId);
        if (session != null) {
            session.close(REVOKED);
        }
    }
}
