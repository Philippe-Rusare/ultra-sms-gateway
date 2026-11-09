package com.ultracoresoft.ultrasmsgateway.apikey;

import java.util.UUID;

/** The authenticated client application behind a request to the messages API. */
public record ApiKeyPrincipal(UUID id, String name) {
}
