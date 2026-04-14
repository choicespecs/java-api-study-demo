package com.example.apidemo.config;

import com.example.apidemo.security.UserDetailsServiceImpl;
import com.example.apidemo.security.apikey.ApiKeyAuthFilter;
import com.example.apidemo.security.jwt.JwtAuthFilter;
import com.example.apidemo.service.ApiKeyService;
import com.example.apidemo.service.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CONCEPT: Multiple Security Filter Chains
 *
 * Spring Security supports multiple SecurityFilterChain beans, each matching
 * different URL patterns. Lower @Order = higher priority.
 *
 * This demo uses 6 chains to illustrate 4 authentication mechanisms:
 *
 *   @Order(1)  — OAuth2 Authorization Server   [AuthorizationServerConfig]
 *   @Order(2)  — Form login (for OAuth2 consent flow)
 *   @Order(3)  — HTTP Basic Auth  → /api/basic/**
 *   @Order(4)  — API Key Auth     → /api/apikey/**
 *   @Order(5)  — JWT Bearer Auth  → /api/jwt/**, /api/rbac/**
 *   @Order(6)  — OAuth2 Resource  → /api/oauth/**
 *   @Order(10) — Public / Default → everything else
 *
 * KEY CONCEPTS:
 *   - STATELESS sessions: no server-side session, no cookies (for API chains)
 *   - CSRF disabled: not needed for stateless REST APIs (CSRF exploits cookies)
 *   - Each chain is independent — a request matches only the first chain it fits
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enables @PreAuthorize, @PostAuthorize, @Secured on methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserDetailsServiceImpl userDetailsService;
    private final JwtService jwtService;
    private final ApiKeyService apiKeyService;

    // ----------------------------------------------------------------
    // Shared beans
    // ----------------------------------------------------------------

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder supports multiple encoding schemes via prefix:
        //   {bcrypt}$2a$... — used for user passwords (default encoding)
        //   {noop}secret   — plain text, used by OAuth2 client secrets in this demo
        //
        // BCryptPasswordEncoder alone would NOT understand the {noop} prefix and
        // would reject OAuth2 client_credentials requests with "invalid_client".
        //
        // In production: use {bcrypt} for everything (no {noop}).
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // Filters created as beans (without @Component) to control registration scope
    @Bean
    public JwtAuthFilter jwtAuthFilter() {
        return new JwtAuthFilter(jwtService, userDetailsService);
    }

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter() {
        return new ApiKeyAuthFilter(apiKeyService);
    }

    // ----------------------------------------------------------------
    // CHAIN 2: Form Login — supports OAuth2 Authorization Code flow
    // The AS redirects browsers to /login; this chain handles that page.
    // Uses sessions (not stateless) so Spring Security can save the
    // original request and redirect back after login.
    // ----------------------------------------------------------------
    @Bean
    @Order(2)
    public SecurityFilterChain formLoginChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/login", "/logout")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .formLogin(Customizer.withDefaults()) // Default /login page provided by Spring Security
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    // ----------------------------------------------------------------
    // CHAIN 3: HTTP Basic Authentication
    //
    // CONCEPT: HTTP Basic Auth
    //   - Client sends: Authorization: Basic base64(username:password)
    //   - Server decodes and validates on EVERY request
    //   - Simple but:
    //     ✗ Credentials in every request (use HTTPS always!)
    //     ✗ No expiry — only way to "logout" is to change password
    //     ✓ Simple to implement and test
    //     ✓ Good for server-to-server with mutual trust
    // ----------------------------------------------------------------
    @Bean
    @Order(3)
    public SecurityFilterChain basicAuthChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/basic/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/basic/public").permitAll()
                        .requestMatchers("/api/basic/admin").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName("API Study Demo"))
                .authenticationProvider(authenticationProvider());
        return http.build();
    }

    // ----------------------------------------------------------------
    // CHAIN 4: API Key Authentication
    //
    // CONCEPT: API Keys
    //   - Client sends: X-API-Key: <key>
    //   - Server looks up key in DB on every request
    //   - Benefits vs JWT:
    //     ✓ Instantly revocable (flip active=false in DB)
    //     ✓ No expiry management on client side
    //     ✗ Requires DB lookup per request (add caching in prod)
    //     ✗ No user identity embedded — only the key owner
    // ----------------------------------------------------------------
    @Bean
    @Order(4)
    public SecurityFilterChain apiKeyChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/apikey/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .addFilterBefore(apiKeyAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // ----------------------------------------------------------------
    // CHAIN 5: JWT Bearer Token Authentication
    //
    // CONCEPT: JWT (JSON Web Token)
    //   - Client sends: Authorization: Bearer <jwt>
    //   - Server validates signature — no DB lookup needed
    //   - Benefits vs Basic Auth:
    //     ✓ Stateless — no session storage needed
    //     ✓ Short-lived (15 min) — limits damage if stolen
    //     ✓ Can carry claims (roles, permissions)
    //   - Drawbacks:
    //     ✗ Cannot be revoked before expiry without a blocklist
    //     ✗ Payload is readable (not encrypted), so no secrets in tokens
    //
    // @EnableMethodSecurity allows @PreAuthorize on individual methods
    // for fine-grained control (demonstrated in RbacController).
    // ----------------------------------------------------------------
    @Bean
    @Order(5)
    public SecurityFilterChain jwtChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/jwt/**", "/api/rbac/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/jwt/public").permitAll()
                        .requestMatchers("/api/rbac/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/rbac/user/**").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/api/rbac/viewer/**").hasAnyRole("VIEWER", "USER", "ADMIN")
                        .anyRequest().authenticated())
                // Without this, missing/invalid tokens return 403 instead of 401
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(
                                new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                                        org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // ----------------------------------------------------------------
    // CHAIN 6: OAuth2 Resource Server
    //
    // CONCEPT: OAuth2 Resource Server
    //   - Validates Bearer tokens issued by our Authorization Server
    //   - Uses the JWK Set (/oauth2/jwks) to verify token signatures
    //   - The token contains scopes granted by the user, not roles
    //
    // SCOPE vs ROLE:
    //   Roles: user attributes (who the user IS — ADMIN, USER)
    //   Scopes: what the client is ALLOWED to do (read, write)
    //           on behalf of the user
    // ----------------------------------------------------------------
    @Bean
    @Order(6)
    public SecurityFilterChain oauthResourceChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/oauth/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/oauth/public", "/api/oauth/callback").permitAll()
                        .requestMatchers("/api/oauth/write").hasAuthority("SCOPE_write")
                        .anyRequest().hasAuthority("SCOPE_read"))
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> jwt.jwkSetUri("http://localhost:8080/oauth2/jwks")));
        return http.build();
    }

    // ----------------------------------------------------------------
    // CHAIN 10: Default — public endpoints, swagger, h2-console, demos
    // ----------------------------------------------------------------
    @Bean
    @Order(10)
    public SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/index.html",
                                "/css/**",
                                "/js/**",
                                "/api/public/**",
                                "/api/auth/**",
                                "/api/rate/**",
                                "/api/timeout/**",
                                "/api/hanging/**",
                                "/api/third-party/**",
                                "/api/partner/**",
                                "/api/products/**",
                                "/api/v1/**",
                                "/api/v2/**",
                                "/api/items/**",
                                "/api/errors/**",
                                "/h2-console/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/actuator/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated())
                .headers(h -> h.frameOptions(f -> f.sameOrigin())); // Allow H2 console iframes
        return http.build();
    }

    // ----------------------------------------------------------------
    // CORS Configuration
    //
    // CONCEPT: Cross-Origin Resource Sharing (CORS)
    //   Browsers block cross-origin requests by default.
    //   CORS headers tell the browser which origins are allowed.
    //
    //   COMMON MISTAKE: Using allowedOrigins("*") with allowCredentials(true)
    //   — browsers reject this combination. Be explicit about allowed origins
    //   when credentials (cookies, Authorization headers) are used.
    // ----------------------------------------------------------------
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Demo: explicit origins. In production, load from config/environment.
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",  // typical React dev server
                "http://localhost:8080"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L); // Cache preflight for 1 hour

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
