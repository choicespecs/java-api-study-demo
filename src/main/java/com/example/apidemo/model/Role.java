package com.example.apidemo.model;

/**
 * CONCEPT: Role-Based Access Control (RBAC)
 *
 * Roles represent coarse-grained permissions. Spring Security requires the
 * "ROLE_" prefix when using hasRole() checks (it strips the prefix internally).
 * When using hasAuthority(), include the full string (e.g., "ROLE_ADMIN").
 *
 * Hierarchy used in this demo:
 *   ADMIN > USER > VIEWER
 */
public enum Role {
    ROLE_ADMIN,
    ROLE_USER,
    ROLE_VIEWER
}
