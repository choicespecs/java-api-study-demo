# Handling Hanging API Calls

## The Problem

A "hanging" API call is one that takes far longer than expected — the downstream service accepted the connection but then stopped responding. Common causes:

- Downstream service is overloaded and processing slowly
- Database query without a timeout running indefinitely
- Network partition: TCP connection established but packets dropped
- Downstream service crashed mid-response

### Why This Brings Down Your Entire Server

Every in-flight HTTP request holds a Tomcat thread. The default thread pool is **200 threads**.

```
t=0s    First slow request arrives → thread #1 blocked
t=10s   20 concurrent hangs → 20 threads blocked
t=30s   200 concurrent hangs → ALL threads blocked
t=31s   New requests queue → users see timeouts for unrelated endpoints
t=??s   Downstream recovers → your server is overwhelmed by backed-up retries
```

The slow service doesn't just cause its own endpoints to fail — it causes **every endpoint on your server** to fail. This is **thread pool exhaustion** and it's one of the most common causes of cascading failures.

---

## Strategy 1: No Protection (BAD)

```java
// ❌ Blocks the Tomcat thread for the full duration
@GetMapping("/data")
public ResponseEntity<?> getData() throws InterruptedException {
    String result = callDownstreamService(); // may hang indefinitely
    return ResponseEntity.ok(result);
}
```

**Demo:** `GET /api/hanging/no-timeout?delay=10`

The request thread is blocked for 10 seconds. The browser waits. Under load, this exhausts the thread pool.

---

## Strategy 2: `CompletableFuture.orTimeout()` (Java 9+)

```java
// ✓ Built-in Java deadline — no extra dependencies
@GetMapping("/data")
public ResponseEntity<?> getData() throws Exception {
    try {
        String result = CompletableFuture
            .supplyAsync(() -> callDownstreamService(), executor)
            .orTimeout(3, TimeUnit.SECONDS)  // fail fast after 3s
            .get();
        return ResponseEntity.ok(Map.of("result", result));
    } catch (ExecutionException e) {
        if (e.getCause() instanceof TimeoutException) {
            return ResponseEntity.status(504).body(Map.of(
                "error", "Downstream timed out after 3s"
            ));
        }
        throw e;
    }
}
```

**Demo:** `GET /api/hanging/with-deadline?delay=8&deadline=3`

- If `delay < deadline`: normal 200 response
- If `delay >= deadline`: 504 response after exactly `deadline` seconds

**Important caveat:** `orTimeout()` completes the future exceptionally, but the task in the executor may still be running. For true cancellation, call `future.cancel(true)` and handle `InterruptedException` in the task.

### `completeOnTimeout()` — Return a Default Instead

If you have a reasonable default, use `completeOnTimeout()` instead of `orTimeout()`:

```java
String result = CompletableFuture
    .supplyAsync(() -> callDownstreamService(), executor)
    .completeOnTimeout("cached-default-value", 3, TimeUnit.SECONDS)
    .get();
// Returns "cached-default-value" if timeout exceeded — no exception thrown
```

---

## Strategy 3: HTTP Client Timeouts

When your service calls another service, configure your HTTP client. There are **two distinct timeouts**:

| Timeout | Protects against | Java HttpClient |
|---------|-----------------|-----------------|
| `connectTimeout` | Unreachable host (DNS failure, firewall) | `.connectTimeout(Duration)` on `HttpClient.Builder` |
| Request `timeout` | Slow response after connection is open | `.timeout(Duration)` on `HttpRequest.Builder` |

```java
// ✓ Configure once per client instance (share the client!)
HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(2))   // establish TCP in 2s or fail
    .build();

// ✓ Per-request timeout
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.example.com/data"))
    .timeout(Duration.ofSeconds(5))          // full round trip in 5s or fail
    .GET()
    .build();

try {
    HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
    // success
} catch (HttpTimeoutException e) {
    // Handle timeout — return cached data, default response, or 504
}
```

**Demo:** `GET /api/hanging/http-client?delay=8&timeout=3`

### The Critical Lesson: Client Timeout ≠ Server Cancellation

