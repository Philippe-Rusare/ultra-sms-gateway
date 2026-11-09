package com.ultracoresoft.ultrasmsgateway.api;

import com.ultracoresoft.ultrasmsgateway.message.NewMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubmitMessageRequest(
        @Schema(description = "Recipient in E.164 format.", example = "+243812345678")
        @NotBlank
        @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "must be an E.164 number, e.g. +243812345678")
        String to,

        @Schema(description = "Text of the SMS. Long texts are sent as several parts.", example = "Your code is 482913")
        @NotBlank
        @Size(max = 918)
        String body,

        @Schema(description = "Seconds the message may wait in the queue before it expires. "
                + "Defaults to gateway.messages.default-ttl.", example = "300")
        @Min(30)
        Integer ttlSeconds,

        @Schema(description = "Your own identifier. Sending the same reference again returns the first message "
                + "instead of queuing a second one.", example = "otp-7f3a")
        @Size(max = 100)
        String clientReference) {

    NewMessage toNewMessage() {
        return new NewMessage(to, body, ttlSeconds, clientReference);
    }
}
