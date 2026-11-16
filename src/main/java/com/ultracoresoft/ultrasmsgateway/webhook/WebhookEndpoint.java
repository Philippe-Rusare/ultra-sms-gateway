package com.ultracoresoft.ultrasmsgateway.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An address of the client's application that receives status changes of its messages. */
@Entity
@Table(name = "webhook_endpoint")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(length = 100)
    private String description;

    /** Event types separated by commas. */
    @Column(nullable = false, length = 200)
    private String events;

    @Column(nullable = false, length = 64)
    private String secret;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastSuccessAt;

    private Instant lastFailureAt;

    public List<WebhookEvent> eventList() {
        return Arrays.stream(events.split(",")).map(WebhookEvent::ofType).flatMap(Optional::stream).toList();
    }

    public boolean listensTo(WebhookEvent event) {
        return eventList().contains(event);
    }

    public void setEventList(Collection<WebhookEvent> list) {
        this.events = list.stream().map(WebhookEvent::type).collect(Collectors.joining(","));
    }

    /** Start of the secret, safe to display. */
    public String secretPrefix() {
        return secret.substring(0, Math.min(12, secret.length()));
    }
}
