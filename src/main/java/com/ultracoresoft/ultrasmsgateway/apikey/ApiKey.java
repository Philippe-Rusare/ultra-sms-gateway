package com.ultracoresoft.ultrasmsgateway.apikey;

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

/** Credential of a client application allowed to queue messages. Only the hash of the key is stored. */
@Entity
@Table(name = "api_key")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 16)
    private String keyPrefix;

    @Column(nullable = false, length = 64, unique = true)
    private String keyHash;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    private Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }
}
