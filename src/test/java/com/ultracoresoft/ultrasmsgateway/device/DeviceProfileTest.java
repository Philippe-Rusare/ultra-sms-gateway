package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class DeviceProfileTest {

    private final UUID organizationId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private DeviceService service(DevicePlanSource... sources) {
        DeviceRepository repository = mock(DeviceRepository.class);
        Device device = new Device();
        device.setId(deviceId);
        device.setName("Vodacom · counter");
        device.setOrganizationId(organizationId);
        when(repository.findById(deviceId)).thenReturn(Optional.of(device));
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        when(organizations.findById(organizationId))
                .thenReturn(Optional.of(new Organization(organizationId, "Demo Company", Instant.EPOCH)));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < sources.length; i++) {
            beans.addBean("plans" + i, sources[i]);
        }
        return new DeviceService(repository, organizations, beans.getBeanProvider(DevicePlanSource.class),
                new StaticListableBeanFactory().getBeanProvider(DeviceLimit.class), mock(DeviceSessionRegistry.class), Clock.systemUTC());
    }

    @Test
    void theProfileHoldsTheDeviceAndOrganizationNames() {
        DeviceService.Profile profile = service().profile(deviceId);
        assertThat(profile.deviceName()).isEqualTo("Vodacom · counter");
        assertThat(profile.organizationName()).isEqualTo("Demo Company");
        assertThat(profile.plan()).isNull();
    }

    @Test
    void anEditionWithPlansAddsThePlan() {
        DeviceService.Profile profile = service(id -> id.equals(organizationId) ? Optional.of("pro") : Optional.empty()).profile(deviceId);
        assertThat(profile.plan()).isEqualTo("pro");
    }

    @Test
    void anUnknownDeviceHasAnEmptyProfile() {
        assertThat(service().profile(UUID.randomUUID())).isEqualTo(new DeviceService.Profile(null, null, null));
    }
}
