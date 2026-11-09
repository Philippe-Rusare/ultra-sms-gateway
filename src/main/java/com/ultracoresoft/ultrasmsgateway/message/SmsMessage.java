package com.ultracoresoft.ultrasmsgateway.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A message and its queue state. The table is the queue: no broker is needed at this scale.
 * {@code body} is erased as soon as the message is sent, failed or expired, because it often holds an OTP.
 */
@Entity
@Table(name = "sms_message")
@Getter
@Setter
@NoArgsConstructor
public class SmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID apiKeyId;

    @Column(length = 100)
    private String clientReference;

    @Column(nullable = false, length = 16)
    private String recipient;

    @Column(length = 1000)
    private String body;

    @Column(nullable = false)
    private int bodyLength;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    /** Last device the message was dispatched to. Kept when the message is queued again. */
    private UUID deviceId;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 200)
    private String failureReason;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant dispatchedAt;

    private Instant acceptedAt;

    private Instant sentAt;

    private Instant deliveredAt;

    @Version
    private long version;
}
