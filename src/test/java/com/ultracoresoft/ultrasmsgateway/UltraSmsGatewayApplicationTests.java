package com.ultracoresoft.ultrasmsgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class UltraSmsGatewayApplicationTests extends IntegrationTest {

    @Test
    void healthIsPublic() throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    void dashboardRequiresLogin() throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/login"));
    }

    @Test
    void loginPageRenders() throws Exception {
        HttpResponse<String> response = get("/login", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Sign in");
    }
}
