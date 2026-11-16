package com.ultracoresoft.ultrasmsgateway.webhook;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    List<WebhookDelivery> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            WebhookDelivery.Status status, Instant now, Limit limit);

    Page<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(UUID endpointId, Pageable pageable);

    Optional<WebhookDelivery> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Modifying
    @Query("delete from WebhookDelivery d where d.status <> :pending and d.createdAt < :before")
    int deleteFinishedBefore(@Param("pending") WebhookDelivery.Status pending, @Param("before") Instant before);
}
