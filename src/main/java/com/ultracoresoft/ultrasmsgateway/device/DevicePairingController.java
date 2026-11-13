package com.ultracoresoft.ultrasmsgateway.device;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Called by the Android app after it scanned the QR code of the dashboard. No authentication: the
 * one-time code is the credential. See docs/device-protocol.md, "Pairing".
 */
@Tag(name = "Devices")
@RestController
@RequiredArgsConstructor
class DevicePairingController {

    private final DevicePairingService pairings;

    record PairRequest(String code) {
    }

    /** {@code token} is shown once: the app stores it and uses it on {@code deviceUrl}. */
    record PairResponse(UUID deviceId, String name, String token, String deviceUrl) {
    }

    @Operation(summary = "Exchange a pairing code for a device token")
    @SecurityRequirements
    @PostMapping("/v1/device/pair")
    ResponseEntity<?> pair(@RequestBody PairRequest request) {
        return pairings.complete(request.code())
                .<ResponseEntity<?>>map(device -> ResponseEntity.ok(new PairResponse(
                        device.deviceId(), device.name(), device.token(), deviceUrl())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                                "Unknown, expired or already used pairing code.")));
    }

    static String deviceUrl() {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/v1/device/ws").toUriString().replaceFirst("^http", "ws");
    }
}