When the client times out, it stops waiting and returns an error to the caller. But the **server-side thread handling the downstream call keeps running**. The downstream service doesn't know the client gave up.

```
Client (your service)     Your server thread         Downstream service
      |                          |                          |
      |-- request -------------→ |-- callDownstream() ---→ |
      |                          |          (hanging...)    |
t=3s  |←-- 504 (client timeout) |                          | (still running)
      |                          |          (hanging...)    |
      |                          |          (hanging...)    |
t=8s  |                          |←-- response ------------ |
      |                          | (thread finally freed)   |
```

This means client-side timeouts protect the **caller** but don't protect the **server's resources**. You need server-side protection too (Strategy 2 or Resilience4j `@TimeLimiter`).

---

## Strategy 4: Resilience4j `@TimeLimiter` (Production)

For production use, prefer `@TimeLimiter` from Resilience4j. It combines:
- Deadline enforcement (like `orTimeout`)
- Fallback method invocation on timeout
- Metrics and monitoring via Spring Boot Actuator

```java
@TimeLimiter(name = "externalApi", fallbackMethod = "fallback")
@CircuitBreaker(name = "externalApi")
public CompletableFuture<String> callService() {
    return CompletableFuture.supplyAsync(() -> slowDownstream(), executor);
}

// Fallback must match signature + trailing Throwable
public CompletableFuture<String> fallback(Throwable t) {
    return CompletableFuture.completedFuture("Cached response — service unavailable");
}
```

Config in `application.yml`:
```yaml
resilience4j:
  timelimiter:
    instances:
      externalApi:
        timeout-duration: 3s
        cancel-running-future: true  # actually interrupts the future
```

See the Circuit Breaker page for a live demo of `@TimeLimiter` in action.

---

## The Two-Timeout Rule

Always set **both** timeouts on every outbound HTTP client:

```
Without connect timeout: vulnerable to dead hosts (accepted SYN, never respond)
Without read timeout:    vulnerable to slow servers (accepted, never finish sending)
```

A common mistake is setting only one:

```java
// ❌ No connect timeout — hangs if host is unreachable
client.readTimeout(Duration.ofSeconds(5));

// ❌ No read timeout — hangs if server responds slowly
client.connectTimeout(Duration.ofSeconds(2));

// ✓ Both — covers all hanging scenarios
client.connectTimeout(Duration.ofSeconds(2))
      .readTimeout(Duration.ofSeconds(5));
```

---

## Strategy Comparison

| Strategy | Frees request thread | Cancels server work | Needs dependency | Best for |
|----------|---------------------|--------------------|--------------------|----------|
| No protection | ✗ Never | ✗ N/A | None | ⚠ Don't use |
| `CompletableFuture.orTimeout()` | ✓ At deadline | ✗ May linger | None (Java 9+) | Simple calls |
| `completeOnTimeout()` | ✓ At deadline | ✗ May linger | None (Java 9+) | When you have a default |
| HTTP client timeouts | ✓ At timeout | ✗ Server thread runs on | None (Java 11+) | Outbound HTTP |
| `@TimeLimiter` (Resilience4j) | ✓ At timeout | ✓ With `cancel-running-future: true` | Resilience4j | Production services |
| `@CircuitBreaker` | ✓ Immediately (when open) | ✓ No call made | Resilience4j | Repeatedly failing services |

---

## Common Mistakes

**1. Setting timeouts too high**
A 60s timeout doesn't protect you if 200 threads hit it simultaneously. At 200 threads × 60s = 200 minutes of blocked time. Keep timeouts aggressive (2–5s for internal services).

**2. Not handling the timeout case in the client**
If you don't catch `HttpTimeoutException` / `TimeoutException`, it propagates as a 500. Return a 503 or 504 with a `Retry-After` header instead.

**3. Forgetting to configure the HTTP client**
Many HTTP clients have no timeout by default. Forgetting to configure `RestTemplate`, `WebClient`, or `HttpClient` means `connectTimeout = ∞` and `readTimeout = ∞`.

**4. Using `Thread.sleep()` in production code to simulate work**
Real work should be async. If your service genuinely needs N seconds of CPU, that's different from waiting for I/O. Async patterns apply to I/O-bound work (network calls, DB queries).
