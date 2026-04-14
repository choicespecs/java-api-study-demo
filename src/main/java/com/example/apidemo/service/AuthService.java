package com.example.apidemo.service;

import com.example.apidemo.dto.LoginRequest;
import com.example.apidemo.dto.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    /**
     * Authenticate credentials and issue JWT tokens.
     *
     * Flow:
     *   credentials → AuthenticationManager → UserDetailsService.loadUserByUsername()
     *   → BCrypt.matches(rawPassword, hashedPassword) → success → issue tokens
     */
    public TokenResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );
        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        return buildTokenResponse(userDetails);
    }

    /**
     * Issue a new access token from a valid refresh token.
     *
     * SECURITY: In production:
     *   1. Verify the token_type claim is "refresh", not "access"
     *   2. Check the refresh token against a server-side store (DB / Redis)
     *      so you can revoke individual tokens
     *   3. Implement refresh token rotation (issue a new refresh token each time)
     */
    public TokenResponse refreshToken(String refreshToken) {
        String username = jwtService.extractUsername(refreshToken);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);

        if (!jwtService.isTokenValid(refreshToken, userDetails)) {
            throw new IllegalArgumentException("Invalid or expired refresh token");
        }
        return buildTokenResponse(userDetails);
    }

    private TokenResponse buildTokenResponse(UserDetails userDetails) {
        return TokenResponse.builder()
                .accessToken(jwtService.generateAccessToken(userDetails))
                .refreshToken(jwtService.generateRefreshToken(userDetails))
                .tokenType("Bearer")
                .expiresIn(900) // 15 minutes
                .roles(userDetails.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .toArray(String[]::new))
                .build();
    }
}
