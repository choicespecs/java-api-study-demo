package com.example.apidemo.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * CONCEPT: Centralized Exception Handling
 *
 * @RestControllerAdvice intercepts exceptions thrown by any @RestController.
 * Instead of each controller catching exceptions individually, one place handles all.
 *
 * USES RFC 7807 ProblemDetail (Spring Boot 3 native support):
 *   - type:     URI identifying the error type (links to docs)
 *   - title:    human-readable summary
 *   - status:   HTTP status code
 *   - detail:   specific error message
 *   - instance: URI of the specific request that failed
 *
 * SECURITY: The handler for generic exceptions (RuntimeException, Exception)
 *   intentionally does NOT forward the internal message to the client.
 *   Internal errors are logged server-side (with full detail) but the client
 *   receives only a generic safe message.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Our custom API exceptions — these are intentionally safe to expose.
     */
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex, WebRequest request) {
        log.debug("API exception: {} - {}", ex.getErrorCode(), ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setType(URI.create("https://api.example.com/errors/" +
                ex.getErrorCode().toLowerCase().replace('_', '-')));
        problem.setTitle(formatTitle(ex.getErrorCode()));
        problem.setProperty("errorCode", ex.getErrorCode());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * Bean validation failures (@Valid, @NotBlank, etc.)
     * Returns a 400 with field-level error details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
        log.debug("Validation failed: {} errors", ex.getBindingResult().getErrorCount());

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Request validation failed. Check 'fieldErrors' for details.");
        problem.setType(URI.create("https://api.example.com/errors/validation-failed"));
        problem.setTitle("Validation Failed");
        problem.setProperty("fieldErrors", fieldErrors);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * 401 Unauthorized — not authenticated.
     * Note: Spring Security usually handles this before reaching here,
     * but exceptions thrown inside controllers are caught here.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication required. Include a valid Authorization header.");
        problem.setTitle("Unauthorized");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * 403 Forbidden — authenticated but not authorized.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "You do not have permission to access this resource.");
        problem.setTitle("Forbidden");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    /**
     * SECURITY: Catch-all for unexpected exceptions.
     *
     * The internal exception message is logged (for ops/debugging) but
     * NEVER forwarded to the client. The client gets a safe generic message.
     *
     * In production:
     *   - Log the full stack trace with a correlation ID
     *   - Return the correlation ID to the client so they can reference it in support tickets
     *   - Alert on these (PagerDuty, CloudWatch alarm, etc.)
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, WebRequest request) {
        // Log the REAL error internally — never send this to the client
        log.error("Unexpected error processing request [{}]: {}",
                request.getDescription(false), ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.");
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        // In production: problem.setProperty("supportReference", generateCorrelationId());
        return problem;
    }

    private String formatTitle(String errorCode) {
        return errorCode.replace('_', ' ').substring(0, 1).toUpperCase()
                + errorCode.replace('_', ' ').substring(1).toLowerCase();
    }
}
