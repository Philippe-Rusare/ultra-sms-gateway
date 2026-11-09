package com.ultracoresoft.ultrasmsgateway.message;

/** {@code created} is false when an earlier message with the same client reference was returned instead. */
public record SubmitResult(SmsMessage message, boolean created) {
}
