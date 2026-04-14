package com.example.apidemo.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CONCEPT: Resilience Patterns for External API Calls
 *
 * When calling external services, three things can go wrong:
 *   1. The service is SLOW        → TimeLimiter (timeout)
 *   2. The service FAILS randomly → Retry with backoff
 *   3. The service is DOWN        → CircuitBreaker (fail fast)
 *
 * Resilience4j applies these in order (outermost first):
 *   Retry ( CircuitBreaker ( TimeLimiter ( function ) ) )
 *
 * All three configs are in application.yml under resilience4j.*
 *
 * WHY THIS MATTERS:
 *   Without resilience patterns, a slow downstream service causes
 *   your threads to pile up waiting, eventually exhausting the thread pool
 *   and taking down your entire service — a "cascading failure".
 */
@Slf4j
@Service
public class ExternalApiService {

    // Tracks how many times the simulated failure endpoint has been called
    // Used to demonstrate the circuit breaker opening after repeated failures
    private final AtomicInteger failureCallCount = new AtomicInteger(0);

    // ----------------------------------------------------------------
    // 1. TIMEOUT DEMO
    //    @TimeLimiter requires CompletableFuture return type.
    //    If the async task takes > timelimiter.instances.externalApi.timeout-duration (3s),
    //    it throws TimeoutException and the fallback is invoked.
    // ----------------------------------------------------------------

    @TimeLimiter(name = "externalApi", fallbackMethod = "timeoutFallback")
    public CompletableFuture<String> callSlowService(int delaySeconds) {
        log.info("Calling slow service with {}s delay", delaySeconds);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(delaySeconds * 1000L);
                return "Slow service responded after " + delaySeconds + "s";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Request interrupted");
            }
        });
    }

    public CompletableFuture<String> timeoutFallback(int delaySeconds, Throwable t) {
        log.warn("Timeout fallback triggered after {}s delay. Cause: {}", delaySeconds, t.getMessage());
        return CompletableFuture.completedFuture(
                "FALLBACK: Service timed out after 3s (you requested " + delaySeconds + "s delay). " +
                "Returning cached/default response.");
    }

    // ----------------------------------------------------------------
    // 2. CIRCUIT BREAKER DEMO
    //    After 50% failure rate over 10 calls (config), the circuit OPENS.
    //    While open, calls immediately return the fallback without hitting
    //    the downstream service — giving it time to recover.
    //
    //    States: CLOSED → OPEN → HALF_OPEN → CLOSED (or back to OPEN)
    // ----------------------------------------------------------------

    @CircuitBreaker(name = "externalApi", fallbackMethod = "circuitBreakerFallback")
    public String callUnreliableService(boolean forceFailure) {
        log.info("Calling unreliable service (forceFailure={})", forceFailure);

        if (forceFailure) {
            int count = failureCallCount.incrementAndGet();
            throw new RuntimeException("Simulated service failure #" + count);
        }

        failureCallCount.set(0);
        return "Unreliable service responded successfully";
    }

    public String circuitBreakerFallback(boolean forceFailure, Throwable t) {
        log.warn("Circuit breaker fallback triggered. Cause: {}", t.getMessage());
        return "FALLBACK: Downstream service unavailable. " +
               "Circuit breaker state protects your system from cascading failures. " +
               "Error was: " + t.getMessage();
    }

    // ----------------------------------------------------------------
    // 3. RETRY DEMO (no @TimeLimiter here — regular return type)
    //    Retry is applied to transient failures (network blips, 503s).
    //    Config: 3 attempts, 500ms wait, exponential backoff (500ms/1s/2s).
    //
    //    Combined with @CircuitBreaker:
    //    If retries also fail, the CB records those as failures too.
    // ----------------------------------------------------------------

    @Retry(name = "externalApi", fallbackMethod = "retryFallback")
    public String callFlakyService(int attemptNumber) {
        // Succeed only on the 3rd attempt to demonstrate retries
        int currentAttempt = failureCallCount.incrementAndGet();
        log.info("Flaky service attempt #{}", currentAttempt);

        if (currentAttempt < 3) {
            throw new RuntimeException("Flaky service failed on attempt " + currentAttempt);
        }

        failureCallCount.set(0);
        return "Flaky service succeeded on attempt " + currentAttempt;
    }

    public String retryFallback(int attemptNumber, Throwable t) {
        failureCallCount.set(0);
        return "FALLBACK: All retry attempts exhausted. Last error: " + t.getMessage();
    }

    public void resetFailureCount() {
        failureCallCount.set(0);
    }
}
