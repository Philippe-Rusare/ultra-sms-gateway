package com.ultracoresoft.ultrasmsgateway.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.config.GatewayProperties;
import com.ultracoresoft.ultrasmsgateway.device.DeviceService;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import com.ultracoresoft.ultrasmsgateway.message.NewMessage;
import com.ultracoresoft.ultrasmsgateway.organization.Organization;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Webhooks end to end: an event per status change, a signed request to a real HTTP server, retries. */
class WebhookTest extends IntegrationTest {

    record Received(Map<String, String> headers, String body) {
    }

    @Autowired
    WebhookService webhooks;

    @Autowired
    WebhookSender sender;

    @Autowired
    WebhookEndpointRepository endpoints;

    @Autowired
    WebhookDeliveryRepository deliveries;

    @Autowired
    MessageService messages;

    @Autowired
    DeviceService devices;

    HttpServer server;
    final List<Received> received = new CopyOnWriteArrayList<>();
    final AtomicInteger answer = new AtomicInteger(200);

    @BeforeEach
    void startReceiver() throws Exception {
        deliveries.deleteAllInBatch();
        endpoints.deleteAllInBatch();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.of(
                    "event", String.valueOf(exchange.getRequestHeaders().getFirst("Usg-Event")),
                    "delivery", String.valueOf(exchange.getRequestHeaders().getFirst("Usg-Delivery")),
                    "signature", String.valueOf(exchange.getRequestHeaders().getFirst("Usg-Signature"))), body));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopReceiver() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private WebhookEndpoint endpoint(List<WebhookEvent> events) {
        WebhookService.Result<com.ultracoresoft.ultrasmsgateway.common.Issued<WebhookEndpoint>> result =
                webhooks.create(Organization.DEFAULT_ID, hookUrl(), "test", events);
        assertThat(result.ok()).isTrue();
        return result.value().value();
    }

    /** Queues a message and has a device report it sent. Returns its id. */
    private UUID sentMessage(String reference) {
        UUID keyId = apiKeys.create(Organization.DEFAULT_ID, "webhook-client").value().getId();
        UUID deviceId = devices.create(Organization.DEFAULT_ID, "webhook-phone").value().getId();
        UUID id = messages.submit(new ApiKeyPrincipal(keyId, Organization.DEFAULT_ID, "webhook-client"),
                new NewMessage("+243812345678", "Your code is 908172", null, reference)).message().getId();
        messages.claim(deviceId, 1);
        messages.onSent(deviceId, id);
        messages.onDelivered(deviceId, id);
        return id;
    }

    @Test
    void aStatusChangeIsSentSignedToTheAddressesListeningToIt() {
        WebhookEndpoint all = endpoint(WebhookEvent.SUBSCRIBABLE);
        endpoint(List.of(WebhookEvent.MESSAGE_FAILED));

        UUID messageId = sentMessage("order-42");
        assertThat(deliveries.findAll()).hasSize(2).allMatch(delivery -> delivery.getEndpointId().equals(all.getId()));

        assertThat(sender.sendDue()).isEqualTo(2);
        assertThat(received).extracting(r -> r.headers().get("event")).containsExactly("message.sent", "message.delivered");
        Received delivered = received.get(1);
        JsonNode body = json.readTree(delivered.body());
        assertThat(body.get("type").asString()).isEqualTo("message.delivered");
        assertThat(body.get("id").asString()).isEqualTo(delivered.headers().get("delivery"));
        assertThat(body.get("data").get("messageId").asString()).isEqualTo(messageId.toString());
        assertThat(body.get("data").get("clientReference").asString()).isEqualTo("order-42");
        assertThat(body.get("data").get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.body()).doesNotContain("908172", "Your code");

        String signature = delivered.headers().get("signature");
        long timestamp = Long.parseLong(signature.substring(2, signature.indexOf(',')));
        assertThat(signature).isEqualTo(WebhookSender.signature(all.getSecret(), timestamp, delivered.body()));
        assertThat(deliveries.findAll()).allMatch(delivery -> delivery.getStatus() == WebhookDelivery.Status.SUCCEEDED);
        assertThat(endpoints.findById(all.getId()).orElseThrow().getLastSuccessAt()).isNotNull();
    }

    @Test
    void aFailedRequestIsTriedAgainLaterThenGivenUp() {
        endpoint(List.of(WebhookEvent.MESSAGE_SENT));
        answer.set(500);
        sentMessage("retry-1");

        assertThat(sender.sendDue()).isEqualTo(1);
        WebhookDelivery delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
        assertThat(delivery.getAttempts()).isEqualTo(1);
        assertThat(delivery.getLastStatusCode()).isEqualTo(500);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(clock.instant().plus(WebhookSender.BACKOFF.getFirst()));

        assertThat(sender.sendDue()).isZero();
        clock.advance(Duration.ofSeconds(31));
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(received).hasSize(2);

        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofHours(7));
            sender.sendDue();
        }
        delivery = deliveries.findAll().getFirst();
        assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.Status.FAILED);
        assertThat(delivery.getAttempts()).isEqualTo(8);

        answer.set(204);
        assertThat(webhooks.retry(Organization.DEFAULT_ID, delivery.getId())).isTrue();
        sender.sendDue();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(WebhookDelivery.Status.SUCCEEDED);
    }

    @Test
    void aPausedAddressReceivesNothingButATestEvent() {
        WebhookEndpoint endpoint = endpoint(WebhookEvent.SUBSCRIBABLE);
        webhooks.update(Organization.DEFAULT_ID, endpoint.getId(), hookUrl(), null, WebhookEvent.SUBSCRIBABLE, false);
        sentMessage("paused-1");
        assertThat(deliveries.findAll()).isEmpty();

        assertThat(webhooks.sendTest(Organization.DEFAULT_ID, endpoint.getId())).isTrue();
        sender.sendDue();
        assertThat(received).extracting(r -> r.headers().get("event")).containsExactly("webhook.test");
        assertThat(webhooks.sendTest(UUID.randomUUID(), endpoint.getId())).isFalse();
    }

    @Test
    void addressesAreChecked() {
        assertThat(webhooks.create(Organization.DEFAULT_ID, "not a url", null, WebhookEvent.SUBSCRIBABLE).problem())
                .isEqualTo(WebhookService.Problem.INVALID_URL);
        assertThat(webhooks.create(Organization.DEFAULT_ID, "ftp://example.com/x", null, WebhookEvent.SUBSCRIBABLE).problem())
                .isEqualTo(WebhookService.Problem.INVALID_URL);
        assertThat(webhooks.create(Organization.DEFAULT_ID, "https://example.com/x", null, List.of()).problem())
                .isEqualTo(WebhookService.Problem.NO_EVENT);
        assertThat(webhooks.create(Organization.DEFAULT_ID, "https://example.com/x", null, List.of(WebhookEvent.TEST)).problem())
                .isEqualTo(WebhookService.Problem.NO_EVENT);

        // A shared service refuses its own network; a self-hosted gateway (the default) allows it.
        WebhookAddresses shared = new WebhookAddresses(properties(false, true));
        assertThat(shared.checkForm("http://example.com/hook")).contains(WebhookAddresses.Problem.HTTPS_REQUIRED);
        for (String url : new String[] {"https://localhost/x", "https://127.0.0.1/x", "https://10.1.2.3/x", "https://192.168.1.10/x",
                "https://169.254.169.254/latest", "https://[::1]/x", "https://100.64.0.1/x", "https://db.internal/x"}) {
            assertThat(shared.checkForm(url)).as(url).contains(WebhookAddresses.Problem.PRIVATE_NETWORK);
        }
        assertThat(shared.checkForm("https://8.8.8.8/hook")).isEmpty();
        assertThat(shared.mayReach(URI.create("https://127.0.0.1/x"))).isFalse();
        assertThat(new WebhookAddresses(properties(true, false)).checkForm("http://localhost:8080/hook")).isEmpty();
    }

    private static GatewayProperties properties(boolean allowPrivate, boolean requireHttps) {
        return new GatewayProperties(null, null, null, null, new GatewayProperties.Webhooks(
                Duration.ofSeconds(5), Duration.ofSeconds(10), 8, Duration.ofDays(7), allowPrivate, requireHttps));
    }
}
