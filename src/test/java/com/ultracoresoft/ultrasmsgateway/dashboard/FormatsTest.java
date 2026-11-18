package com.ultracoresoft.ultrasmsgateway.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FormatsTest {

    private final Formats formats = new Formats(new GatewayProperties(null, null, null, new GatewayProperties.Dashboard("UTC"), null));

    @Test
    void numbersAreMaskedAsInThePhoneApp() {
        assertThat(formats.masked("+243812345678")).isEqualTo("+243 81 ••• 5678");
        assertThat(formats.masked("0812345678")).isEqualTo("08 ••• 5678");
        assertThat(formats.masked("12345")).isEqualTo("12345");
        assertThat(formats.masked(null)).isEqualTo("—");
    }

    @Test
    void timeIsHoursAndMinutes() {
        assertThat(formats.time(Instant.parse("2026-11-18T10:34:59Z"))).isEqualTo("10:34");
        assertThat(formats.time(null)).isEqualTo("—");
    }
}
