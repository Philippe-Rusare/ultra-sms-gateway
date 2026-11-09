package com.ultracoresoft.ultrasmsgateway.api;

import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import java.time.Instant;
import java.util.UUID;

/** The text of the message is never returned. */
public record MessageResponse(
        UUID id,
        String to,
        MessageStatus status,
        String clientReference,
        int attempts,
        String failureReason,
        Instant createdAt,
        Instant expiresAt,
        Instant sentAt,
        Instant deliveredAt) {

    static MessageResponse from(SmsMessage message) {
        return new MessageResponse(
                message.getId(),
                message.getRecipient(),
                message.getStatus(),
                message.getClientReference(),
                message.getAttempts(),
                message.getFailureReason(),
                message.getCreatedAt(),
                message.getExpiresAt(),
                message.getSentAt(),
                message.getDeliveredAt());
    }
}
