package com.ultracoresoft.ultrasmsgateway.organization;

import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceRepository;
import com.ultracoresoft.ultrasmsgateway.device.DeviceSessionRegistry;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suspends an organization and reactivates it. While suspended, its API answers {@code 403} and its phones are
 * refused at the handshake ({@code 403}); those connected are closed at once (code {@code 4003}) and keep trying,
 * so that they come back by themselves once it is reactivated. Nothing is deleted.
 */
@Service
@RequiredArgsConstructor
public class OrganizationSuspensions {

    private final OrganizationRepository organizations;
    private final DeviceRepository devices;
    private final DeviceSessionRegistry sessions;
    private final Clock clock;

    public boolean isSuspended(UUID organizationId) {
        return organizationId != null && organizations.findById(organizationId).map(Organization::isSuspended).orElse(false);
    }

    @Transactional
    public void suspend(UUID organizationId, String reason) {
        Organization organization = organizations.findById(organizationId).orElseThrow();
        organization.setSuspendedAt(clock.instant());
        organization.setSuspensionReason(reason == null || reason.length() <= 200 ? reason : reason.substring(0, 200));
        devices.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId).stream().map(Device::getId).forEach(sessions::suspend);
    }

    @Transactional
    public void reactivate(UUID organizationId) {
        Organization organization = organizations.findById(organizationId).orElseThrow();
        organization.setSuspendedAt(null);
        organization.setSuspensionReason(null);
    }
}
