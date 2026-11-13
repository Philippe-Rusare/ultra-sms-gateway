package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.dashboard.QrCode;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationRepository;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Pairing by QR code: the one-time code is exchanged, without authentication, for a device token. */
class DevicePairingTest extends IntegrationTest {

    @Autowired
    DevicePairingService pairings;

    @Autowired
    DeviceService devices;

    @Autowired
    DeviceRepository deviceRepository;

    @Autowired
    OrganizationRepository organizations;

    private HttpResponse<String> pair(String code) throws Exception {
        return post("/v1/device/pair", null, "{\"code\":\"" + code + "\"}");
    }

    @Test
    void codeIsExchangedOnceForADeviceOfTheSameOrganization() throws Exception {
        UUID acme = organizations.save(new Organization(UUID.randomUUID(), "Acme", clock.instant())).getId();
        Issued<DevicePairing> pairing = pairings.start(acme, "Vodacom phone 1");
        assertThat(pairing.value().statusAt(clock.instant())).isEqualTo(DevicePairing.Status.PENDING);

        HttpResponse<String> response = pair(pairing.secret());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = parse(response.body());
        assertThat(body.path("name").asString()).isEqualTo("Vodacom phone 1");
        assertThat(body.path("deviceUrl").asString()).isEqualTo("ws://localhost:" + port + "/v1/device/ws");

        String token = body.path("token").asString();
        UUID deviceId = devices.authenticate(token).orElseThrow();
        assertThat(deviceId.toString()).isEqualTo(body.path("deviceId").asString());
        assertThat(deviceRepository.findById(deviceId).orElseThrow().getOrganizationId()).isEqualTo(acme);
        assertThat(pairings.find(acme, pairing.value().getId()).orElseThrow().statusAt(clock.instant()))
                .isEqualTo(DevicePairing.Status.PAIRED);

        HttpResponse<String> again = pair(pairing.secret());
        assertThat(again.statusCode()).isEqualTo(404);
        assertThat(again.body()).contains("already used").doesNotContain(token);
    }

    @Test
    void expiredOrUnknownCodeIsRefusedAndCreatesNoDevice() throws Exception {
        long before = deviceRepository.count();
        Issued<DevicePairing> pairing = pairings.start(Organization.DEFAULT_ID, "Late phone");

        clock.advance(Duration.ofMinutes(11));

        assertThat(pair(pairing.secret()).statusCode()).isEqualTo(404);
        assertThat(pair("usgp_not-a-real-code").statusCode()).isEqualTo(404);
        assertThat(pair("").statusCode()).isEqualTo(404);
        assertThat(deviceRepository.count()).isEqualTo(before);
        assertThat(pairing.value().statusAt(clock.instant())).isEqualTo(DevicePairing.Status.EXPIRED);
    }

    @Test
    void pairingOfAnotherOrganizationIsNotVisible() {
        UUID acme = organizations.save(new Organization(UUID.randomUUID(), "Acme", clock.instant())).getId();
        Issued<DevicePairing> pairing = pairings.start(acme, "Acme phone");

        assertThat(pairings.find(Organization.DEFAULT_ID, pairing.value().getId())).isEmpty();
    }

    @Test
    void onlyTheHashOfTheCodeIsStored() {
        Issued<DevicePairing> pairing = pairings.start(Organization.DEFAULT_ID, "Phone");

        assertThat(pairing.value().getCodeHash()).hasSize(64).doesNotContain(pairing.secret());
    }

    @Test
    void qrCodeIsDrawnAsAPath() {
        QrCode qr = QrCode.of("ultrasms://pair?server=https%3A%2F%2Fapp.example.com&code=usgp_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG");

        assertThat(qr.size()).isGreaterThan(20);
        // The top-left finder pattern starts with a run of seven dark modules.
        assertThat(qr.path()).startsWith("M0 0h7v1h-7z");
    }
}
