package com.ultracoresoft.ultrasmsgateway.message;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID>, JpaSpecificationExecutor<SmsMessage> {

    Optional<SmsMessage> findByIdAndApiKeyId(UUID id, UUID apiKeyId);

    Optional<SmsMessage> findByApiKeyIdAndClientReference(UUID apiKeyId, String clientReference);

    /** Oldest messages first: the queue is first in, first out. */
    List<SmsMessage> findByStatusAndExpiresAtAfterOrderByCreatedAtAsc(MessageStatus status, Instant now, Limit limit);

    List<SmsMessage> findByDeviceIdAndStatusAndExpiresAtAfter(UUID deviceId, MessageStatus status, Instant now);

    long countByDeviceIdAndStatusIn(UUID deviceId, Collection<MessageStatus> statuses);

    long countByStatus(MessageStatus status);

    List<SmsMessage> findByStatusInAndExpiresAtBefore(Collection<MessageStatus> statuses, Instant now);

    List<SmsMessage> findByStatusAndDispatchedAtBefore(MessageStatus status, Instant before);

    List<SmsMessage> findByStatusAndAcceptedAtBefore(MessageStatus status, Instant before);

    @Query("select m.status as status, count(m) as total from SmsMessage m where m.createdAt >= :since group by m.status")
    List<StatusCount> countByStatusSince(@Param("since") Instant since);

    interface StatusCount {
        MessageStatus getStatus();

        Long getTotal();
    }

    /** Creation time and status of recent messages, for the overview chart. */
    @Query("select m.createdAt as createdAt, m.status as status from SmsMessage m where m.createdAt >= :since")
    List<CreatedStatus> findCreatedStatusSince(@Param("since") Instant since);

    interface CreatedStatus {
        Instant getCreatedAt();

        MessageStatus getStatus();
    }
}
