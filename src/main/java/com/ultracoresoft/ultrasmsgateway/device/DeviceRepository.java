package com.ultracoresoft.ultrasmsgateway.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {

    Optional<Device> findByTokenHash(String tokenHash);

    List<Device> findAllByOrderByCreatedAtDesc();
}
