package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CONCEPT: JWT Bearer Token Authentication
 *
 * Flow:
 *   1. POST /api/auth/login  → receive accessToken
 *   2. GET  /api/jwt/protected  with header:  Authorization: Bearer <accessToken>
 *   3. JwtAuthFilter validates the token, populates SecurityContext
 *   4. Controller receives the authenticated user via Authentication parameter
 *
 * To test:
 *   # Step 1: Get token
 *   curl -X POST http://localhost:8080/api/auth/login \
 *     -H "Content-Type: application/json" \
 *     -d '{"username":"user","password":"password"}'
 *
 *   # Step 2: Use token
 *   curl http://localhost:8080/api/jwt/protected \
 *     -H "Authorization: Bearer <token_from_step_1>"
 */
@RestController
@RequestMapping("/api/jwt")
@Tag(name = "03. JWT Auth", description = "JWT Bearer token demo — login first at /api/auth/login")
public class JwtController {

    @GetMapping("/public")
    @Operation(summary = "Public endpoint (JWT chain)", description = "Accessible without a token")
    public ResponseEntity<Map<String, String>> publicEndpoint() {
        return ResponseEntity.ok(Map.of(
                "message", "Public endpoint in the JWT security chain",
                "next_step", "POST /api/auth/login to get a token, then call /api/jwt/protected"
        ));
    }

    @GetMapping("/protected")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Protected endpoint",
            description = "Requires Bearer JWT. Get one from POST /api/auth/login."
    )
    public ResponseEntity<Map<String, Object>> protectedEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "JWT authentication successful!",
                "username", auth.getName(),
                "roles", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
                "how_it_works", Map.of(
                        "step1", "Client sent: Authorization: Bearer <jwt>",
                        "step2", "JwtAuthFilter extracted the token",
                        "step3", "JwtService validated the HMAC-SHA256 signature",
                        "step4", "Username extracted from 'sub' claim",
                        "step5", "SecurityContext populated — controller received auth"
                )
        ));
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Current user info", description = "Returns the authenticated user's details from the JWT")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "authorities", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
                "authenticated", auth.isAuthenticated(),
                "auth_type", auth.getClass().getSimpleName()
        ));
    }
}
