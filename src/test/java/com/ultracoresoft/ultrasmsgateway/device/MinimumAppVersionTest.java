package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** A gateway that no longer accepts versions of the app older than 1.2.0. */
@TestPropertySource(properties = "gateway.devices.minimum-app-version=1.2.0")
class MinimumAppVersionTest extends IntegrationTest {

    @Autowired
    DeviceService devices;

    String deviceToken;

    @BeforeEach
    void setUp() {
        deviceToken = devices.create(Organization.DEFAULT_ID, "old-phone").secret();
    }

    @Test
    void anOlderVersionIsRefusedAtTheHandshake() {
        assertThatThrownBy(() -> DeviceWebSocketTest.FakeDevice.connect(port, deviceToken, "1.1.0"))
                .hasStackTraceContaining("426");
    }

    @Test
    void theMinimumAndNewerVersionsAreAccepted() throws Exception {
        var device = DeviceWebSocketTest.FakeDevice.connect(port, deviceToken, "1.2.0");
        assertThat(device.next().get("type").asString()).isEqualTo("hello");
        device.session.close();
    }

    @Test
    void aPhoneThatReportsAnOlderVersionIsToldToUpdateAndDisconnected() throws Exception {
        // Connected without saying its version, as before 1.0.0.
        var device = DeviceWebSocketTest.FakeDevice.connect(port, deviceToken);
        assertThat(device.next().get("type").asString()).isEqualTo("hello");
        device.send("{\"type\":\"heartbeat\",\"battery\":80,\"appVersion\":\"1.0.0\"}");
        var frame = device.next();
        assertThat(frame.get("type").asString()).isEqualTo("update_required");
        assertThat(frame.get("minimumVersion").asString()).isEqualTo("1.2.0");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (device.session.isOpen() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(device.session.isOpen()).isFalse();
    }
}
