package com.ultracoresoft.ultrasmsgateway.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.message.MessageStatus;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

/** End to end: a fake Android device on a real WebSocket. */
class DeviceWebSocketTest extends IntegrationTest {

    @Autowired
    DeviceService devices;

    @Autowired
    DeviceRepository deviceRepository;

    String apiKey;
    String deviceToken;
    UUID deviceId;
    FakeDevice device;

    @BeforeEach
    void setUp() {
        apiKey = apiKeys.create(Organization.DEFAULT_ID, "ws-client").secret();
        var issued = devices.create(Organization.DEFAULT_ID, "ws-phone");
        deviceToken = issued.secret();
        deviceId = issued.value().getId();
    }

    @AfterEach
    void disconnect() throws Exception {
        if (device != null && device.session.isOpen()) {
            device.session.close();
        }
    }

    @Test
    void rejectsUnknownToken() {
        assertThatThrownBy(() -> FakeDevice.connect(port, "usgd_wrong")).isInstanceOf(Exception.class);
    }

    @Test
    void queuedMessageIsSentAsSoonAsTheDeviceConnects() throws Exception {
        String id = parse(post("/v1/messages", apiKey, """
                {"to":"+243812345678","body":"Your code is 654321"}""").body()).path("id").asString();

        device = FakeDevice.connect(port, deviceToken);
        assertThat(device.next().path("type").asString()).isEqualTo("hello");

        JsonNode send = device.next();
        assertThat(send.path("type").asString()).isEqualTo("send");
        assertThat(send.path("id").asString()).isEqualTo(id);
        assertThat(send.path("to").asString()).isEqualTo("+243812345678");
        assertThat(send.path("body").asString()).isEqualTo("Your code is 654321");

        device.send("{\"type\":\"ack\",\"id\":\"" + id + "\"}");
        device.send("{\"type\":\"sent\",\"id\":\"" + id + "\"}");
        device.send("{\"type\":\"delivered\",\"id\":\"" + id + "\"}");

        awaitStatus(id, MessageStatus.DELIVERED);
        assertThat(messageRepository.findById(UUID.fromString(id)).orElseThrow().getBody()).isNull();
    }

    @Test
    void messageQueuedWhileConnectedIsPushedRightAway() throws Exception {
        device = FakeDevice.connect(port, deviceToken);
        assertThat(device.next().path("type").asString()).isEqualTo("hello");

        String id = parse(post("/v1/messages", apiKey, """
                {"to":"+243812345678","body":"Hello"}""").body()).path("id").asString();

        assertThat(device.next().path("id").asString()).isEqualTo(id);
        device.send("{\"type\":\"failed\",\"id\":\"" + id + "\",\"reason\":\"RESULT_ERROR_NO_SERVICE\"}");

        awaitStatus(id, MessageStatus.FAILED);
        assertThat(get("/v1/messages/" + id, apiKey).body()).contains("RESULT_ERROR_NO_SERVICE");
    }

    @Test
    void heartbeatUpdatesTheDevice() throws Exception {
        device = FakeDevice.connect(port, deviceToken);
        device.next();
        device.send("""
                {"type":"heartbeat","battery":76,"charging":true,"signal":3,"operator":"Vodacom CD","network":"LTE","appVersion":"0.1.0"}""");

        long deadline = System.currentTimeMillis() + 5_000;
        Device stored;
        do {
            stored = deviceRepository.findById(deviceId).orElseThrow();
        } while (stored.getBatteryLevel() == null && System.currentTimeMillis() < deadline);
        assertThat(stored.getBatteryLevel()).isEqualTo(76);
        assertThat(stored.getOperatorName()).isEqualTo("Vodacom CD");
    }

    @Test
    void invalidFrameGetsAnError() throws Exception {
        device = FakeDevice.connect(port, deviceToken);
        device.next();
        device.send("not json");
        assertThat(device.next().path("code").asString()).isEqualTo("INVALID_FRAME");
    }

    private void awaitStatus(String id, MessageStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        MessageStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            status = messageRepository.findById(UUID.fromString(id)).orElseThrow().getStatus();
            if (status == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(status).isEqualTo(expected);
    }

    /** Minimal stand-in for the Android app. */
    static final class FakeDevice extends TextWebSocketHandler {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private WebSocketSession session;
        private tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

        static FakeDevice connect(int port, String token) throws Exception {
            FakeDevice device = new FakeDevice();
            WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
            headers.add("Authorization", "Bearer " + token);
            device.session = new StandardWebSocketClient()
                    .execute(device, headers, URI.create("ws://localhost:" + port + "/v1/device/ws"))
                    .get(5, TimeUnit.SECONDS);
            return device;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        JsonNode next() throws InterruptedException {
            String frame = received.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("frame from the gateway").isNotNull();
            return json.readTree(frame);
        }

        void send(String frame) throws Exception {
            session.sendMessage(new TextMessage(frame));
        }
    }
}
