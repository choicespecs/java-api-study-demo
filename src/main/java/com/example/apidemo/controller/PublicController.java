package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Public endpoints — no authentication required.
 * Demonstrates the baseline before any security is applied.
 */
@RestController
@RequestMapping("/api/public")
@Tag(name = "00. Public", description = "No authentication required")
public class PublicController {

    @GetMapping("/info")
    @Operation(summary = "Application info", description = "Health check and demo reference — open to everyone")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "app", "REST API Study Demo",
                "timestamp", Instant.now(),
                "demos", Map.of(
                        "basic_auth",    "GET /api/basic/protected  (admin:password or user:password)",
                        "jwt_auth",      "POST /api/auth/login  →  GET /api/jwt/protected",
                        "api_key",       "GET /api/apikey/data  (X-API-Key: demo-api-key-user-12345)",
                        "oauth2",        "GET /.well-known/openid-configuration",
                        "rbac",          "GET /api/rbac/admin  (requires ROLE_ADMIN)",
                        "rate_limiting", "GET /api/rate/standard  (20 req/min)",
                        "timeout",       "GET /api/timeout/slow?delay=2",
                        "pagination",    "GET /api/products?page=0&size=5&sort=price,asc",
                        "versioning",    "GET /api/v1/items  vs  GET /api/v2/items",
                        "errors",        "GET /api/errors/demo"
                ),
                "swagger_ui", "http://localhost:8080/swagger-ui.html",
                "h2_console", "http://localhost:8080/h2-console  (sa / password)"
        ));
    }
}
