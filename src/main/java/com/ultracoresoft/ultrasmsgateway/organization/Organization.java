package com.ultracoresoft.ultrasmsgateway.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Owner of API keys, devices and messages. Data of one organization is never visible to, or sent by,
 * another one. A self-hosted gateway has a single organization, {@link #DEFAULT_ID}.
 */
@Entity
@Table(name = "organization")
@Getter
@Setter
@NoArgsConstructor
public class Organization {

    /** The organization created by the database migration, used when the gateway serves a single one. */
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Instant createdAt;

    /** Set while the organization is suspended: its API answers 403 and its phones are refused. */
    private Instant suspendedAt;

    /** Why, as the organization's members are told; null when not suspended. */
    @Column(length = 200)
    private String suspensionReason;

    public boolean isSuspended() {
        return suspendedAt != null;
    }

    public Organization(UUID id, String name, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
    }
}
