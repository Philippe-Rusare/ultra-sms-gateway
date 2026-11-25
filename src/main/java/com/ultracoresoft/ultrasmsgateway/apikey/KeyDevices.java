package com.ultracoresoft.ultrasmsgateway.apikey;

import com.ultracoresoft.ultrasmsgateway.device.KeyDeviceRules;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The phones an API key sends with. A key with no phone uses every phone of its organization. A key tied to phones
 * never sends with another one: with none of them online, its messages wait.
 *
 * <p>The ties are kept when the plan no longer allows them (KeyDeviceRules), but not applied: the key then uses
 * every phone again, until the plan allows them back.
 */
@Service
@RequiredArgsConstructor
public class KeyDevices {

    private final JdbcTemplate jdbc;
    private final ObjectProvider<KeyDeviceRules> rules;

    /** The most phones a key may be tied to: empty, no limit; 0, keys cannot be tied. */
    public OptionalInt maxPerKey(UUID organizationId) {
        return rules.stream().findFirst().map(rule -> rule.maxDevicesPerKey(organizationId)).orElse(OptionalInt.empty());
    }

    /** The page where more phones per key can be opened (the plans); null when there is none. */
    public String upgradePath() {
        return rules.stream().findFirst().flatMap(KeyDeviceRules::upgradePath).orElse(null);
    }

    /** The phones a key is tied to, as saved (whether or not the plan applies them). */
    @Transactional(readOnly = true)
    public Set<UUID> of(UUID apiKeyId) {
        return new LinkedHashSet<>(jdbc.queryForList("select device_id from api_key_device where api_key_id = ?", UUID.class, apiKeyId));
    }

    /**
     * Ties the key to these phones of its organization (none: every phone). Refused past the limit of the plan, and
     * for a key or phones of another organization.
     */
    @Transactional
    public void set(UUID organizationId, UUID apiKeyId, Collection<UUID> deviceIds) {
        Integer owned = jdbc.queryForObject("select count(*) from api_key where id = ? and organization_id = ?", Integer.class, apiKeyId, organizationId);
        if (owned == null || owned == 0) {
            throw new IllegalArgumentException("Unknown API key.");
        }
        Set<UUID> wanted = new LinkedHashSet<>();
        for (UUID deviceId : deviceIds) {
            if (deviceId == null) {
                continue;
            }
            Integer ours = jdbc.queryForObject("select count(*) from device where id = ? and organization_id = ? and revoked_at is null",
                    Integer.class, deviceId, organizationId);
            if (ours != null && ours > 0) {
                wanted.add(deviceId);
            }
        }
        OptionalInt max = maxPerKey(organizationId);
        if (!wanted.isEmpty() && max.isPresent() && wanted.size() > max.getAsInt()) {
            throw new IllegalArgumentException("Too many phones for one key.");
        }
        jdbc.update("delete from api_key_device where api_key_id = ?", apiKeyId);
        for (UUID deviceId : wanted) {
            jdbc.update("insert into api_key_device (api_key_id, device_id) values (?, ?)", apiKeyId, deviceId);
        }
    }

    /**
     * The ties applied now in the organization, by key: only the keys tied to phones, and only if the plan allows
     * their number of phones.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> applied(UUID organizationId) {
        OptionalInt max = maxPerKey(organizationId);
        if (max.isPresent() && max.getAsInt() == 0) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> ties = saved(organizationId);
        if (max.isPresent()) {
            ties.values().removeIf(devices -> devices.size() > max.getAsInt());
        }
        return ties;
    }

    /** Every tie of the organization, as saved, by key. */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> saved(UUID organizationId) {
        Map<UUID, Set<UUID>> ties = new HashMap<>();
        jdbc.query("select k.id, d.device_id from api_key_device d join api_key k on k.id = d.api_key_id where k.organization_id = ?",
                row -> {
                    ties.computeIfAbsent(row.getObject(1, UUID.class), id -> new LinkedHashSet<>()).add(row.getObject(2, UUID.class));
                }, organizationId);
        return ties;
    }

    /** The names of the active keys tied to each phone (keys using every phone are not listed). */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> keysByDevice(UUID organizationId) {
        Map<UUID, List<String>> keys = new HashMap<>();
        jdbc.query("select d.device_id, k.name from api_key_device d join api_key k on k.id = d.api_key_id "
                        + "where k.organization_id = ? and k.revoked_at is null order by k.name",
                row -> {
                    keys.computeIfAbsent(row.getObject(1, UUID.class), id -> new java.util.ArrayList<>()).add(row.getString(2));
                }, organizationId);
        return keys;
    }
}
