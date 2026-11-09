package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    public static final String TOKEN_PREFIX = "usgd_";

    private final DeviceRepository repository;
    private final DeviceSessionRegistry sessions;
    private final Clock clock;

    @Transactional
    public Issued<Device> create(String name) {
        String token = Secrets.generate(TOKEN_PREFIX);
        Device device = new Device();
        device.setName(name.trim());
        device.setTokenPrefix(Secrets.displayPrefix(token));
        device.setTokenHash(Secrets.hash(token));
        device.setCreatedAt(clock.instant());
        return new Issued<>(repository.save(device), token);
    }

    @Transactional
    public void revoke(UUID id) {
        repository.findById(id)
                .filter(Device::isActive)
                .ifPresent(device -> device.setRevokedAt(clock.instant()));
        sessions.disconnect(id);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByTokenHash(Secrets.hash(token))
                .filter(Device::isActive)
                .map(Device::getId);
    }

    @Transactional
    public void markConnected(UUID id) {
        repository.findById(id).ifPresent(device -> {
            Instant now = clock.instant();
            device.setLastConnectedAt(now);
            device.setLastSeenAt(now);
        });
    }

    @Transactional
    public void recordHeartbeat(UUID id, DeviceFrame frame) {
        repository.findById(id).ifPresent(device -> {
            device.setLastSeenAt(clock.instant());
            device.setBatteryLevel(clamp(frame.battery(), 0, 100));
            device.setCharging(frame.charging());
            device.setSignalLevel(clamp(frame.signal(), 0, 4));
            device.setOperatorName(truncate(frame.operator(), 100));
            device.setNetworkType(truncate(frame.network(), 30));
            device.setAppVersion(truncate(frame.appVersion(), 30));
        });
    }

    @Transactional(readOnly = true)
    public List<Device> list() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    private static Integer clamp(Integer value, int min, int max) {
        return value == null ? null : Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
