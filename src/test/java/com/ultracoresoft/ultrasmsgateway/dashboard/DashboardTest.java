package com.ultracoresoft.ultrasmsgateway.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import com.ultracoresoft.ultrasmsgateway.message.NewMessage;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import com.ultracoresoft.ultrasmsgateway.organization.OrganizationRepository;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Signs in like a browser and renders every page, so template errors fail the build. */
class DashboardTest extends IntegrationTest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Autowired
    MessageService messages;

    @Autowired
    DeviceService devices;

    @Autowired
    OrganizationRepository organizations;

    HttpClient browser;
    UUID messageId;

    @BeforeEach
    void signIn() throws Exception {
        UUID apiKeyId = apiKeys.create(Organization.DEFAULT_ID, "dashboard-client").value().getId();
        UUID deviceId = devices.create(Organization.DEFAULT_ID, "dashboard-phone").value().getId();
        messageId = messages.submit(new ApiKeyPrincipal(apiKeyId, Organization.DEFAULT_ID, "dashboard-client"), new NewMessage("+243812345678", "Your code is 111111", null, "ref-1"))
                .message().getId();
        messages.claim(deviceId, 1);
        messages.onSent(deviceId, messageId);

        browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String form = "username=admin&password=" + URLEncoder.encode("test-admin-password", StandardCharsets.UTF_8)
                + "&_csrf=" + csrf("/login");
        HttpResponse<String> login = browser.send(HttpRequest.newBuilder(url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
    }

    @Test
    void everyPageRenders() throws Exception {
        // The overview reads as the phone app: the state band, the counters, the live activity (numbers masked), the phones.
        assertThat(page("/")).contains("No phone online", "Phones online", "Live activity", "+243 81 ••• 5678",
                "dashboard-phone", "Messages by outcome", "series-sent", "state-badge");
        assertThat(page("/messages")).contains("+243812345678", "Sent");
        assertThat(page("/messages?status=SENT&recipient=2438")).contains("+243812345678");
        assertThat(page("/messages/" + messageId)).contains("History", "erased after sending", "Dispatched");
        assertThat(page("/devices")).contains("/v1/device/ws", "dashboard-phone", "device-card", "battery");
        assertThat(page("/api-keys")).contains("dashboard-client");
    }

    @Test
    void anotherOrganizationsDataIsNotShown() throws Exception {
        UUID other = organizations.save(new Organization(UUID.randomUUID(), "Other", clock.instant())).getId();
        UUID otherKey = apiKeys.create(other, "other-org-client").value().getId();
        devices.create(other, "other-org-phone");
        UUID otherMessage = messages.submit(new ApiKeyPrincipal(otherKey, other, "other-org-client"),
                new NewMessage("+243999000111", "Hidden", null, null)).message().getId();

        assertThat(page("/messages")).doesNotContain("+243999000111");
        assertThat(page("/devices")).doesNotContain("other-org-phone");
        assertThat(page("/api-keys")).doesNotContain("other-org-client");
        assertThat(page("/")).doesNotContain("other-org-phone");
        HttpResponse<String> detail = browser.send(HttpRequest.newBuilder(url("/messages/" + otherMessage)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(detail.statusCode()).isEqualTo(404);
    }

    @Test
    void addingADeviceShowsAQrCodeAndFollowsThePairing() throws Exception {
        String form = "name=qr-phone&_csrf=" + csrf("/devices");
        HttpResponse<String> started = browser.send(HttpRequest.newBuilder(url("/devices/pair"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).isEqualTo(302);
        String pairingPath = URI.create(started.headers().firstValue("Location").orElseThrow()).getPath();

        String page = page(pairingPath);
        assertThat(page).contains("Add qr-phone", "<svg", "Scan the QR code", "Waiting for the phone");
        Matcher link = Pattern.compile("ultrasms://pair\\?server=([^&]+)&amp;code=(usgp_[A-Za-z0-9_-]+)").matcher(page);
        assertThat(link.find()).as("pairing link on the page").isTrue();
        assertThat(java.net.URLDecoder.decode(link.group(1), StandardCharsets.UTF_8)).isEqualTo("http://localhost:" + port);
        assertThat(page(pairingPath + "/status")).contains("PENDING");

        // The QR code is shown once: reopening the page does not show it again.
        assertThat(page(pairingPath)).contains("only shown once").doesNotContain("<svg");

        HttpResponse<String> paired = post("/v1/device/pair", null, "{\"code\":\"" + link.group(2) + "\"}");
        assertThat(paired.statusCode()).isEqualTo(200);
        assertThat(page(pairingPath + "/status")).contains("PAIRED");
        assertThat(page(pairingPath)).contains("The phone is paired");
        assertThat(page("/devices")).contains("qr-phone");
    }

    @Test
    void messageTextIsNeverShown() throws Exception {
        assertThat(page("/messages")).doesNotContain("111111");
        assertThat(page("/messages/" + messageId)).doesNotContain("111111");
    }

    @Test
    void createdApiKeyIsShownOnce() throws Exception {
        String form = "name=new-app&_csrf=" + csrf("/api-keys");
        HttpResponse<String> created = browser.send(HttpRequest.newBuilder(url("/api-keys"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(302);

        assertThat(page("/api-keys")).contains("API key created for new-app", "usg_");
        assertThat(page("/api-keys")).doesNotContain("API key created for new-app");
    }

    @Test
    void devicesAndKeysCanBeRenamedAndTheirListsArePaged() throws Exception {
        UUID keyId = apiKeys.create(Organization.DEFAULT_ID, "rename-me").value().getId();
        submit("/api-keys", "/api-keys/" + keyId + "/rename", "name=renamed-key");
        assertThat(page("/api-keys")).contains("renamed-key", "Name changed.").doesNotContain(">rename-me<");

        UUID deviceId = devices.create(Organization.DEFAULT_ID, "rename-phone").value().getId();
        submit("/devices", "/devices/" + deviceId + "/rename", "name=%20");
        assertThat(page("/devices")).contains("The name is required", "rename-phone");
        submit("/devices", "/devices/" + deviceId + "/rename", "name=renamed-phone");
        assertThat(page("/devices")).contains("renamed-phone");

        for (int i = 0; i < DashboardController.LIST_PAGE_SIZE; i++) {
            apiKeys.create(Organization.DEFAULT_ID, "paged-key-" + i);
        }
        assertThat(page("/api-keys")).contains("1–25 of", "rel=\"next\"", "?page=1", "Per page", "page=0&amp;size=50");
        assertThat(page("/api-keys?page=1")).contains("26–", "rel=\"prev\"");
        assertThat(page("/api-keys?size=50")).contains("1–", "aria-current=\"true\">50</span>", "size=100");
        assertThat(page("/api-keys?size=1000")).contains("1–25 of");
    }

    @Test
    void webhookPagesCreateAnEndpointAndShowItsSecretOnce() throws Exception {
        submit("/webhooks", "/webhooks", "url=" + URLEncoder.encode("https://example.com/hook", StandardCharsets.UTF_8)
                + "&description=Orders&events=message.delivered&events=message.failed");
        String list = page("/webhooks");
        assertThat(list).contains("https://example.com/hook", "message.delivered", "message.failed").doesNotContain("??");
        Matcher detail = Pattern.compile("href=\"(/webhooks/[0-9a-f-]{36})\"").matcher(list);
        assertThat(detail.find()).isTrue();
        assertThat(page(detail.group(1))).contains("Orders", "whsec_", "Send a test event", "Requests");
        submit(detail.group(1), detail.group(1) + "/test", "x=1");
        assertThat(page(detail.group(1))).contains("Test event queued", "webhook.test");
        submit("/webhooks", "/webhooks", "url=ftp%3A%2F%2Fbad&events=message.sent");
        assertThat(page("/webhooks")).contains("Enter a complete address");
    }

    @Test
    void pagesFollowTheLanguageOfTheBrowser() throws Exception {
        String french = browser.send(HttpRequest.newBuilder(url("/devices")).header("Accept-Language", "fr-FR,fr;q=0.9").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(french).contains("Téléphones", "Ajouter un téléphone", "Se déconnecter").doesNotContain("??");
        assertThat(page("/devices")).contains("Add a device", "Sign out").doesNotContain("??");
        String spanish = browser.send(HttpRequest.newBuilder(url("/")).header("Accept-Language", "es").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(spanish).contains("Live activity").doesNotContain("??");
    }

    private void submit(String formPage, String action, String body) throws Exception {
        HttpResponse<String> response = browser.send(HttpRequest.newBuilder(url(action))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body + "&_csrf=" + csrf(formPage))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(action).isEqualTo(302);
    }

    private String page(String path) throws Exception {
        HttpResponse<String> response = browser.send(HttpRequest.newBuilder(url(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    private String csrf(String path) throws Exception {
        Matcher matcher = CSRF.matcher(browser.send(HttpRequest.newBuilder(url(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body());
        assertThat(matcher.find()).as("CSRF token on " + path).isTrue();
        return URLEncoder.encode(matcher.group(1), StandardCharsets.UTF_8);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
