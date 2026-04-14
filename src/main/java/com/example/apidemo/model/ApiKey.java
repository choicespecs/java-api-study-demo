package com.example.apidemo.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * CONCEPT: API Key Authentication
 *
 * API keys are opaque tokens (no encoded data) sent in a request header.
 * They differ from JWTs in that:
 *   - The server must look up the key in a database on every request
 *   - They can be instantly revoked (set active=false)
 *   - They carry no self-contained claims — the DB is the source of truth
 *
 * SECURITY NOTES:
 *   - In production, store only a HASH of the key (like a password).
 *     Show the plain key once on creation, then never again.
 *   - Enforce expiry (expiresAt) and rotate keys regularly.
 *   - Rate-limit per key independently.
 */
@Entity
@Table(name = "api_keys")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String keyValue;

    @Column(nullable = false)
    private String owner;

    private String description;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Role role = Role.ROLE_USER;

    @Builder.Default
    private boolean active = true;

    private Instant createdAt;
    private Instant expiresAt;   // null = never expires

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }
}
