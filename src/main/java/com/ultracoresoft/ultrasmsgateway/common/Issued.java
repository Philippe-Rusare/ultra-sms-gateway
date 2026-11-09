package com.ultracoresoft.ultrasmsgateway.common;

/** A newly created credential together with its secret, which is only available at creation time. */
public record Issued<T>(T value, String secret) {
}
