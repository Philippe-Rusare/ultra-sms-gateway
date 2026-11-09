package com.ultracoresoft.ultrasmsgateway;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyService;
import com.ultracoresoft.ultrasmsgateway.message.MessageEventRepository;
import com.ultracoresoft.ultrasmsgateway.message.SmsMessageRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Starts the whole application on a random port, with H2 and a controllable clock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestClockConfig.class)
public abstract class IntegrationTest {

    protected final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    protected int port;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected ApiKeyService apiKeys;

    @Autowired
    protected SmsMessageRepository messageRepository;

    @Autowired
    protected MessageEventRepository eventRepository;

    @BeforeEach
    void cleanQueue() {
        eventRepository.deleteAllInBatch();
        messageRepository.deleteAllInBatch();
    }

    protected HttpResponse<String> post(String path, String apiKey, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> get(String path, String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (apiKey != null) {
            request.header("X-Api-Key", apiKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    protected JsonNode parse(String body) {
        return json.readTree(body);
    }
}
