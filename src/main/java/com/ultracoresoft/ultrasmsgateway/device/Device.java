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

/** An Android phone that sends SMS with its SIM card. The last heartbeat values are kept for the dashboard. */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String tokenPrefix;

    @Column(nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant revokedAt;

    private Instant lastConnectedAt;

    private Instant lastSeenAt;

    private Integer batteryLevel;

    private Boolean charging;

    private Integer signalLevel;

    @Column(length = 100)
    private String operatorName;

    @Column(length = 30)
    private String networkType;

    @Column(length = 30)
    private String appVersion;

    public boolean isActive() {
        return revokedAt == null;
    }
}
