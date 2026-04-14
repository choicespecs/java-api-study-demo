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
 * CONCEPT: API Key Authentication
 *
 * Demo API Keys (seeded by DataInitializer):
 *   demo-api-key-admin-12345  — ROLE_ADMIN
 *   demo-api-key-user-12345   — ROLE_USER
 *
 * To test:
 *   curl http://localhost:8080/api/apikey/data \
 *     -H "X-API-Key: demo-api-key-user-12345"
 *
 * In Swagger UI: click "Authorize" → API Key → enter key value.
 */
@RestController
@RequestMapping("/api/apikey")
@Tag(name = "04. API Key Auth", description = "API Key authentication demo")
public class ApiKeyController {

    @GetMapping("/data")
    @SecurityRequirement(name = "apiKeyAuth")
    @Operation(
            summary = "Get data with API key",
            description = "Send X-API-Key header. Keys: demo-api-key-user-12345 (USER) or demo-api-key-admin-12345 (ADMIN)"
    )
    public ResponseEntity<Map<String, Object>> getData(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Authenticated via API Key!",
                "owner", auth.getName(),
                "role", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
                "how_it_works", Map.of(
                        "step1", "Client sent header: X-API-Key: <key>",
                        "step2", "ApiKeyAuthFilter extracted the key",
                        "step3", "ApiKeyService looked up the key in the database",
                        "step4", "Verified key is active and not expired",
                        "step5", "SecurityContext populated with key owner identity"
                ),
                "vs_jwt", Map.of(
                        "api_key", "DB lookup on each request; instantly revocable",
                        "jwt", "Signature check only; cannot revoke before expiry"
                )
        ));
    }

    @GetMapping("/admin")
    @SecurityRequirement(name = "apiKeyAuth")
    @Operation(
            summary = "Admin API key endpoint",
            description = "Requires ROLE_ADMIN. Use demo-api-key-admin-12345."
    )
    public ResponseEntity<Map<String, String>> adminData(Authentication auth) {
        // Role check via SecurityConfig: /api/apikey/** is authenticated only
        // For per-endpoint role checks, use @PreAuthorize or check auth here
        boolean isAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        if (!isAdmin) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "ROLE_ADMIN required. Your key has: " +
                            auth.getAuthorities().stream().map(a -> a.getAuthority()).toList().toString()));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Admin access granted via API key",
                "owner", auth.getName()
        ));
    }
}
