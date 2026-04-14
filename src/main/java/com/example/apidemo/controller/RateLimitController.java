package com.example.apidemo.controller;

import com.example.apidemo.config.RateLimitConfig;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * CONCEPT: Rate Limiting
 *
 * Rate limiting protects APIs from:
 *   - Abuse and DDoS attacks
 *   - Runaway client bugs flooding the server
 *   - Fair resource allocation across tenants
 *
 * This demo uses Bucket4j's token-bucket algorithm.
 * Rate limit state is stored per IP in memory (see RateLimitConfig).
 *
 * HTTP HEADERS:
 *   Industry practice is to communicate limits via response headers:
 *     X-Rate-Limit-Limit     — max requests allowed
 *     X-Rate-Limit-Remaining — tokens left in current window
 *     X-Rate-Limit-Retry-After-Seconds — seconds until next token
 *   (GitHub, Stripe, and other major APIs use similar headers)
 *
 * HTTP STATUS 429 Too Many Requests — returned when rate limited.
 */
@Slf4j
@RestController
@RequestMapping("/api/rate")
@Tag(name = "07. Rate Limiting", description = "Rate limiting demo — rapid-fire requests to see 429 responses")
public class RateLimitController {

    private final RateLimitConfig rateLimitConfig;
    private final Bucket strictBucket;
    private final Bucket tieredBucket;

    public RateLimitController(RateLimitConfig rateLimitConfig) {
        this.rateLimitConfig = rateLimitConfig;
        this.strictBucket = rateLimitConfig.createStrictBucket();
        this.tieredBucket = rateLimitConfig.createTieredBucket();
    }

    /**
     * Standard rate limit: 20 requests per minute, keyed by client IP.
     *
     * To see rate limiting in action:
     *   for i in {1..25}; do curl -s http://localhost:8080/api/rate/standard | jq .status; done
     */
    @GetMapping("/standard")
    @Operation(
            summary = "Standard rate limit (20 req/min per IP)",
            description = "Send many rapid requests to trigger 429. " +
                          "Watch the X-Rate-Limit-Remaining header decrease."
    )
    public ResponseEntity<?> standardRateLimit(HttpServletRequest request) {
        String clientIp = getClientIp(request);
        Bucket bucket = rateLimitConfig.resolveIpBucket(clientIp);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return ResponseEntity.ok()
                    .headers(rateLimitHeaders(20, probe.getRemainingTokens(), 0))
                    .body(Map.of(
                            "message", "Request allowed",
                            "clientIp", clientIp,
                            "remainingTokens", probe.getRemainingTokens(),
                            "tip", "Keep hitting this endpoint to exhaust tokens and see 429"
                    ));
        }

        long retryAfterSeconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(rateLimitHeaders(20, 0, retryAfterSeconds))
                .body(Map.of(
                        "error", "Too Many Requests",
                        "message", "Rate limit exceeded: 20 requests per minute per IP",
                        "retryAfterSeconds", retryAfterSeconds,
                        "clientIp", clientIp
                ));
    }

    /**
     * Strict rate limit: 5 requests per minute (shared bucket, not per-IP).
     * Simulates a sensitive endpoint like /login or /forgot-password.
     */
    @GetMapping("/strict")
    @Operation(
            summary = "Strict rate limit (5 req/min total)",
            description = "Very tight limit — 5 requests per minute for the entire endpoint. " +
                          "Models protecting login or password-reset endpoints."
    )
    public ResponseEntity<?> strictRateLimit() {
        ConsumptionProbe probe = strictBucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return ResponseEntity.ok()
                    .headers(rateLimitHeaders(5, probe.getRemainingTokens(), 0))
                    .body(Map.of(
                            "message", "Request allowed (strict endpoint)",
                            "remaining", probe.getRemainingTokens(),
                            "limit", 5,
                            "use_case", "Login, password reset, OTP endpoints"
                    ));
        }

        long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(rateLimitHeaders(5, 0, retryAfter))
                .body(Map.of(
                        "error", "Too Many Requests",
                        "limit", "5 requests per minute",
                        "retryAfterSeconds", retryAfter
                ));
    }

    /**
     * Tiered rate limit: burst (10 per 10s) + sustained (100 per hour).
     *
     * This is how production APIs work:
     *   - Allow short bursts (a loop in client code is fine)
     *   - Prevent sustained high volume (runaway clients)
     */
    @GetMapping("/tiered")
    @Operation(
            summary = "Tiered rate limit (burst + sustained)",
            description = "Two limits apply simultaneously: burst (10/10s) and sustained (100/hr). " +
                          "The more restrictive one applies. Demonstrates real-world API throttling tiers."
    )
    public ResponseEntity<?> tieredRateLimit() {
        ConsumptionProbe probe = tieredBucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return ResponseEntity.ok()
                    .headers(rateLimitHeaders(10, probe.getRemainingTokens(), 0))
                    .body(Map.of(
                            "message", "Request allowed",
                            "remaining_tokens", probe.getRemainingTokens(),
                            "limits", Map.of(
                                    "burst", "10 per 10 seconds",
                                    "sustained", "100 per hour"
                            ),
                            "note", "Both limits must pass — whichever is more restrictive wins"
                    ));
        }

        long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .headers(rateLimitHeaders(10, 0, retryAfter))
                .body(Map.of(
                        "error", "Too Many Requests — rate limit exceeded",
                        "retryAfterSeconds", retryAfter
                ));
    }

    @GetMapping("/info")
    @Operation(summary = "Rate limit configuration info", description = "Shows current rate limit settings")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "endpoints", Map.of(
                        "/api/rate/standard", "20 req/min per IP (token bucket)",
                        "/api/rate/strict", "5 req/min total (shared bucket)",
                        "/api/rate/tiered", "10 per 10s (burst) + 100 per hour (sustained)"
                ),
                "algorithm", "Token Bucket (Bucket4j)",
                "storage", "In-memory ConcurrentHashMap (single node demo)",
                "production_alternative", "bucket4j-redis for distributed rate limiting",
                "headers", Map.of(
                        "X-Rate-Limit-Limit", "Max allowed requests",
                        "X-Rate-Limit-Remaining", "Tokens left",
                        "X-Rate-Limit-Retry-After-Seconds", "Wait time on 429"
                )
        ));
    }

    private HttpHeaders rateLimitHeaders(long limit, long remaining, long retryAfterSeconds) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Rate-Limit-Limit", String.valueOf(limit));
        headers.add("X-Rate-Limit-Remaining", String.valueOf(remaining));
        if (retryAfterSeconds > 0) {
            headers.add("X-Rate-Limit-Retry-After-Seconds", String.valueOf(retryAfterSeconds));
            headers.add("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return headers;
    }

    private String getClientIp(HttpServletRequest request) {
        // Check common proxy headers first
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim(); // First IP in chain is the client
        }
        return request.getRemoteAddr();
    }
}
