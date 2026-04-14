package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;

/**
 * CONCEPT: Handling Hanging API Calls
 *
 * A "hanging" API call is one that takes far longer than expected — often because
 * a downstream service is overloaded, has crashed, or the network is degraded.
 *
 * WHY IT MATTERS:
 *   Every blocked request holds a server thread. Tomcat's default thread pool is 200.
 *   If 200 downstream calls hang simultaneously, your server stops responding to
 *   ALL requests — not just calls to the slow service. This is thread pool exhaustion.
 *
 *   Timeline of failure without protection:
 *     t=0s   First slow request arrives, thread #1 blocks
 *     t=30s  200 concurrent slow requests → all 200 threads blocked
 *     t=31s  New requests queue up → timeouts → users see errors
 *     t=??s  Downstream recovers, but your server is overwhelmed by queued retries
 *
 * THREE PROTECTION STRATEGIES DEMOED HERE:
 *
 *   1. No protection (BAD)        → /api/hanging/no-timeout
 *      Thread.sleep blocks the request thread for the full duration.
 *
 *   2. CompletableFuture deadline  → /api/hanging/with-deadline
 *      Java 9+ built-in: .orTimeout(N, SECONDS) fails fast, frees the request thread.
 *      The async work still runs in an executor thread but the HTTP response is fast.
 *
 *   3. HTTP client timeouts        → /api/hanging/http-client
 *      When YOUR service calls another service, configure both:
 *        - connectTimeout: max time to establish the TCP connection
 *        - requestTimeout: max time for the full request (connect + headers + body)
 *      Demonstrates that client-side timeout does NOT cancel the server-side work.
 *
 * RELATED PATTERNS (in TimeoutController):
 *   @TimeLimiter (Resilience4j) — similar to approach 2 but with fallback and metrics
 *   @CircuitBreaker             — stops calling a repeatedly-hanging service entirely
 */
@RestController
@RequestMapping("/api/hanging")
@Tag(name = "09. Hanging APIs", description = "Thread exhaustion, deadlines, and HTTP client timeouts")
public class HangingApiController {

    // Dedicated executor for async tasks (keeps them off Tomcat's request threads)
    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ── APPROACH 0: INFO ────────────────────────────────────────

