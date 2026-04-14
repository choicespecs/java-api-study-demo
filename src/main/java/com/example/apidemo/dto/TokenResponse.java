package com.example.apidemo.dto;

import lombok.*;

/**
 * CONCEPT: Token Response format
 *
 * Mirrors the OAuth2 token response structure (RFC 6749 §5.1) so the format
 * feels familiar even for our custom JWT endpoint:
 *   access_token, token_type, expires_in, refresh_token
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private String tokenType;    // Always "Bearer"
    private long expiresIn;      // Seconds until access token expires
    private String[] roles;      // Convenience field — NOT part of OAuth2 spec
}
