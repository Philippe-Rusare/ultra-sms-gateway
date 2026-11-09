package com.ultracoresoft.ultrasmsgateway.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import com.ultracoresoft.ultrasmsgateway.message.NewMessage;
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

    HttpClient browser;
    UUID messageId;

    @BeforeEach
    void signIn() throws Exception {
        UUID apiKeyId = apiKeys.create("dashboard-client").value().getId();
        UUID deviceId = devices.create("dashboard-phone").value().getId();
        messageId = messages.submit(apiKeyId, new NewMessage("+243812345678", "Your code is 111111", null, "ref-1"))
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
        assertThat(page("/")).contains("Waiting in queue", "dashboard-phone");
        assertThat(page("/messages")).contains("+243812345678", "SENT");
        assertThat(page("/messages?status=SENT&recipient=2438")).contains("+243812345678");
        assertThat(page("/messages/" + messageId)).contains("History", "erased after sending", "DISPATCHED");
        assertThat(page("/devices")).contains("/v1/device/ws", "dashboard-phone");
        assertThat(page("/api-keys")).contains("dashboard-client");
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
