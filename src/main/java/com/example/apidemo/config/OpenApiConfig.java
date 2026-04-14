package com.example.apidemo.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Swagger UI with security scheme definitions.
 * Access at: http://localhost:8080/swagger-ui.html
 *
 * Click "Authorize" to enter credentials before testing protected endpoints.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "REST API Study Demo",
                version = "1.0",
                description = "Demonstrates authentication, authorization, OAuth2, rate limiting, " +
                              "timeouts, pagination, versioning, and error handling concepts.",
                contact = @Contact(name = "Study Demo")
        )
)
@SecuritySchemes({
        @SecurityScheme(
                name = "bearerAuth",
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT",
                description = "JWT token from POST /api/auth/login"
        ),
        @SecurityScheme(
                name = "basicAuth",
                type = SecuritySchemeType.HTTP,
                scheme = "basic",
                description = "HTTP Basic Auth (username:password)"
        ),
        @SecurityScheme(
                name = "apiKeyAuth",
                type = SecuritySchemeType.APIKEY,
                in = io.swagger.v3.oas.annotations.enums.SecuritySchemeIn.HEADER,
                paramName = "X-API-Key",
                description = "API Key in X-API-Key header"
        )
})
public class OpenApiConfig {
}
