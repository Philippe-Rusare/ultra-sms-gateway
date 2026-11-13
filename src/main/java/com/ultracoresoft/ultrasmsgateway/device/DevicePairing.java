package com.ultracoresoft.ultrasmsgateway.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A pending "add this phone" request. The dashboard shows its one-time code as a QR code; the app
 * exchanges the code for a device token. The code is never stored, only its hash.
 */
@Entity
@Table(name = "device_pairing")
@Getter
@Setter
@NoArgsConstructor
public class DevicePairing {

    public enum Status { PENDING, PAIRED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 100)
    private String deviceName;

    @Column(nullable = false, length = 64, unique = true)
    private String codeHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    private UUID deviceId;

    @Column(nullable = false)
    private Instant createdAt;

    public Status statusAt(Instant now) {
        if (usedAt != null) {
            return Status.PAIRED;
        }
        return expiresAt.isAfter(now) ? Status.PENDING : Status.EXPIRED;
    }
}
