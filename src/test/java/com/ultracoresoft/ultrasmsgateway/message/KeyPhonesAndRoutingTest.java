package com.ultracoresoft.ultrasmsgateway.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.apikey.KeyDevices;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceRepository;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.device.Network;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** API keys tied to phones, and routing by operator, when phones take the queued messages. */
class KeyPhonesAndRoutingTest extends IntegrationTest {

    @Autowired
    MessageService service;

    @Autowired
    DeviceService devices;

    @Autowired
    DeviceRepository deviceRepository;

    @Autowired
    KeyDevices keyDevices;

    @Autowired
    RoutingService routing;

    ApiKeyPrincipal pharmacy;
    ApiKeyPrincipal agency;
    UUID vodacom;
    UUID airtel;

    @BeforeEach
    void setUp() {
        pharmacy = principal("pharmacy");
        agency = principal("agency");
        vodacom = phone("pharmacy-vodacom", "Vodacom CD");
        airtel = phone("pharmacy-airtel", "Airtel");
    }

    @AfterEach
    void tearDown() {
        routing.save(Organization.DEFAULT_ID, new RoutingService.Settings(false, RoutingService.Fallback.ANY));
        devices.revoke(Organization.DEFAULT_ID, vodacom);
        devices.revoke(Organization.DEFAULT_ID, airtel);
    }

    private ApiKeyPrincipal principal(String name) {
        return new ApiKeyPrincipal(apiKeys.create(Organization.DEFAULT_ID, name).value().getId(), Organization.DEFAULT_ID, name);
    }

    private UUID phone(String name, String operator) {
        Device device = devices.create(Organization.DEFAULT_ID, name).value();
        device.setOperatorName(operator);
        deviceRepository.save(device);
        return device.getId();
    }

    private SmsMessage queue(ApiKeyPrincipal client, String to) {
        return service.submit(client, new NewMessage(to, "Hello", null, null)).message();
    }

    @Test
    void theNetworksAreKnownByPrefixAndBySim() {
        assertThat(Network.ofRecipient("+243812345678")).contains(Network.VODACOM);
        assertThat(Network.ofRecipient("+243971234567")).contains(Network.AIRTEL);
        assertThat(Network.ofRecipient("+243851234567")).contains(Network.ORANGE);
        assertThat(Network.ofRecipient("+243901234567")).contains(Network.AFRICELL);
        assertThat(Network.ofRecipient("+33612345678")).isEmpty();
        assertThat(Network.ofOperator("Vodacom CD")).contains(Network.VODACOM);
        assertThat(Network.ofOperator("ORANGE RDC")).contains(Network.ORANGE);
        assertThat(Network.ofOperator("T-Mobile")).isEmpty();
    }

    @Test
    void aKeyTiedToAPhoneNeverSendsWithAnother() {
        keyDevices.set(Organization.DEFAULT_ID, pharmacy.id(), List.of(airtel));
        SmsMessage tied = queue(pharmacy, "+243812345678");
        SmsMessage free = queue(agency, "+243812345678");

        assertThat(service.claim(vodacom, 10)).extracting(SmsMessage::getId).containsExactly(free.getId());
        assertThat(service.claim(airtel, 10)).extracting(SmsMessage::getId).containsExactly(tied.getId());
    }

    @Test
    void aKeyTiedToNoPhoneUsesThemAll() {
        keyDevices.set(Organization.DEFAULT_ID, pharmacy.id(), List.of());
        SmsMessage message = queue(pharmacy, "+243812345678");
        assertThat(service.claim(vodacom, 10)).extracting(SmsMessage::getId).containsExactly(message.getId());
    }

    @Test
    void phonesOfAnotherOrganizationCannotBeTied() {
        assertThatThrownBy(() -> keyDevices.set(UUID.randomUUID(), pharmacy.id(), List.of(airtel)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withRoutingEachMessageWaitsForItsNetwork() {
        routing.save(Organization.DEFAULT_ID, new RoutingService.Settings(true, RoutingService.Fallback.WAIT));
        SmsMessage toAirtel = queue(agency, "+243971234567");
        SmsMessage toVodacom = queue(agency, "+243812345678");
        SmsMessage abroad = queue(agency, "+33612345678");

        assertThat(service.claim(vodacom, 10)).extracting(SmsMessage::getId).containsExactly(toVodacom.getId(), abroad.getId());
        List<SmsMessage> fromAirtel = service.claim(airtel, 10);
        assertThat(fromAirtel).extracting(SmsMessage::getId).containsExactly(toAirtel.getId());
        assertThat(fromAirtel.getFirst().getRecipientNetwork()).isEqualTo("AIRTEL");
        assertThat(fromAirtel.getFirst().getSentNetwork()).isEqualTo("AIRTEL");
    }

    @Test
    void withRoutingAndNoPhoneOfTheNetworkOnlineAnotherOneSends() {
        routing.save(Organization.DEFAULT_ID, new RoutingService.Settings(true, RoutingService.Fallback.ANY));
        // No phone is connected in this test: the Airtel one is not online, the Vodacom phone takes the message.
        SmsMessage toAirtel = queue(agency, "+243971234567");
        assertThat(service.claim(vodacom, 10)).extracting(SmsMessage::getId).containsExactly(toAirtel.getId());
    }

    @Test
    void routingStaysWithinThePhonesOfTheKey() {
        routing.save(Organization.DEFAULT_ID, new RoutingService.Settings(true, RoutingService.Fallback.WAIT));
        keyDevices.set(Organization.DEFAULT_ID, pharmacy.id(), List.of(vodacom));
        // The key has no Airtel phone: waiting would be forever, so its own phone sends.
        SmsMessage toAirtel = queue(pharmacy, "+243971234567");
        assertThat(service.claim(airtel, 10)).isEmpty();
        assertThat(service.claim(vodacom, 10)).extracting(SmsMessage::getId).containsExactly(toAirtel.getId());
    }
}
