package com.example.apidemo.controller;

import com.example.apidemo.service.ExternalApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CONCEPT: Timeout, Circuit Breaker, and Retry Patterns
 *
 * Without resilience patterns, a slow/failing downstream service causes:
 *   - Thread pool exhaustion (threads stuck waiting)
 *   - Cascading failures (your service goes down too)
 *   - Poor user experience (indefinite hangs)
 *
 * Resilience4j provides three complementary patterns:
 *
 *   @TimeLimiter   — fail fast if a call takes too long (3s configured)
 *   @Retry         — retry transient failures with backoff
 *   @CircuitBreaker — stop calling a broken service; auto-recover
 *
 * Monitor circuit breaker state:
 *   GET /actuator/circuitbreakers
 *   GET /actuator/circuitbreakerevents
 */
@RestController
@RequestMapping("/api/timeout")
@RequiredArgsConstructor
@Tag(name = "08. Timeouts & Resilience", description = "Circuit breaker, retry, and timeout pattern demos")
public class TimeoutController {

    private final ExternalApiService externalApiService;

    @GetMapping("/info")
    @Operation(summary = "Resilience patterns explained")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "patterns", Map.of(
                        "TimeLimiter", Map.of(
                                "what", "Cancels async calls that exceed a time limit",
                                "config", "timelimiter.instances.externalApi.timeout-duration=3s",
                                "test", "GET /api/timeout/slow?delay=2 (passes) vs ?delay=4 (triggers fallback)"
                        ),
                        "CircuitBreaker", Map.of(
                                "what", "Stops calling a failing service; returns fallback immediately",
                                "states", "CLOSED (normal) → OPEN (failing) → HALF_OPEN (testing) → CLOSED",
                                "config", "failure-rate-threshold=50%, sliding-window-size=10",
                                "test", "GET /api/timeout/unreliable?fail=true  (repeat 5+ times to open circuit)"
                        ),
                        "Retry", Map.of(
                                "what", "Retries failed calls with exponential backoff",
                                "config", "max-attempts=3, wait=500ms, exponential backoff x2",
                                "test", "GET /api/timeout/flaky  (succeeds on 3rd attempt)"
                        )
                ),
                "monitor", Map.of(
                        "circuit_breakers", "GET /actuator/circuitbreakers",
                        "events", "GET /actuator/circuitbreakerevents"
                )
        ));
    }

    /**
     * TIMEOUT DEMO
     *
     * The ExternalApiService.callSlowService() is decorated with @TimeLimiter (3s timeout).
     * If delay < 3: returns the actual response
     * If delay >= 3: TimeoutException → fallback message returned
     *
     * Returns CompletableFuture — Spring MVC handles async responses transparently.
     */
    @GetMapping("/slow")
    @Operation(
            summary = "Timeout demo",
            description = "Simulates a slow service. Configured timeout is 3 seconds. " +
                          "?delay=2 succeeds; ?delay=4 triggers the timeout fallback."
    )
    public CompletableFuture<ResponseEntity<Map<String, Object>>> slowEndpoint(
            @RequestParam(defaultValue = "2") int delay) {

        return externalApiService.callSlowService(delay)
                .thenApply(result -> ResponseEntity.ok(Map.of(
                        "result", result,
                        "requestedDelay", delay + "s",
                        "timeout", "3s",
                        "status", delay < 3 ? "SUCCESS" : "Would timeout but fallback handled it"
                )));
    }

    /**
     * CIRCUIT BREAKER DEMO
     *
     * Pass fail=true to simulate service failures.
     * After 5+ failures (50% of 10-call window), the circuit OPENS.
     * Once open: calls return the fallback immediately (no real call made).
     *
     * Steps to see the circuit open:
     *   1. GET /api/timeout/unreliable?fail=true  (repeat 5-6 times)
     *   2. GET /actuator/circuitbreakers          (see state=OPEN)
     *   3. Wait 10 seconds (waitDurationInOpenState)
     *   4. GET /api/timeout/unreliable?fail=false (circuit tests recovery)
     */
    @GetMapping("/unreliable")
    @Operation(
            summary = "Circuit breaker demo",
            description = "?fail=true simulates service failure. Repeat 5+ times to open the circuit. " +
                          "Then watch /actuator/circuitbreakers for state changes."
    )
    public ResponseEntity<Map<String, Object>> unreliableEndpoint(
            @RequestParam(defaultValue = "false") boolean fail) {

        String result = externalApiService.callUnreliableService(fail);
        return ResponseEntity.ok(Map.of(
                "result", result,
                "fail_requested", fail,
                "tip", "Repeat with fail=true to open the circuit. Check /actuator/circuitbreakers"
        ));
    }

    /**
     * RETRY DEMO
     *
     * ExternalApiService.callFlakyService() fails on the first 2 attempts
     * and succeeds on the 3rd. With @Retry(max-attempts=3), this succeeds transparently.
     *
     * In the logs, you'll see: "Flaky service attempt #1", "#2", "#3 — success"
     */
    @GetMapping("/flaky")
    @Operation(
            summary = "Retry with backoff demo",
            description = "The service fails on attempts 1 and 2, succeeds on attempt 3. " +
                          "Resilience4j retries automatically (500ms, then 1s backoff). Watch the logs."
    )
    public ResponseEntity<Map<String, Object>> flakyEndpoint() {
        externalApiService.resetFailureCount();
        String result = externalApiService.callFlakyService(1);
        return ResponseEntity.ok(Map.of(
                "result", result,
                "how", "3 attempts made internally with 500ms/1000ms exponential backoff",
                "tip", "Check application logs to see retry attempts"
        ));
    }

    /**
     * WHAT HAPPENS WITHOUT RESILIENCE PATTERNS (educational)
     *
     * This endpoint simulates a long-running operation with NO timeout.
     * In production, this would block a thread for the full duration,
     * potentially exhausting the thread pool.
     */
    @GetMapping("/no-protection")
    @Operation(
            summary = "Unprotected slow call (educational)",
            description = "Shows what happens without timeout protection: the request blocks for the full duration. " +
                          "With enough concurrent requests, this exhausts the thread pool."
    )
    public ResponseEntity<Map<String, Object>> noProtection(
            @RequestParam(defaultValue = "2") int delay) throws InterruptedException {

        long start = System.currentTimeMillis();
        Thread.sleep(delay * 1000L); // Blocks a server thread for `delay` seconds
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "message", "Completed after " + elapsed + "ms with no timeout protection",
                "risk", "Under load, many concurrent slow requests exhaust the thread pool",
                "solution", "Use @TimeLimiter + CompletableFuture to avoid blocking threads",
                "delay", delay + "s"
        ));
    }
}
