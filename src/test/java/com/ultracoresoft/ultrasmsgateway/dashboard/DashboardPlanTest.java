package com.ultracoresoft.ultrasmsgateway.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.device.DeviceLimit;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** With a plan (DeviceLimit), the overview counts the phones online against the phones the plan allows. */
@Import(DashboardPlanTest.Plan.class)
class DashboardPlanTest extends IntegrationTest {

    /** Large, so that the phones other tests left in the shared database never reach it. */
    static final int PLAN_PHONES = 500;

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @TestConfiguration(proxyBeanMethods = false)
    static class Plan {

        @Bean
        DeviceLimit planLimit() {
            return new DeviceLimit() {
                @Override
                public OptionalInt maxDevices(UUID organizationId) {
                    return OptionalInt.of(PLAN_PHONES);
                }

                @Override
                public Optional<String> upgradePath() {
                    return Optional.of("/billing");
                }
            };
        }
    }

    @Autowired
    DeviceService devices;

    @Test
    void thePhonesOnlineAreCountedAgainstThePlan() throws Exception {
        devices.create(Organization.DEFAULT_ID, "plan-phone");

        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String form = "username=admin&password=" + URLEncoder.encode("test-admin-password", StandardCharsets.UTF_8)
                + "&_csrf=" + csrf(browser);
        browser.send(HttpRequest.newBuilder(url("/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());

        String overview = browser.send(HttpRequest.newBuilder(url("/")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(overview).contains("0 / " + PLAN_PHONES).doesNotContain("paired of", "included in your plan");
    }

    private String csrf(HttpClient browser) throws Exception {
        String body = browser.send(HttpRequest.newBuilder(url("/login")).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = CSRF.matcher(body);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
