package com.ultracoresoft.ultrasmsgateway.dashboard;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** Date formatting for the templates, used as {@code ${@fmt.dateTime(...)}}. */
@Component("fmt")
public class Formats {

    private final ZoneId zone;
    private final DateTimeFormatter formatter;

    public Formats(GatewayProperties properties) {
        this.zone = ZoneId.of(properties.dashboard().timeZone());
        this.formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone);
    }

    public String dateTime(Instant instant) {
        return instant == null ? "—" : formatter.format(instant);
    }

    public String zone() {
        return zone.getId();
    }
}
