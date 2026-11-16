package com.ultracoresoft.ultrasmsgateway.webhook;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {

    List<WebhookEndpoint> findByOrganizationIdAndActiveTrue(UUID organizationId);

    Page<WebhookEndpoint> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    Optional<WebhookEndpoint> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
