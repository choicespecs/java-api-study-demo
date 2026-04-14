package com.example.apidemo.controller;

import com.example.apidemo.dto.LoginRequest;
import com.example.apidemo.dto.TokenResponse;
import com.example.apidemo.service.AuthService;
import com.example.apidemo.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CONCEPT: Token-Based Authentication
 *
 * This controller handles the "login" step of JWT authentication.
 * It is stateless: no session is created. Credentials are exchanged
 * for tokens that the client stores and sends with future requests.
 *
 * Demo credentials:
 *   admin   / password  → ROLE_ADMIN, ROLE_USER
 *   user    / password  → ROLE_USER
 *   viewer  / password  → ROLE_VIEWER
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "01. JWT Auth", description = "Login and token management for JWT demo")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    /**
     * Step 1 of JWT flow: exchange credentials for tokens.
     *
     * Returns:
     *   accessToken  — short-lived (15 min), use to call protected APIs
     *   refreshToken — long-lived (24 hr), use only to get new access tokens
     *
     * SECURITY: Send credentials only over HTTPS. Never log them.
     */
    @PostMapping("/login")
    @Operation(
            summary = "Login — get JWT tokens",
            description = "POST username+password → receive access token (15min) and refresh token (24hr). " +
                          "Then use: Authorization: Bearer <accessToken> on protected endpoints."
    )
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Step 2 (optional): silently refresh the access token when it expires.
     *
     * This avoids forcing the user to log in again every 15 minutes.
     * The refresh token acts as a long-lived credential stored by the client.
     *
     * SECURITY: Refresh tokens should be:
     *   - Stored securely (httpOnly cookie or secure storage, not localStorage)
     *   - Rotated on each use (server issues a new refresh token)
     *   - Revocable (store in DB, check on each use)
     */
    @PostMapping("/refresh")
    @Operation(
            summary = "Refresh access token",
            description = "Exchange a refresh token for a new access token. " +
                          "The refresh token is long-lived and avoids re-entering credentials."
    )
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "refreshToken field is required"));
        }

        try {
            TokenResponse response = authService.refreshToken(refreshToken);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid or expired refresh token"));
        }
    }

    /**
     * Educational endpoint: decode a JWT to see its contents.
     *
     * Demonstrates that JWTs are NOT encrypted — only signed.
     * The payload is Base64URL-encoded and readable by anyone.
     *
     * SECURITY: NEVER expose this endpoint in production.
     */
    @PostMapping("/decode")
    @Operation(
            summary = "Decode JWT payload (educational)",
            description = "Decodes a JWT to show its claims. " +
                          "Demonstrates that JWT payloads are readable (Base64, not encrypted). " +
                          "DO NOT implement this in production."
    )
    public ResponseEntity<?> decodeToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token field is required"));
        }

        try {
            Map<String, Object> payload = jwtService.decodePayload(token);

            // Split the token to show structure
            String[] parts = token.split("\\.");
            return ResponseEntity.ok(Map.of(
                    "warning", "JWT payloads are Base64URL-encoded, NOT encrypted. Anyone can decode them.",
                    "structure", "A JWT = header.payload.signature (3 parts separated by dots)",
                    "parts_count", parts.length,
                    "header_encoded", parts.length > 0 ? parts[0] : "N/A",
                    "payload_encoded", parts.length > 1 ? parts[1] : "N/A",
                    "signature_encoded", parts.length > 2 ? parts[2] : "N/A",
                    "decoded_payload", payload
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Cannot decode token: " + e.getMessage()));
        }
    }
}
