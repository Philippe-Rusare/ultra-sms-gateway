package com.ultracoresoft.ultrasmsgateway.api;

import com.ultracoresoft.ultrasmsgateway.apikey.ApiKeyPrincipal;
import com.ultracoresoft.ultrasmsgateway.message.MessageDispatcher;
import com.ultracoresoft.ultrasmsgateway.message.MessageService;
import com.ultracoresoft.ultrasmsgateway.message.SubmitResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "Messages")
@RestController
@RequestMapping("/v1/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messages;
    private final MessageDispatcher dispatcher;

    @Operation(summary = "Queue an SMS",
            description = "The message is stored, then pushed to a connected device. "
                    + "If no device is connected it waits in the queue until one connects or it expires.")
    @ApiResponse(responseCode = "202", description = "Queued")
    @ApiResponse(responseCode = "200", description = "Already queued earlier with the same clientReference")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key")
    @PostMapping
    public ResponseEntity<MessageResponse> submit(@AuthenticationPrincipal ApiKeyPrincipal client,
                                                  @Valid @RequestBody SubmitMessageRequest request) {
        SubmitResult result = messages.submit(client, request.toNewMessage());
        if (result.created()) {
            dispatcher.dispatchSoon();
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(result.message().getId())
                .toUri();
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .location(location)
                .body(MessageResponse.from(result.message()));
    }

    @Operation(summary = "Get the status of a message queued with this API key")
    @ApiResponse(responseCode = "200", description = "Found")
    @ApiResponse(responseCode = "404", description = "Unknown, or queued with another API key")
    @GetMapping("/{id}")
    public MessageResponse get(@AuthenticationPrincipal ApiKeyPrincipal client, @PathVariable UUID id) {
        return MessageResponse.from(messages.get(client.id(), id));
    }
}
