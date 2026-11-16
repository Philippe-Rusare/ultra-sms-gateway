package com.ultracoresoft.ultrasmsgateway.webhook;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** What a webhook reports. The {@code type} is the name sent in the payload and the {@code Usg-Event} header. */
public enum WebhookEvent {
    MESSAGE_SENT("message.sent"),
    MESSAGE_DELIVERED("message.delivered"),
    MESSAGE_FAILED("message.failed"),
    MESSAGE_EXPIRED("message.expired"),
    /** Sent on demand from the dashboard, to check an address; never subscribed to. */
    TEST("webhook.test");

    /** The events an address can subscribe to. */
    public static final List<WebhookEvent> SUBSCRIBABLE = List.of(MESSAGE_SENT, MESSAGE_DELIVERED, MESSAGE_FAILED, MESSAGE_EXPIRED);

    private final String type;

    WebhookEvent(String type) {
        this.type = type;
    }

    public String type() {
        return type;
    }

    public static Optional<WebhookEvent> ofType(String type) {
        return Arrays.stream(values()).filter(event -> event.type.equals(type)).findFirst();
    }
}
