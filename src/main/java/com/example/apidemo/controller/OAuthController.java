package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CONCEPT: OAuth2 Resource Server
 *
 * These endpoints are protected by tokens issued from our Authorization Server.
 * Clients must obtain a token first, then send it as a Bearer token.
 *
 * ── CLIENT CREDENTIALS FLOW (machine-to-machine) ────────────────
 * curl -X POST http://localhost:8080/oauth2/token \
 *   -u machine-client:machine-secret \
 *   -d "grant_type=client_credentials&scope=read"
 *
 * Then use the access_token:
 * curl http://localhost:8080/api/oauth/data \
 *   -H "Authorization: Bearer <access_token>"
 *
 * ── AUTHORIZATION CODE FLOW (user grants access) ─────────────────
 * 1. Open browser: http://localhost:8080/oauth2/authorize
 *    ?client_id=web-client&response_type=code
 *    &redirect_uri=http://localhost:8080/api/oauth/callback&scope=openid+read
 * 2. Login with admin/password, approve consent screen
 * 3. Browser redirects to /api/oauth/callback?code=<AUTH_CODE>
 * 4. Exchange code: POST /oauth2/token
 *    -u web-client:web-secret
 *    -d "grant_type=authorization_code&code=<CODE>
 *        &redirect_uri=http://localhost:8080/api/oauth/callback"
 *
 * SCOPE vs ROLE:
 *   OAuth2 tokens carry "scopes" (what the client is allowed to do)
 *   not user roles (what the user is).
 *   Spring Security maps scopes to authorities as "SCOPE_read", "SCOPE_write".
 */
@RestController
@RequestMapping("/api/oauth")
@Tag(name = "05. OAuth2", description = "OAuth2 Resource Server demo — requires OAuth2 access token")
public class OAuthController {

    @GetMapping("/public")
    @Operation(summary = "Public OAuth endpoint", description = "No token required")
    public ResponseEntity<Map<String, Object>> publicInfo() {
        return ResponseEntity.ok(Map.of(
                "message", "OAuth2 Resource Server demo",
                "authorization_server", "http://localhost:8080",
                "discovery_endpoint", "http://localhost:8080/.well-known/openid-configuration",
                "jwks_endpoint", "http://localhost:8080/oauth2/jwks",
                "clients", Map.of(
                        "machine_client", Map.of(
                                "client_id", "machine-client",
                                "client_secret", "machine-secret",
                                "grant_type", "client_credentials",
                                "test_curl", "curl -X POST http://localhost:8080/oauth2/token " +
                                             "-u machine-client:machine-secret " +
                                             "-d 'grant_type=client_credentials&scope=read'"
                        ),
                        "web_client", Map.of(
                                "client_id", "web-client",
                                "client_secret", "web-secret",
                                "grant_type", "authorization_code",
                                "auth_url", "http://localhost:8080/oauth2/authorize" +
                                            "?client_id=web-client&response_type=code" +
                                            "&redirect_uri=http://localhost:8080/api/oauth/callback&scope=openid+read"
                        )
                )
        ));
    }

    /**
     * Protected by OAuth2 scope "read".
     * Token must have the "read" scope (set by SecurityConfig: hasAuthority("SCOPE_read")).
     */
    @GetMapping("/data")
    @Operation(
            summary = "Read-scoped data",
            description = "Requires OAuth2 access token with 'read' scope. " +
                          "Get token from POST /oauth2/token with client_credentials grant."
    )
    public ResponseEntity<Map<String, Object>> getData(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        return ResponseEntity.ok(Map.of(
                "message", "OAuth2 authenticated access to read-scoped data",
                "subject", jwt.getSubject(),
                "scopes", jwt.getClaim("scope"),
                "issuer", jwt.getIssuer(),
                "expires_at", jwt.getExpiresAt(),
                "all_claims", jwt.getClaims()
        ));
    }

    /**
     * Protected by OAuth2 scope "write".
     * Demonstrates scope-based access control (not just authentication).
     */
    @GetMapping("/write")
    @Operation(
            summary = "Write-scoped endpoint",
            description = "Requires 'write' scope. Demonstrates that scopes restrict what a client can do."
    )
    public ResponseEntity<Map<String, Object>> writeData(Authentication auth) {
        Jwt jwt = (Jwt) auth.getPrincipal();
        return ResponseEntity.ok(Map.of(
                "message", "Write access granted via OAuth2 scope",
                "scopes", jwt.getClaim("scope"),
                "note", "This endpoint requires scope=write in the token"
        ));
    }

    /**
     * Receives the authorization code redirect from the OAuth2 auth code flow.
     * In a real app, this would exchange the code for tokens server-side.
     */
    @GetMapping("/callback")
    @Operation(
            summary = "OAuth2 callback (Authorization Code)",
            description = "Receives the redirect after user grants consent. " +
                          "Shows the authorization code that must be exchanged for tokens."
    )
    public ResponseEntity<Map<String, Object>> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", error,
                    "message", "User denied access or another error occurred"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "message", "Authorization code received! Now exchange it for tokens.",
                "authorization_code", code != null ? code : "none",
                "state", state != null ? state : "none",
                "next_step", "POST /oauth2/token with this code",
                "example_curl", "curl -X POST http://localhost:8080/oauth2/token " +
                                "-u web-client:web-secret " +
                                "-d 'grant_type=authorization_code&code=" + code +
                                "&redirect_uri=http://localhost:8080/api/oauth/callback'"
        ));
    }
}
