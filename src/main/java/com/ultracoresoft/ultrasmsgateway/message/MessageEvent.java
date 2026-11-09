package com.ultracoresoft.ultrasmsgateway.message;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One status change of a message, kept for the audit trail shown in the dashboard. */
@Entity
@Table(name = "message_event")
@Getter
@Setter
@NoArgsConstructor
public class MessageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID messageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageStatus status;

    private UUID deviceId;

    @Column(length = 200)
    private String detail;

    @Column(nullable = false)
    private Instant createdAt;

    static MessageEvent of(UUID messageId, MessageStatus status, UUID deviceId, String detail, Instant at) {
        MessageEvent event = new MessageEvent();
        event.setMessageId(messageId);
        event.setStatus(status);
        event.setDeviceId(deviceId);
        event.setDetail(detail);
        event.setCreatedAt(at);
        return event;
    }
}
