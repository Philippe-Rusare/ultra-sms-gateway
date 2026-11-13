package com.ultracoresoft.ultrasmsgateway.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKey;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyRepository;
import com.ultracoresoft.ultrasmsgateway.common.Issued;
import com.ultracoresoft.ultrasmsgateway.device.Device;
import com.ultracoresoft.ultrasmsgateway.device.DeviceRepository;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.message.NewMessage;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessage;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Data of one organization must never be visible to, or sent by, another one. */
class OrganizationIsolationTest extends IntegrationTest {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    MessageService messages;

    @Autowired
    DeviceService devices;

    @Autowired
    DeviceRepository deviceRepository;

    @Autowired
    ApiKeyRepository apiKeyRepository;

    UUID acme;
    UUID globex;
    Issued<ApiKey> acmeKey;
    Issued<ApiKey> globexKey;
    UUID acmePhone;
    UUID globexPhone;

    @BeforeEach
    void setUp() {
        acme = organizations.save(new Organization(UUID.randomUUID(), "Acme", clock.instant())).getId();
        globex = organizations.save(new Organization(UUID.randomUUID(), "Globex", clock.instant())).getId();
        acmeKey = apiKeys.create(acme, "acme-app");
        globexKey = apiKeys.create(globex, "globex-app");
        acmePhone = devices.create(acme, "acme-phone").value().getId();
        globexPhone = devices.create(globex, "globex-phone").value().getId();
    }

    private SmsMessage queueFor(Issued<ApiKey> key) {
        ApiKey apiKey = key.value();
        return messages.submit(new ApiKeyPrincipal(apiKey.getId(), apiKey.getOrganizationId(), apiKey.getName()),
                new NewMessage("+243812345678", "Your code is 123456", null, null)).message();
    }

    @Test
    void messageTakesTheOrganizationOfItsApiKey() {
        assertThat(queueFor(acmeKey).getOrganizationId()).isEqualTo(acme);
    }

    @Test
    void phoneOnlyReceivesMessagesOfItsOwnOrganization() {
        SmsMessage acmeMessage = queueFor(acmeKey);

        assertThat(messages.claim(globexPhone, 10)).isEmpty();
        assertThat(messageRepository.findById(acmeMessage.getId()).orElseThrow().getStatus()).isEqualTo(MessageStatus.QUEUED);

        assertThat(messages.claim(acmePhone, 10)).extracting(SmsMessage::getId).containsExactly(acmeMessage.getId());
    }

    @Test
    void eachPhoneGetsItsOwnOrganizationQueue() {
        SmsMessage acmeMessage = queueFor(acmeKey);
        SmsMessage globexMessage = queueFor(globexKey);

        assertThat(messages.claim(globexPhone, 10)).extracting(SmsMessage::getId).containsExactly(globexMessage.getId());
        assertThat(messages.claim(acmePhone, 10)).extracting(SmsMessage::getId).containsExactly(acmeMessage.getId());
    }

    @Test
    void revokedPhoneReceivesNothing() {
        queueFor(acmeKey);
        devices.revoke(acme, acmePhone);

        assertThat(messages.claim(acmePhone, 10)).isEmpty();
    }

    @Test
    void reportFromAnotherOrganizationsPhoneIsIgnored() {
        SmsMessage acmeMessage = queueFor(acmeKey);
        messages.claim(acmePhone, 1);

        messages.onSent(globexPhone, acmeMessage.getId());

        assertThat(messageRepository.findById(acmeMessage.getId()).orElseThrow().getStatus()).isEqualTo(MessageStatus.DISPATCHED);
    }

    @Test
    void apiKeyCannotReadAnotherOrganizationsMessage() throws Exception {
        SmsMessage acmeMessage = queueFor(acmeKey);

        HttpResponse<String> response = get("/v1/messages/" + acmeMessage.getId(), globexKey.secret());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(get("/v1/messages/" + acmeMessage.getId(), acmeKey.secret()).statusCode()).isEqualTo(200);
    }

    @Test
    void listsOnlyShowTheOrganizationsOwnKeysAndPhones() {
        assertThat(apiKeys.list(acme)).extracting(ApiKey::getName).containsExactly("acme-app");
        assertThat(devices.list(globex)).extracting(Device::getName).containsExactly("globex-phone");
    }

    @Test
    void anOrganizationCannotRevokeAnothersKeyOrPhone() {
        apiKeys.revoke(globex, acmeKey.value().getId());
        devices.revoke(globex, acmePhone);

        assertThat(apiKeyRepository.findById(acmeKey.value().getId()).orElseThrow().isActive()).isTrue();
        assertThat(deviceRepository.findById(acmePhone).orElseThrow().isActive()).isTrue();
    }

    @Test
    void existingDataBelongsToTheDefaultOrganization() {
        assertThat(organizations.findById(Organization.DEFAULT_ID)).isPresent();
    }
}
