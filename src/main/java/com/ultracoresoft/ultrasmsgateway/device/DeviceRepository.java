package com.ultracoresoft.ultrasmsgateway.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByTokenHash(String tokenHash);

    Optional<Device> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<Device> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);

    Page<Device> findAllByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    long countByOrganizationIdAndRevokedAtIsNull(UUID organizationId);

    /** The active phones in the order they were paired: the first ones are those a limit keeps working. */
    List<Device> findByOrganizationIdAndRevokedAtIsNullOrderByCreatedAtAsc(UUID organizationId, Pageable pageable);
}
