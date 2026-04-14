package com.example.apidemo.service;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CONCEPT: JSON Web Tokens (JWT)
 *
 * A JWT has three Base64URL-encoded parts separated by dots:
 *   HEADER.PAYLOAD.SIGNATURE
 *
 * Header:  algorithm + token type  →  {"alg":"HS256","typ":"JWT"}
 * Payload: claims (data)           →  {"sub":"admin","roles":[...],"exp":...}
 * Signature: HMAC(header + "." + payload, secret)
 *
 * IMPORTANT: The payload is NOT encrypted — it is only signed.
 *            Anyone can decode it. Never put sensitive data in a JWT.
 *
 * WHY JWT?
 *   - Stateless: the server doesn't need a session store
 *   - Self-contained: the server can verify without a DB lookup
 *   - Portable: works across services/languages
 *
 * TRADEOFFS:
 *   - Cannot be revoked before expiry without a token blocklist
 *   - If the signing key leaks, all tokens are compromised
 *   - Keep access tokens SHORT-LIVED (≤15 min)
 */
@Slf4j
@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    // ----------------------------------------------------------------
    // Token generation
    // ----------------------------------------------------------------

    public String generateAccessToken(UserDetails userDetails) {
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("token_type", "access");
        return buildToken(claims, userDetails.getUsername(), jwtExpirationMs);
    }

    /**
     * Refresh tokens are long-lived and contain minimal data.
     * They are only used to obtain new access tokens, never to access resources.
     * SECURITY: In production, store refresh tokens in the DB so you can revoke them.
     */
    public String generateRefreshToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("token_type", "refresh");
        return buildToken(claims, userDetails.getUsername(), refreshExpirationMs);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(getSigningKey())  // Signs with HMAC-SHA256
                .compact();
    }

    // ----------------------------------------------------------------
    // Token validation & extraction
    // ----------------------------------------------------------------

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return false;
        }
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(extractAllClaims(token));
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Educational: decode the JWT payload (claims) for display.
     * Do NOT expose this in production endpoints.
     */
    public Map<String, Object> decodePayload(String token) {
        Claims claims = extractAllClaims(token);
        return new HashMap<>(claims);
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
