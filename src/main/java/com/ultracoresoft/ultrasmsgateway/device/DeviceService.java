package com.ultracoresoft.ultrasmsgateway.device;

import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.common.Secrets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DeviceService {

    public static final String TOKEN_PREFIX = "usgd_";

    private final DeviceRepository repository;
    private final OrganizationRepository organizations;
    private final ObjectProvider<DevicePlanSource> plans;
    private final ObjectProvider<DeviceLimit> limits;
    private final DeviceSessionRegistry sessions;
    private final Clock clock;

    /** What a phone is told about itself when it connects. */
    public record Profile(String deviceName, String organizationName, String plan) {
    }

    @Transactional(readOnly = true)
    public Profile profile(UUID deviceId) {
        return repository.findById(deviceId)
                .map(device -> new Profile(
                        device.getName(),
                        organizations.findById(device.getOrganizationId()).map(Organization::getName).orElse(null),
                        plans.stream().findFirst().flatMap(source -> source.plan(device.getOrganizationId())).orElse(null)))
                .orElse(new Profile(null, null, null));
    }

    /**
     * How many active phones the organization has and how many it may have ({@code limit} null: no limit).
     *
     * @param upgradePath where the limit can be raised, or null
     */
    public record Allowance(long used, Integer limit, String upgradePath) {

        /** No phone can be added. */
        public boolean full() {
            return limit != null && used >= limit;
        }
    }

    @Transactional(readOnly = true)
    public Allowance allowance(UUID organizationId) {
        OptionalInt limit = limitOf(organizationId);
        return new Allowance(repository.countByOrganizationIdAndRevokedAtIsNull(organizationId),
                limit.isPresent() ? Integer.valueOf(limit.getAsInt()) : null,
                limits.stream().findFirst().flatMap(DeviceLimit::upgradePath).orElse(null));
    }

    /** Refuses when the organization already has every phone its limit allows. */
    public void checkRoomFor(UUID organizationId) {
        Allowance allowance = allowance(organizationId);
        if (allowance.full()) {
            throw new DeviceLimitReachedException(allowance.limit());
        }
    }

    /**
     * The active phones of the organization that receive messages: all of them without a limit, otherwise the first
     * ones paired, as many as the limit allows. The others are paused.
     */
    @Transactional(readOnly = true)
    public Set<UUID> receiving(UUID organizationId) {
        OptionalInt limit = limitOf(organizationId);
        Pageable first = limit.isPresent() ? PageRequest.of(0, Math.max(limit.getAsInt(), 1)) : Pageable.unpaged();
        List<Device> active = repository.findByOrganizationIdAndRevokedAtIsNullOrderByCreatedAtAsc(organizationId, first);
        if (limit.isPresent() && limit.getAsInt() <= 0) {
            return Set.of();
        }
        return active.stream().map(Device::getId).collect(Collectors.toSet());
    }

    /**
     * Whether a phone is paused, past the phones its organization's plan allows, with what the app shows: the limit
     * and the names of the phones that do send (the first ones paired).
     */
    public record PauseState(boolean paused, Integer limit, List<String> active) {

        static final PauseState NONE = new PauseState(false, null, List.of());
    }

    @Transactional(readOnly = true)
    public PauseState pauseState(UUID deviceId) {
        return repository.findById(deviceId).filter(Device::isActive).map(device -> {
            OptionalInt limit = limitOf(device.getOrganizationId());
            if (limit.isEmpty()) {
                return PauseState.NONE;
            }
            List<Device> first = repository.findByOrganizationIdAndRevokedAtIsNullOrderByCreatedAtAsc(
                    device.getOrganizationId(), PageRequest.of(0, Math.max(limit.getAsInt(), 1)));
            boolean paused = limit.getAsInt() <= 0 || first.stream().noneMatch(d -> d.getId().equals(deviceId));
            return new PauseState(paused, limit.getAsInt(), paused ? first.stream().map(Device::getName).toList() : List.of());
        }).orElse(PauseState.NONE);
    }

    /** Whether this phone may be given messages (false for a paused phone, see {@link #receiving}). */
    @Transactional(readOnly = true)
    public boolean mayReceive(UUID deviceId) {
        return repository.findById(deviceId)
                .filter(Device::isActive)
                .map(device -> limitOf(device.getOrganizationId()).isEmpty()
                        || receiving(device.getOrganizationId()).contains(deviceId))
                .orElse(false);
    }

    private OptionalInt limitOf(UUID organizationId) {
        return limits.stream().findFirst().map(limit -> limit.maxDevices(organizationId)).orElse(OptionalInt.empty());
    }

    /** Adds a phone; refused with {@link DeviceLimitReachedException} past the limit of the organization. */
    @Transactional
    public Issued<Device> create(UUID organizationId, String name) {
        checkRoomFor(organizationId);
        String token = Secrets.generate(TOKEN_PREFIX);
        Device device = new Device();
        device.setOrganizationId(organizationId);
        device.setName(name.trim());
        device.setTokenPrefix(Secrets.displayPrefix(token));
        device.setTokenHash(Secrets.hash(token));
        device.setCreatedAt(clock.instant());
        return new Issued<>(repository.save(device), token);
    }

    /** Renames the device if it belongs to the organization. The name is trimmed by the caller's checks. */
    @Transactional
    public void rename(UUID organizationId, UUID id, String name) {
        repository.findByIdAndOrganizationId(id, organizationId).ifPresent(item -> item.setName(name.trim()));
    }

    @Transactional(readOnly = true)
    public Page<Device> page(UUID organizationId, Pageable pageable) {
        return repository.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId, pageable);
    }

    @Transactional
    public void revoke(UUID organizationId, UUID id) {
        repository.findByIdAndOrganizationId(id, organizationId)
                .filter(Device::isActive)
                .ifPresent(device -> {
                    device.setRevokedAt(clock.instant());
                    sessions.disconnect(id);
                });
    }

    @Transactional(readOnly = true)
    public Optional<UUID> authenticate(String token) {
        if (token == null || !token.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByTokenHash(Secrets.hash(token))
                .filter(Device::isActive)
                .map(Device::getId);
    }

    @Transactional
    public void markConnected(UUID id) {
        repository.findById(id).ifPresent(device -> {
            Instant now = clock.instant();
            device.setLastConnectedAt(now);
            device.setLastSeenAt(now);
        });
    }

    @Transactional
    public void recordHeartbeat(UUID id, DeviceFrame frame) {
        repository.findById(id).ifPresent(device -> {
            device.setLastSeenAt(clock.instant());
            device.setBatteryLevel(clamp(frame.battery(), 0, 100));
            device.setCharging(frame.charging());
            device.setSignalLevel(clamp(frame.signal(), 0, 4));
            device.setOperatorName(truncate(frame.operator(), 100));
            device.setNetworkType(truncate(frame.network(), 30));
            device.setAppVersion(truncate(frame.appVersion(), 30));
        });
    }

    @Transactional(readOnly = true)
    public List<Device> list(UUID organizationId) {
        return repository.findAllByOrganizationIdOrderByCreatedAtDesc(organizationId);
    }

    private static Integer clamp(Integer value, int min, int max) {
        return value == null ? null : Math.max(min, Math.min(max, value));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
