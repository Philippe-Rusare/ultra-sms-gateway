package com.ultracoresoft.ultrasmsgateway.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One request to send to one address, and what happened to it. */
@Entity
@Table(name = "webhook_delivery")
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    /** Chosen before the request is written, since the payload carries it. */
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID endpointId;

    @Column(nullable = false)
    private UUID organizationId;

    /** Event type, e.g. message.delivered. */
    @Column(nullable = false, length = 40)
    private String event;

    private UUID messageId;

    @Column(nullable = false, length = 4000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(nullable = false)
    private int attempts;

    /** When the next attempt is due; null once the delivery is finished. */
    private Instant nextAttemptAt;

    private Integer lastStatusCode;

    @Column(length = 200)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;
}
