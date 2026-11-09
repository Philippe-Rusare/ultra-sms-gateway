package com.ultracoresoft.ultrasmsgateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ultracoresoft.ultrasmsgateway.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MessageApiTest extends IntegrationTest {

    private String apiKey;

    @BeforeEach
    void createKey() {
        apiKey = apiKeys.create("test-client").secret();
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        HttpResponse<String> response = post("/v1/messages", null, """
                {"to":"+243812345678","body":"Hello"}""");
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
    }

    @Test
    void rejectsUnknownApiKey() throws Exception {
        HttpResponse<String> response = post("/v1/messages", "usg_not-a-real-key", """
                {"to":"+243812345678","body":"Hello"}""");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsRevokedApiKey() throws Exception {
        var issued = apiKeys.create("revoked-client");
        apiKeys.revoke(issued.value().getId());
        HttpResponse<String> response = post("/v1/messages", issued.secret(), """
                {"to":"+243812345678","body":"Hello"}""");
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsInvalidNumber() throws Exception {
        HttpResponse<String> response = post("/v1/messages", apiKey, """
                {"to":"0812345678","body":"Hello"}""");
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(parse(response.body()).path("errors").path("to").asString()).contains("E.164");
    }

    @Test
    void rejectsTtlAboveMaximum() throws Exception {
        HttpResponse<String> response = post("/v1/messages", apiKey, """
                {"to":"+243812345678","body":"Hello","ttlSeconds":999999}""");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void queuesMessageWithoutReturningItsText() throws Exception {
        HttpResponse<String> response = post("/v1/messages", apiKey, """
                {"to":"+243812345678","body":"Your code is 482913"}""");
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).doesNotContain("482913");
        JsonNode created = parse(response.body());
        assertThat(created.path("status").asString()).isEqualTo("QUEUED");
        assertThat(response.headers().firstValue("Location"))
                .hasValueSatisfying(location -> assertThat(location).endsWith("/v1/messages/" + created.path("id").asString()));

        HttpResponse<String> fetched = get("/v1/messages/" + created.path("id").asString(), apiKey);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(parse(fetched.body()).path("to").asString()).isEqualTo("+243812345678");
    }

    @Test
    void sameClientReferenceReturnsTheFirstMessage() throws Exception {
        String request = """
                {"to":"+243812345678","body":"Hello","clientReference":"otp-1"}""";
        HttpResponse<String> first = post("/v1/messages", apiKey, request);
        HttpResponse<String> second = post("/v1/messages", apiKey, request);
        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(parse(second.body()).path("id").asString()).isEqualTo(parse(first.body()).path("id").asString());
        assertThat(messageRepository.count()).isEqualTo(1);
    }

    @Test
    void anotherClientCannotReadTheMessage() throws Exception {
        HttpResponse<String> created = post("/v1/messages", apiKey, """
                {"to":"+243812345678","body":"Hello"}""");
        String otherKey = apiKeys.create("other-client").secret();
        HttpResponse<String> fetched = get("/v1/messages/" + parse(created.body()).path("id").asString(), otherKey);
        assertThat(fetched.statusCode()).isEqualTo(404);
    }
}
