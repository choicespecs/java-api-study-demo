package com.example.apidemo.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.UUID;

/**
 * CONCEPT: OAuth2 Authorization Server
 *
 * This app acts as its own OAuth2 Authorization Server using Spring Authorization Server.
 * It exposes the standard OAuth2 endpoints:
 *
 *   GET  /oauth2/authorize          — start auth code flow (browser)
 *   POST /oauth2/token              — exchange code/credentials for tokens
 *   GET  /oauth2/jwks               — public keys for token verification
 *   POST /oauth2/revoke             — revoke a token
 *   POST /oauth2/introspect         — check if a token is active
 *   GET  /.well-known/openid-configuration — OIDC discovery document
 *
 * OAUTH2 GRANT TYPES DEMOED:
 *   1. client_credentials — machine-to-machine (no user)
 *   2. authorization_code — user grants permission to a client app
 *
 * TOKEN SIGNING:
 *   OAuth2 tokens are signed with RSA (asymmetric).
 *   The private key signs; the public key (at /oauth2/jwks) verifies.
 *   This allows resource servers to verify tokens without the private key.
 *
 *   Our custom JWT endpoint uses HMAC-SHA256 (symmetric) for comparison.
 */
@Configuration
public class AuthorizationServerConfig {

    /**
     * Authorization Server security filter chain — highest priority (@Order(1)).
     * Handles all /oauth2/** and /.well-known/** endpoints.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults()); // Enable OpenID Connect 1.0

        http
                // Redirect to /login for Authorization Code flow (when browser hits /oauth2/authorize unauthenticated)
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // The UserInfo endpoint (/userinfo) is protected — validate OAuth2 tokens
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));

        return http.build();
    }

    // ----------------------------------------------------------------
    // Registered OAuth2 Clients
    // In production: store in a database (JdbcRegisteredClientRepository)
    // ----------------------------------------------------------------

    @Bean
    public RegisteredClientRepository registeredClientRepository() {

        // ── Client 1: Machine-to-Machine (client_credentials) ──────────
        // Used when a backend service calls another service directly.
        // No user involved. Client authenticates with its own credentials.
        //
        // How to test (Postman or curl):
        //   POST /oauth2/token
        //   Authorization: Basic (base64 of "machine-client:machine-secret")
        //   Body: grant_type=client_credentials&scope=read
        RegisteredClient machineClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("machine-client")
                .clientSecret("{noop}machine-secret") // {noop} = plain text (DEMO ONLY; use BCrypt in prod)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("read")
                .scope("write")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();

        // ── Client 2: Web Application (authorization_code) ─────────────
        // Full OAuth2 flow: user logs in, grants consent, client gets code,
        // exchanges code for tokens.
        //
        // How to test (browser):
        //   1. Visit: http://localhost:8080/oauth2/authorize
        //             ?client_id=web-client
        //             &response_type=code
        //             &redirect_uri=http://localhost:8080/api/oauth/callback
        //             &scope=openid+read
        //   2. Login with admin/password (or user/password)
        //   3. Approve the consent screen
        //   4. Browser redirects to /api/oauth/callback?code=<AUTH_CODE>
        //   5. Exchange code: POST /oauth2/token
        //      Body: grant_type=authorization_code&code=<CODE>
        //            &redirect_uri=http://localhost:8080/api/oauth/callback
        //      Auth: Basic (base64 of "web-client:web-secret")
        RegisteredClient webClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("web-client")
                .clientSecret("{noop}web-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:8080/api/oauth/callback")
                .redirectUri("https://oauth.pstmn.io/v1/callback") // Postman OAuth helper
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("read")
                .scope("write")
                .clientSettings(ClientSettings.builder()
                        .requireAuthorizationConsent(true) // Show consent screen (teaches OAuth2 consent concept)
                        .requireProofKey(false)            // Set true to enforce PKCE (recommended for SPAs)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(1))
                        .reuseRefreshTokens(false) // Rotate refresh tokens — more secure
                        .build())
                .build();

        return new InMemoryRegisteredClientRepository(machineClient, webClient);
    }

    // ----------------------------------------------------------------
    // RSA Key Pair — signs the OAuth2 JWTs
    //
    // Asymmetric (RSA) vs Symmetric (HMAC):
    //   RSA:  private key signs, public key verifies
    //         → Resource servers can verify without the private key
    //         → Better for multi-service architectures
    //   HMAC: same key signs and verifies
    //         → Simpler but requires sharing the secret
    //
    // PRODUCTION: Load the key pair from a KMS or HSM.
    //             Do NOT generate a new key on each startup — all
    //             previously issued tokens become unverifiable!
    // ----------------------------------------------------------------

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        RSAKey rsaKey = generateRsaKey();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private static RSAKey generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                    .privateKey((RSAPrivateKey) keyPair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer("http://localhost:8080")
                .build();
    }
}
