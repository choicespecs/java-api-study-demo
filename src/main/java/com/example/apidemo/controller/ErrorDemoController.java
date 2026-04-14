package com.example.apidemo.controller;

import com.example.apidemo.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * CONCEPT: Proper Error Handling
 *
 * Good error handling means:
 *   1. Correct HTTP status codes (4xx = client error, 5xx = server error)
 *   2. Meaningful error messages (help developers debug)
 *   3. Consistent response format (clients can parse predictably)
 *   4. Not leaking internals (no stack traces, no DB errors to clients)
 *
 * RFC 7807 Problem Details: standard format for HTTP API errors
 *   {
 *     "type": "https://api.example.com/errors/not-found",
 *     "title": "Resource Not Found",
 *     "status": 404,
 *     "detail": "Product with ID 999 does not exist",
 *     "instance": "/api/products/999"
 *   }
 *
 * Spring Boot 3 supports ProblemDetail natively (see GlobalExceptionHandler).
 */
@RestController
@RequestMapping("/api/errors")
@Tag(name = "11. Error Handling", description = "Error handling patterns and common HTTP status codes")
public class ErrorDemoController {

    @GetMapping("/info")
    @Operation(summary = "Error handling concepts")
    public ResponseEntity<Map<String, Object>> info() {
        // Map.of() is limited to 10 entries; use Map.ofEntries() for larger maps
        Map<String, String> statusCodes = new java.util.LinkedHashMap<>();
        statusCodes.put("200_OK", "Success");
        statusCodes.put("201_Created", "Resource created (include Location header)");
        statusCodes.put("204_No_Content", "Success with no body (DELETE, some PUT)");
        statusCodes.put("400_Bad_Request", "Client sent invalid data");
        statusCodes.put("401_Unauthorized", "Not authenticated (misleading name!)");
        statusCodes.put("403_Forbidden", "Authenticated but not authorized");
        statusCodes.put("404_Not_Found", "Resource doesn't exist");
        statusCodes.put("409_Conflict", "State conflict (duplicate, version mismatch)");
        statusCodes.put("422_Unprocessable_Entity", "Validation failed");
        statusCodes.put("429_Too_Many_Requests", "Rate limited");
        statusCodes.put("500_Internal_Server_Error", "Server bug (never leak details to client)");
        statusCodes.put("503_Service_Unavailable", "Temporarily down (circuit breaker open)");

        return ResponseEntity.ok(Map.of(
                "http_status_codes", statusCodes,
                "endpoints", Map.of(
                        "GET /api/errors/400", "Trigger a 400 Bad Request",
                        "GET /api/errors/404", "Trigger a 404 Not Found",
                        "GET /api/errors/409", "Trigger a 409 Conflict",
                        "GET /api/errors/500", "Trigger a 500 (handled safely)",
                        "POST /api/errors/validate", "Trigger validation errors (400)",
                        "GET /api/errors/leak-demo", "Shows bad vs good error responses"
                )
        ));
    }

    @GetMapping("/400")
    @Operation(summary = "400 Bad Request demo")
    public ResponseEntity<?> badRequest(@RequestParam(required = false) String input) {
        if (input == null || input.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "The 'input' query parameter is required and cannot be blank");
        }
        return ResponseEntity.ok(Map.of("input", input));
    }

    @GetMapping("/404")
    @Operation(summary = "404 Not Found demo")
    public ResponseEntity<?> notFound(@RequestParam(defaultValue = "42") Long id) {
        // Simulating a resource lookup that fails
        throw new ApiException(HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                "Product with ID " + id + " does not exist");
    }

    @GetMapping("/409")
    @Operation(summary = "409 Conflict demo", description = "Demonstrates conflict responses (e.g., duplicate resource)")
    public ResponseEntity<?> conflict() {
        throw new ApiException(HttpStatus.CONFLICT,
                "DUPLICATE_RESOURCE",
                "A product with the name 'Widget A' already exists. Use PUT to update it.");
    }

    @GetMapping("/500")
    @Operation(
            summary = "500 Internal Server Error (safe handling)",
            description = "Throws a RuntimeException — GlobalExceptionHandler catches it and returns a safe 500 response " +
                          "WITHOUT exposing the internal error details to the client."
    )
    public ResponseEntity<?> serverError() {
        // This exception's message will NOT be forwarded to the client
        throw new RuntimeException("Database connection pool exhausted: " +
                "jdbc:mysql://internal-db:3306 - this message is internal only");
    }

    /**
     * Demonstrates Spring Validation (@Valid).
     * On validation failure, Spring throws MethodArgumentNotValidException
     * which our GlobalExceptionHandler converts to a structured 400 response.
     */
    @PostMapping("/validate")
    @Operation(
            summary = "Validation error demo",
            description = "POST a body with invalid fields to see validation errors. " +
                          "Try: {} or {\"name\":\"\",\"quantity\":-1}"
    )
    public ResponseEntity<?> validate(@Valid @RequestBody CreateItemRequest request) {
        return ResponseEntity.ok(Map.of(
                "message", "Validation passed!",
                "received", request
        ));
    }

    /**
     * SECURITY CONCEPT: Never leak internal errors to clients.
     *
     * BAD:  "Error: Connection refused to jdbc:mysql://internal-db-01:3306/prod_db"
     * GOOD: "An unexpected error occurred. Please try again later. Ref: ERR-12345"
     *
     * Internal details help attackers (DB structure, server names, technology stack).
     */
    @GetMapping("/leak-demo")
    @Operation(
            summary = "Security: error message leak demo",
            description = "Shows what NOT to return (internal details) vs what to return (safe messages)"
        )
    public ResponseEntity<Map<String, Object>> leakDemo() {
        return ResponseEntity.ok(Map.of(
                "bad_practice", Map.of(
                        "example_response", Map.of(
                                "error", "Internal Server Error",
                                "message", "org.postgresql.util.PSQLException: ERROR: duplicate key value " +
                                           "violates unique constraint \"users_email_key\" on table users " +
                                           "in DB: prod-db-01.internal:5432/myapp",
                                "stackTrace", "at com.example..."
                        ),
                        "risks", java.util.List.of(
                                "Reveals DB technology (PostgreSQL)",
                                "Reveals internal hostname (prod-db-01.internal)",
                                "Reveals table and column names",
                                "Reveals application class structure"
                        )
                ),
                "good_practice", Map.of(
                        "example_response", Map.of(
                                "status", 409,
                                "error", "DUPLICATE_EMAIL",
                                "message", "An account with this email address already exists.",
                                "errorCode", "ERR-409-001"
                        ),
                        "benefits", java.util.List.of(
                                "Client gets actionable information",
                                "No internal details exposed",
                                "Error code can be logged server-side for debugging"
                        )
                )
        ));
    }

    // ── Request body DTO with validation ───────────────────────────

    @Data
    static class CreateItemRequest {
        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
        private String name;

        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;
    }
}
