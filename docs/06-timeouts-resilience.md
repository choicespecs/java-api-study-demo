# Timeouts & Resilience Patterns

Without resilience patterns, a slow or failing downstream service can take your entire service down — a **cascading failure**.

---

## The Problem: Cascading Failures

```
Request → Your Service → (calls) → Slow External Service (5s response time)
                                                      ↓
Your thread pool: 200 threads × 5s = only 40 req/sec max
                                                      ↓
Thread pool exhausted → your service hangs → upstream hangs → entire system down
```

---

## Pattern 1: Timeout

**Rule:** Every external call must have a timeout.  
**No timeout = thread waits forever.**

```java
// With @TimeLimiter (timeout = 3s configured in application.yml)
@TimeLimiter(name = "externalApi", fallbackMethod = "timeoutFallback")
public CompletableFuture<String> callSlowService(int delaySeconds) {
    return CompletableFuture.supplyAsync(() -> {
        Thread.sleep(delaySeconds * 1000L);  // Simulated slow call
        return "response";
    });
}
```

**Demo:**
```bash
# Succeeds (2s < 3s timeout)
curl "http://localhost:8080/api/timeout/slow?delay=2"

# Times out → fallback response returned
curl "http://localhost:8080/api/timeout/slow?delay=4"

# No protection — blocks a server thread for the full duration
curl "http://localhost:8080/api/timeout/no-protection?delay=5"
```

---

## Pattern 2: Circuit Breaker

Think of an electrical circuit breaker: when too many failures occur, the circuit "opens" and electricity (requests) stop flowing — protecting the system from further damage.

**States:**
```
CLOSED → normal operation, requests flow through
  ↓ (failure rate > 50% over last 10 calls)
OPEN → requests fail immediately (fallback returned), no real calls made
  ↓ (after 10s waitDurationInOpenState)
HALF-OPEN → 3 test requests allowed through
  ↓ (if they succeed)              ↓ (if they fail again)
CLOSED (recovered)              OPEN (still broken)
```

**Demo:**
```bash
# Normal: circuit CLOSED
curl "http://localhost:8080/api/timeout/unreliable?fail=false"

# Fail 5+ times to open the circuit
for i in {1..6}; do
  curl "http://localhost:8080/api/timeout/unreliable?fail=true"
done

# Check circuit state
curl http://localhost:8080/actuator/circuitbreakers

# Now even with fail=false, circuit is OPEN → immediate fallback
curl "http://localhost:8080/api/timeout/unreliable?fail=false"

# Wait 10s, then try again — circuit goes to HALF-OPEN
sleep 10
curl "http://localhost:8080/api/timeout/unreliable?fail=false"
```

---

## Pattern 3: Retry with Exponential Backoff

For **transient failures** (network blips, temporary 503s) — retry automatically.

**Backoff schedule (configured in application.yml):**
```
Attempt 1 → fails → wait 500ms
Attempt 2 → fails → wait 1000ms (500ms × 2)
Attempt 3 → fails → wait 2000ms (1000ms × 2)
Give up → invoke fallback
```

**Demo:**
```bash
# The flaky service fails attempts 1 and 2, succeeds on attempt 3
# Resilience4j retries automatically — you see one successful response
curl http://localhost:8080/api/timeout/flaky

# Watch the logs:
# DEBUG c.e.a.s.ExternalApiService: Flaky service attempt #1
# DEBUG c.e.a.s.ExternalApiService: Flaky service attempt #2
# DEBUG c.e.a.s.ExternalApiService: Flaky service attempt #3
```

**CAUTION:** Only retry idempotent operations. Never retry a non-idempotent request (like "charge card") automatically — you might charge twice.

---

## Pattern 4: Bulkhead

Limits the number of **concurrent** calls to an external service.  
Prevents one slow dependency from consuming all your threads.

Think of ship compartments (bulkheads) — flooding one doesn't sink the ship.

```yaml
# application.yml
resilience4j:
  bulkhead:
    instances:
      externalApi:
        maxConcurrentCalls: 10
        maxWaitDuration: 100ms
```

```java
@Bulkhead(name = "externalApi", fallbackMethod = "bulkheadFallback")
public String callExternalApi() { ... }
```

---

## Combining Patterns

In production, combine all three:

```
Retry ( CircuitBreaker ( TimeLimiter ( function ) ) )
```

Resilience4j applies them in this order (outermost first):
1. **Retry**: if the inner operation fails, retry
2. **CircuitBreaker**: track success/failure rate
3. **TimeLimiter**: cancel if it takes too long

```java
@CircuitBreaker(name = "externalApi", fallbackMethod = "fallback")
@TimeLimiter(name = "externalApi")
@Retry(name = "externalApi")
public CompletableFuture<String> resilientCall() { ... }
```

---

## Monitoring Circuit Breakers

```bash
# View all circuit breakers and their states
curl http://localhost:8080/actuator/circuitbreakers | jq

# View recent events
curl http://localhost:8080/actuator/circuitbreakerevents | jq
```

---

## Configuration Reference

```yaml
resilience4j:
  circuitbreaker:
    instances:
      externalApi:
        slidingWindowSize: 10          # Evaluate last 10 calls
        minimumNumberOfCalls: 5        # Need 5 calls before tripping
        failureRateThreshold: 50       # Open if ≥50% fail
        waitDurationInOpenState: 10s   # Time in OPEN state before HALF-OPEN
        permittedNumberOfCallsInHalfOpenState: 3

  timelimiter:
    instances:
      externalApi:
        timeoutDuration: 3s

  retry:
    instances:
      externalApi:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
```

---

## Connection Pooling

Another timeout-prevention strategy: configure connection and read timeouts on HTTP clients.

```java
// RestTemplate with timeouts
@Bean
public RestTemplate restTemplate() {
    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory();
    factory.setConnectTimeout(2000);   // 2s to establish connection
    factory.setReadTimeout(5000);      // 5s to read response
    return new RestTemplate(factory);
}

// WebClient (reactive) with timeouts
WebClient.builder()
    .clientConnector(new ReactorClientHttpConnector(
        HttpClient.create()
            .responseTimeout(Duration.ofSeconds(3))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
    ))
    .build();
```
