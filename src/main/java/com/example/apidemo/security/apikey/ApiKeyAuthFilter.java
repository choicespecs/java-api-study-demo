package com.example.apidemo.security.apikey;

import com.example.apidemo.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * CONCEPT: API Key Authentication Filter
 *
 * Reads the X-API-Key header and validates it against the database.
 * If valid, populates the SecurityContext with the key owner's identity.
 *
 * API Key vs JWT comparison:
 *   API Key: opaque, server validates via DB, instantly revocable
 *   JWT:     self-contained, server validates via signature, not easily revocable
 *
 * NOTE: Not annotated with @Component — registered only in the API key
 *       SecurityFilterChain to avoid being applied to all requests.
 */
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        apiKeyService.validateApiKey(apiKey).ifPresentOrElse(
                key -> {
                    ApiKeyAuthToken authToken = new ApiKeyAuthToken(
                            key.getOwner(),
                            List.of(new SimpleGrantedAuthority(key.getRole().name()))
                    );
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("API key authenticated for owner: {}", key.getOwner());
                },
                () -> log.debug("Invalid or expired API key presented")
        );

        filterChain.doFilter(request, response);
    }
}
