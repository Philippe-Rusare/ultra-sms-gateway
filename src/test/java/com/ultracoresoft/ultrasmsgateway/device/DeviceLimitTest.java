package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ultracoresoft.ultrasmsgateway.organization.OrganizationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.domain.Pageable;

/** The limit an edition with plans sets on the phones of an organization (DeviceLimit). */
class DeviceLimitTest {

    private final UUID organizationId = UUID.randomUUID();
    private final DeviceRepository repository = mock(DeviceRepository.class);

    private DeviceService service(DeviceLimit... limits) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < limits.length; i++) {
            beans.addBean("limit" + i, limits[i]);
        }
        when(repository.save(any(Device.class))).thenAnswer(call -> call.getArgument(0));
        return new DeviceService(repository, mock(OrganizationRepository.class),
                new StaticListableBeanFactory().getBeanProvider(DevicePlanSource.class), beans.getBeanProvider(DeviceLimit.class),
                mock(DeviceSessionRegistry.class), Clock.systemUTC());
    }

    private static DeviceLimit limitOf(int phones) {
        return new DeviceLimit() {
            @Override
            public OptionalInt maxDevices(UUID organizationId) {
                return OptionalInt.of(phones);
            }

            @Override
            public Optional<String> upgradePath() {
                return Optional.of("/billing");
            }
        };
    }

    private Device device(int minutes) {
        Device device = new Device();
        device.setId(UUID.randomUUID());
        device.setOrganizationId(organizationId);
        device.setName("Phone " + minutes);
        device.setCreatedAt(Instant.EPOCH.plusSeconds(60L * minutes));
        when(repository.findById(device.getId())).thenReturn(Optional.of(device));
        return device;
    }

    @Test
    void withoutALimitPhonesAreAddedFreely() {
        when(repository.countByOrganizationIdAndRevokedAtIsNull(organizationId)).thenReturn(40L);
        DeviceService devices = service();
        assertThat(devices.allowance(organizationId).limit()).isNull();
        assertThat(devices.create(organizationId, "Phone").value().getName()).isEqualTo("Phone");
    }

    @Test
    void noPhoneIsAddedPastTheLimit() {
        when(repository.countByOrganizationIdAndRevokedAtIsNull(organizationId)).thenReturn(1L);
        DeviceService devices = service(limitOf(1));
        DeviceService.Allowance allowance = devices.allowance(organizationId);
        assertThat(allowance.full()).isTrue();
        assertThat(allowance.upgradePath()).isEqualTo("/billing");
        assertThatThrownBy(() -> devices.create(organizationId, "Second phone"))
                .isInstanceOf(DeviceLimitReachedException.class);
        verify(repository, never()).save(any(Device.class));
    }

    @Test
    void underTheLimitAPhoneIsAdded() {
        when(repository.countByOrganizationIdAndRevokedAtIsNull(organizationId)).thenReturn(4L);
        assertThat(service(limitOf(5)).create(organizationId, "Fifth phone").value().getName()).isEqualTo("Fifth phone");
    }

    @Test
    void pastALowerLimitTheLatestPhonesArePaused() {
        Device first = device(1);
        Device second = device(2);
        Device third = device(3);
        // The repository returns the first ones paired, as many as asked.
        when(repository.findByOrganizationIdAndRevokedAtIsNullOrderByCreatedAtAsc(eq(organizationId), any(Pageable.class)))
                .thenAnswer(call -> {
                    Pageable page = call.getArgument(1);
                    List<Device> all = List.of(first, second, third);
                    return page.isPaged() ? all.subList(0, Math.min(page.getPageSize(), all.size())) : all;
                });
        DeviceService devices = service(limitOf(1));
        assertThat(devices.receiving(organizationId)).containsExactly(first.getId());
        assertThat(devices.mayReceive(first.getId())).isTrue();
        assertThat(devices.mayReceive(third.getId())).isFalse();
        assertThat(service().mayReceive(third.getId())).isTrue();
    }
}
