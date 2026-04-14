package com.example.apidemo.security.jwt;

import com.example.apidemo.service.JwtService;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CONCEPT: JWT Bearer Token Authentication Filter
 *
 * Runs once per request (OncePerRequestFilter). Extracts the JWT from
 * the Authorization header and, if valid, populates the SecurityContext.
 *
 * Request flow:
 *   HTTP Request → JwtAuthFilter → (valid token?) → SecurityContext ← Controller
 *
 * The filter does NOT reject requests — it only authenticates if a valid
 * token is present. The security filter chain's authorizeHttpRequests()
 * rules then decide whether an unauthenticated request is permitted.
 *
 * NOTE: This class is NOT annotated with @Component to prevent Spring Boot
 * from auto-registering it as a global servlet filter. It is registered
 * only in the JWT SecurityFilterChain via SecurityConfig.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // JWT must arrive as: Authorization: Bearer <token>
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7); // Strip "Bearer " prefix

        try {
            final String username = jwtService.extractUsername(jwt);

            // Only authenticate if not already authenticated
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtService.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("JWT authenticated user: {}", username);
                }
            }
        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT: {}", e.getMessage());
            // Fall through — Spring Security will return 401 for protected endpoints
        } catch (MalformedJwtException | SignatureException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
        } catch (Exception e) {
            log.error("JWT filter error: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
