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

    /** Hour and minutes, in the time zone of the dashboard: "10:34". */
    public String time(Instant instant) {
        return instant == null ? "—" : DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(instant);
    }

    /** A number with its middle hidden, as the phone app shows it: "+243 81 ••• 4567". */
    public String masked(String number) {
        if (number == null || number.isBlank()) {
            return "—";
        }
        String digits = number.replaceAll("[^0-9+]", "");
        if (digits.length() < 8) {
            return digits;
        }
        boolean international = digits.startsWith("+");
        int countryEnd = international ? Math.min(4, digits.length() - 6) : 0;
        String country = international ? digits.substring(0, countryEnd) + " " : "";
        String rest = digits.substring(countryEnd);
        return country + rest.substring(0, 2) + " ••• " + rest.substring(rest.length() - 4);
    }

    public String zone() {
        return zone.getId();
    }

    public ZoneId zoneId() {
        return zone;
    }
}
