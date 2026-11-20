package com.ultracoresoft.ultrasmsgateway.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** A suspended organization: API in 403, phones closed and refused, all back once reactivated. */
class SuspensionTest extends IntegrationTest {

    @Autowired
    OrganizationRepository organizations;

    @Autowired
    OrganizationSuspensions suspensions;

    @Autowired
    DeviceService devices;

    private final HttpClient http = HttpClient.newHttpClient();

    private int submit(String key) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/messages"))
                        .header("X-Api-Key", key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"to\":\"+243812345678\",\"body\":\"Hello\"}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static final class Recorder extends TextWebSocketHandler {
        volatile CloseStatus closed;

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = status;
        }
    }

    private WebSocketSession connect(String token, Recorder recorder) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + token);
        return new StandardWebSocketClient().execute(recorder, headers, URI.create("ws://localhost:" + port + "/v1/device/ws"))
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    void aSuspendedOrganizationIsRefusedUntilReactivated() throws Exception {
        UUID id = UUID.randomUUID();
        organizations.save(new Organization(id, "Spam Promo", Instant.now()));
        String key = apiKeys.create(id, "site").secret();
        String token = devices.create(id, "phone").secret();
        assertThat(submit(key)).isEqualTo(202);
        Recorder recorder = new Recorder();
        WebSocketSession session = connect(token, recorder);

        suspensions.suspend(id, "Envoi de SMS non sollicités");
        assertThat(suspensions.isSuspended(id)).isTrue();
        assertThat(submit(key)).isEqualTo(403);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (session.isOpen() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        assertThat(recorder.closed).isNotNull();
        assertThat(recorder.closed.getCode()).isEqualTo(4003);
        assertThatThrownBy(() -> connect(token, new Recorder())).hasStackTraceContaining("403");

        suspensions.reactivate(id);
        assertThat(submit(key)).isEqualTo(202);
        connect(token, new Recorder()).close();
    }
}
