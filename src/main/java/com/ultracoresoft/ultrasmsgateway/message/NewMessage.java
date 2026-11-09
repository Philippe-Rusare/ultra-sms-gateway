package com.ultracoresoft.ultrasmsgateway.message;

/** What a client asks to send, already validated by the API layer. */
public record NewMessage(String to, String body, Integer ttlSeconds, String clientReference) {
}
