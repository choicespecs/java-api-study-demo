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
 * CONCEPT: HTTP Basic Authentication
 *
 * Basic Auth sends credentials on every request as:
 *   Authorization: Basic base64("username:password")
 *
 * To test:
 *   curl -u admin:password http://localhost:8080/api/basic/protected
 *   curl -u admin:password http://localhost:8080/api/basic/admin
 *   curl -u user:password  http://localhost:8080/api/basic/admin  ← 403 Forbidden
 *
 * In Swagger UI: click "Authorize" → Basic Auth → enter credentials.
 */
@RestController
@RequestMapping("/api/basic")
@Tag(name = "02. Basic Auth", description = "HTTP Basic Authentication demo")
public class BasicAuthController {

    @GetMapping("/public")
    @Operation(summary = "Public endpoint", description = "No auth needed — baseline comparison")
    public ResponseEntity<Map<String, String>> publicEndpoint() {
        return ResponseEntity.ok(Map.of(
                "message", "This endpoint is public — no credentials required",
                "hint", "Try /api/basic/protected next"
        ));
    }

    @GetMapping("/protected")
    @SecurityRequirement(name = "basicAuth")
    @Operation(
            summary = "Protected endpoint",
            description = "Requires valid username + password via HTTP Basic Auth. " +
                          "Try: admin/password or user/password or viewer/password"
    )
    public ResponseEntity<Map<String, Object>> protectedEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "You are authenticated via HTTP Basic Auth!",
                "username", auth.getName(),
                "roles", auth.getAuthorities().stream()
                        .map(a -> a.getAuthority()).toList(),
                "how_it_works", "Your browser/client sent: Authorization: Basic base64(username:password)"
        ));
    }

    @GetMapping("/admin")
    @SecurityRequirement(name = "basicAuth")
    @Operation(
            summary = "Admin-only endpoint",
            description = "Requires ROLE_ADMIN. Try admin/password (works) vs user/password (403 Forbidden)."
    )
    public ResponseEntity<Map<String, String>> adminEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Admin access granted via Basic Auth",
                "username", auth.getName(),
                "note", "Spring Security checked hasRole('ADMIN') before allowing access"
        ));
    }
}