    @GetMapping("/info")
    @Operation(summary = "Hanging API concept overview")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "problem", "Hanging downstream calls block server threads, exhausting the thread pool",
                "thread_pool_size", "Tomcat default = 200 threads. 200 concurrent hangs = server freeze.",
                "strategies", Map.of(
                        "1_no_timeout",    "GET /api/hanging/no-timeout?delay=5  — BAD: blocks a thread",
                        "2_deadline",      "GET /api/hanging/with-deadline?delay=5&deadline=3  — CompletableFuture.orTimeout()",
                        "3_http_client",   "GET /api/hanging/http-client?delay=5&timeout=3  — Java HttpClient with timeouts"
                ),
                "key_insight", "A client-side timeout protects the CALLER but does NOT cancel work on the server. " +
                               "Both ends need protection."
        ));
    }

    // ── APPROACH 1: NO PROTECTION (BAD) ────────────────────────

    /**
     * The naive approach: Thread.sleep() blocks the Tomcat request thread for the
     * entire duration. Under load, this exhausts the server thread pool.
     *
     * Try setting delay=30 and opening many tabs simultaneously to see the effect.
     */
    @GetMapping("/no-timeout")
    @Operation(
            summary = "No timeout protection (BAD)",
            description = "Simulates a slow downstream call with no protection. " +
                          "The server thread is blocked for the full delay duration. " +
                          "Try delay=10 to feel how long the browser waits."
    )
    public ResponseEntity<Map<String, Object>> noTimeout(
            @RequestParam(defaultValue = "5") int delay) throws InterruptedException {

        long start = System.currentTimeMillis();
        // ❌ Blocks the Tomcat request thread — the thread cannot serve other requests
        Thread.sleep(delay * 1000L);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "result", "completed",
                "elapsed_ms", elapsed,
                "delay_requested", delay + "s",
                "thread", Thread.currentThread().getName(),
                "problem", "This thread was blocked for " + elapsed + "ms. " +
                           "With " + delay + "s hangs and 200 threads, your server freezes at " +
                           (200 * delay) + "s of total blocked time.",
                "fix", "Use CompletableFuture.orTimeout() or @TimeLimiter"
        ));
    }

    // ── APPROACH 2: COMPLETABLEFUTURE + orTimeout() ────────────

    /**
     * Java 9+ built-in deadline pattern.
     *
     * The slow work runs in a separate executor thread. The request thread calls
     * .get() which blocks briefly, but orTimeout() completes the future exceptionally
     * after `deadline` seconds — freeing the request thread with a fast error response.
     *
     * KEY INSIGHT: The executor thread may still be running (doing real work) even
     * after the timeout. It will eventually complete or be interrupted. The CALLER
     * gets a fast response; the work may continue in the background.
     *
     * For true cancellation, use CompletableFuture.cancel(true) and handle
     * InterruptedException in the task.
     */
    @GetMapping("/with-deadline")
    @Operation(
            summary = "CompletableFuture.orTimeout() deadline",
            description = "Java 9+ built-in. If delay > deadline, the caller gets a fast 504 response " +
                          "instead of waiting the full delay. Try delay=8 with deadline=3."
    )
    public ResponseEntity<Map<String, Object>> withDeadline(
            @RequestParam(defaultValue = "5") int delay,
            @RequestParam(defaultValue = "3") int deadline) {

        long start = System.currentTimeMillis();

        CompletableFuture<String> future = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Thread.sleep(delay * 1000L); // simulated slow work
                        return "Downstream responded after " + delay + "s";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "Task was interrupted";
                    }
                }, executor)
                .orTimeout(deadline, TimeUnit.SECONDS); // ✓ fail fast if exceeded

        try {
            String result = future.get();
            long elapsed = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "result", result,
                    "elapsed_ms", elapsed,
                    "status", "SUCCESS",
                    "note", "Responded within the " + deadline + "s deadline"
            ));
        } catch (ExecutionException e) {
            long elapsed = System.currentTimeMillis() - start;
            if (e.getCause() instanceof TimeoutException) {
                return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                        "result", "DEADLINE_EXCEEDED",
                        "elapsed_ms", elapsed,
                        "deadline_s", deadline,
                        "technique", "CompletableFuture.orTimeout(" + deadline + ", SECONDS)",
                        "note", "Caller got a fast 504 after " + deadline + "s. " +
                                "The async task may still be running in the background.",
                        "for_true_cancellation", "Call future.cancel(true) and handle InterruptedException in the task"
                ));
            }
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    // ── APPROACH 3: HTTP CLIENT WITH TIMEOUTS ──────────────────

    /**
     * When your service calls another service, configure your HTTP client with
     * explicit timeouts. Java 11's HttpClient supports two levels:
     *
     *   connectTimeout — max time to establish the TCP connection.
     *                    Guards against unreachable hosts.
     *
     *   request timeout (per-request) — max time for the full round trip.
     *                    Guards against slow responses after connection is open.
     *
     * CRITICAL LESSON: An HTTP client timeout cancels the client's wait,
     * but the server-side thread handling /api/hanging/no-timeout keeps running.
     * The server burns CPU/thread resources even though the client gave up.
     * → BOTH caller and callee need timeout protection.
     *
     * This endpoint calls /api/hanging/no-timeout on itself to demonstrate.
     */
    @GetMapping("/http-client")
    @Operation(
            summary = "HTTP client with connect + request timeouts",
            description = "Calls /api/hanging/no-timeout using a Java HttpClient configured with timeouts. " +
                          "Try delay=8 with timeout=3 — the client aborts in 3s even though the server " +
                          "would take 8s. Notice: the server-side thread still runs for 8s."
    )
    public ResponseEntity<Map<String, Object>> withHttpClientTimeout(
            @RequestParam(defaultValue = "5") int delay,
            @RequestParam(defaultValue = "3") int timeout) {

        // ✓ Configure timeouts on the client — not on the server
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)) // TCP connection timeout
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/hanging/no-timeout?delay=" + delay))
                .timeout(Duration.ofSeconds(timeout)) // per-request total timeout
                .GET()
                .build();

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            return ResponseEntity.ok(Map.of(
                    "result", "SUCCESS",
                    "elapsed_ms", elapsed,
                    "downstream_status", response.statusCode(),
                    "note", "Downstream responded within the " + timeout + "s timeout"
            ));
        } catch (HttpTimeoutException e) {
            long elapsed = System.currentTimeMillis() - start;
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of(
                    "result", "CLIENT_TIMEOUT",
                    "elapsed_ms", elapsed,
                    "timeout_s", timeout,
                    "connect_timeout_s", 2,
                    "error_type", "HttpTimeoutException",
                    "critical_lesson", "This client gave up after " + timeout + "s, " +
                                       "but the server-side thread for /api/hanging/no-timeout " +
                                       "is still sleeping for " + delay + "s total. " +
                                       "Client timeout ≠ server cancellation.",
                    "full_protection", "Add server-side timeout too: @TimeLimiter or CompletableFuture.orTimeout()"
            ));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "result", "ERROR",
                    "elapsed_ms", elapsed,
                    "error", e.getClass().getSimpleName() + ": " + e.getMessage()
            ));
        }
    }
}
