package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CONCEPT: Role-Based Access Control (RBAC)
 *
 * RBAC assigns permissions to roles, not directly to users.
 * Users are assigned roles → roles have permissions → users inherit permissions.
 *
 * This demo shows TWO ways to enforce roles in Spring Security:
 *
 * 1. URL-level rules in SecurityConfig (coarse-grained):
 *    .requestMatchers("/api/rbac/admin/**").hasRole("ADMIN")
 *
 * 2. Method-level rules with @PreAuthorize (fine-grained):
 *    @PreAuthorize("hasRole('ADMIN') and #userId == authentication.name")
 *    This allows complex expressions combining roles + runtime data.
 *
 * Test users (login at POST /api/auth/login to get JWT):
 *   admin  / password → ROLE_ADMIN, ROLE_USER
 *   user   / password → ROLE_USER
 *   viewer / password → ROLE_VIEWER
 *
 * Role hierarchy used in this demo (enforced manually):
 *   ADMIN > USER > VIEWER
 *
 * Spring Security has a RoleHierarchy bean for automatic hierarchy support.
 */
@RestController
@RequestMapping("/api/rbac")
@Tag(name = "06. RBAC", description = "Role-Based Access Control demo — get JWT first from /api/auth/login")
public class RbacController {

    // ── URL-LEVEL RBAC (defined in SecurityConfig) ─────────────────

    @GetMapping("/viewer")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Viewer endpoint",
            description = "Accessible by VIEWER, USER, and ADMIN. URL rule: hasAnyRole('VIEWER','USER','ADMIN')"
    )
    public ResponseEntity<Map<String, Object>> viewerEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Viewer access granted",
                "username", auth.getName(),
                "roles", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList(),
                "access_rule", "hasAnyRole('VIEWER','USER','ADMIN') in SecurityConfig"
        ));
    }

    @GetMapping("/user")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "User endpoint",
            description = "Accessible by USER and ADMIN. URL rule: hasAnyRole('USER','ADMIN'). " +
                          "Try viewer/password → 403 Forbidden"
    )
    public ResponseEntity<Map<String, Object>> userEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "User access granted",
                "username", auth.getName(),
                "roles", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList()
        ));
    }

    @GetMapping("/admin")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Admin endpoint",
            description = "Accessible by ADMIN only. URL rule: hasRole('ADMIN'). " +
                          "Try user/password → 403 Forbidden"
    )
    public ResponseEntity<Map<String, Object>> adminEndpoint(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Admin access granted",
                "username", auth.getName(),
                "roles", auth.getAuthorities().stream().map(a -> a.getAuthority()).toList()
        ));
    }

    // ── METHOD-LEVEL RBAC with @PreAuthorize ────────────────────────

    /**
     * @PreAuthorize with SpEL expression.
     * Evaluated BEFORE the method is called.
     * Allows complex logic: combine roles, check ownership, etc.
     *
     * Note: @EnableMethodSecurity must be set in SecurityConfig.
     */
    @GetMapping("/method-security/admin-only")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Method-level admin check",
            description = "@PreAuthorize('hasRole(\"ADMIN\")') on the method — " +
                          "Spring AOP intercepts the call and checks the role before execution"
    )
    public ResponseEntity<Map<String, String>> methodLevelAdmin(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Passed @PreAuthorize('hasRole(\"ADMIN\")')",
                "username", auth.getName(),
                "how", "@EnableMethodSecurity + Spring AOP intercept this method before it runs"
        ));
    }

    /**
     * Complex @PreAuthorize: combines role check with ownership.
     *
     * The expression "#userId == authentication.name" ensures users can
     * only access their OWN data even if they pass the role check.
     * This is attribute-based access control (ABAC) layered on RBAC.
     */
    @GetMapping("/method-security/my-data/{userId}")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("hasRole('USER') and #userId == authentication.name")
    @Operation(
            summary = "Ownership check",
            description = "Requires USER role AND the userId path variable must match the logged-in username. " +
                          "Demonstrates combining RBAC with ownership (ABAC pattern)."
    )
    public ResponseEntity<Map<String, String>> myData(
            @PathVariable String userId,
            Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "message", "Data for " + userId,
                "requestedBy", auth.getName(),
                "expression", "hasRole('USER') and #userId == authentication.name",
                "note", "Other users (even admins, unless you add hasRole('ADMIN') OR ...) cannot see this"
        ));
    }

    /**
     * @PostAuthorize: runs AFTER the method, checks the return value.
     * Useful for filtering based on returned data (e.g., data belongs to user).
     * Less common than @PreAuthorize.
     */
    @GetMapping("/method-security/post-authorize")
    @SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "@PostAuthorize demo concept",
            description = "Demonstrates that method-level security can also run AFTER the method " +
                          "to validate the return value. See @PostAuthorize in Spring docs."
    )
    public ResponseEntity<Map<String, String>> postAuthorizeDemo(Authentication auth) {
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "concept", "@PostAuthorize('returnObject.body.username == authentication.name') " +
                           "would validate the returned data belongs to the requester",
                "when_to_use", "When you cannot determine access rights until after loading the data"
        ));
    }
}
