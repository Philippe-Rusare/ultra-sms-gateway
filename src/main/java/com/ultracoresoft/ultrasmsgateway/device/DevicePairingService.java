package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pairing by QR code: a one-time code created in the dashboard, exchanged once by the app for a device token. */
@Service
@RequiredArgsConstructor
public class DevicePairingService {

    public static final String CODE_PREFIX = "usgp_";
    public static final Duration VALIDITY = Duration.ofMinutes(10);

    private final DevicePairingRepository pairings;
    private final DeviceService devices;
    private final Clock clock;

    /** The device a pairing code was exchanged for, with its token, available only at that moment. */
    public record PairedDevice(UUID deviceId, String name, String token) {
    }

    /** Starts a pairing. The returned secret is the code to show as a QR code; it is not stored. */
    @Transactional
    public Issued<DevicePairing> start(UUID organizationId, String deviceName) {
        String code = Secrets.generate(CODE_PREFIX);
        Instant now = clock.instant();
        DevicePairing pairing = new DevicePairing();
        pairing.setOrganizationId(organizationId);
        pairing.setDeviceName(deviceName.trim());
        pairing.setCodeHash(Secrets.hash(code));
        pairing.setCreatedAt(now);
        pairing.setExpiresAt(now.plus(VALIDITY));
        return new Issued<>(pairings.save(pairing), code);
    }

    /**
     * Exchanges a code for a new device in the organization that created the code.
     * Empty when the code is unknown, expired or already used.
     */
    @Transactional
    public Optional<PairedDevice> complete(String code) {
        if (code == null || !code.startsWith(CODE_PREFIX)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return pairings.findByCodeHash(Secrets.hash(code))
                .filter(pairing -> pairing.statusAt(now) == DevicePairing.Status.PENDING)
                .map(pairing -> {
                    Issued<Device> issued = devices.create(pairing.getOrganizationId(), pairing.getDeviceName());
                    pairing.setUsedAt(now);
                    pairing.setDeviceId(issued.value().getId());
                    return new PairedDevice(issued.value().getId(), issued.value().getName(), issued.secret());
                });
    }

    @Transactional(readOnly = true)
    public Optional<DevicePairing> find(UUID organizationId, UUID id) {
        return pairings.findByIdAndOrganizationId(id, organizationId);
    }
}
