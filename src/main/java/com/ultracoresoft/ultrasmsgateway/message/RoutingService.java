package com.ultracoresoft.ultrasmsgateway.message;

import com.ultracoresoft.ultrasmsgateway.device.KeyDeviceRules;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Routing by operator, per organization: off by default; when on, what to do when no phone of the right network is online. */
@Service
@RequiredArgsConstructor
public class RoutingService {

    /** When no phone of the recipient's network is online: send with another phone, or wait for one. */
    public enum Fallback { ANY, WAIT }

    public record Settings(boolean enabled, Fallback fallback) {
    }

    /** This month: messages sent from the recipient's network, and from another one. */
    public record Stats(long sameNetwork, long otherNetwork) {

        public Integer samePercent() {
            long total = sameNetwork + otherNetwork;
            return total == 0 ? null : (int) Math.round(sameNetwork * 100.0 / total);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectProvider<KeyDeviceRules> rules;

    /** Whether the organization's plan allows routing (always, without an edition with plans). */
    public boolean allowed(UUID organizationId) {
        return rules.stream().findFirst().map(rule -> rule.routing(organizationId)).orElse(true);
    }

    @Transactional(readOnly = true)
    public Settings settings(UUID organizationId) {
        return jdbc.query("select enabled, fallback from routing_settings where organization_id = ?",
                (row, i) -> new Settings(row.getBoolean(1), Fallback.valueOf(row.getString(2))), organizationId)
                .stream().findFirst().orElse(new Settings(false, Fallback.ANY));
    }

    /** The settings applied now: off when the plan does not allow routing. */
    public Settings applied(UUID organizationId) {
        Settings settings = settings(organizationId);
        return settings.enabled() && allowed(organizationId) ? settings : new Settings(false, settings.fallback());
    }

    @Transactional
    public void save(UUID organizationId, Settings settings) {
        if (jdbc.update("update routing_settings set enabled = ?, fallback = ? where organization_id = ?",
                settings.enabled(), settings.fallback().name(), organizationId) == 0) {
            jdbc.update("insert into routing_settings (organization_id, enabled, fallback) values (?, ?, ?)",
                    organizationId, settings.enabled(), settings.fallback().name());
        }
    }

    @Transactional(readOnly = true)
    public Stats stats(UUID organizationId, Instant since) {
        return jdbc.queryForObject("select sum(case when recipient_network = sent_network then 1 else 0 end), "
                        + "sum(case when recipient_network <> sent_network then 1 else 0 end) from sms_message "
                        + "where organization_id = ? and dispatched_at >= ? and recipient_network is not null and sent_network is not null",
                (row, i) -> new Stats(row.getLong(1), row.getLong(2)), organizationId, java.sql.Timestamp.from(since));
    }
}
