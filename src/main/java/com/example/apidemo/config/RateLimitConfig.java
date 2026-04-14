package com.example.apidemo.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CONCEPT: Rate Limiting with Token Bucket Algorithm
 *
 * The token bucket algorithm:
 *   - Each bucket holds N tokens
 *   - Tokens refill at a fixed rate
 *   - Each request consumes 1 token
 *   - If no tokens: request is rejected (429 Too Many Requests)
 *
 * WHY RATE LIMIT?
 *   - Protect against DDoS and brute force attacks
 *   - Ensure fair usage across clients
 *   - Prevent runaway clients from overwhelming the server
 *   - Monetize API tiers (free/pro/enterprise limits)
 *
 * PRODUCTION NOTE:
 *   This demo uses an in-memory ConcurrentHashMap (works for a single node).
 *   For multi-node deployments, use:
 *     - bucket4j-redis (distributed buckets in Redis)
 *     - Bucket4j Hazelcast/Infinispan
 *   or move rate limiting to an API gateway (Kong, AWS API Gateway).
 *
 * MEMORY CONCERN:
 *   The IP bucket map grows as new IPs are seen. In production, use a
 *   cache with TTL eviction (Caffeine, Guava) or Redis with key expiry.
 */
@Component
public class RateLimitConfig {

    // Per-IP buckets — keyed by client IP address
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    /**
     * Standard tier: 20 requests per minute per IP.
     * Uses computeIfAbsent for thread-safe bucket creation.
     */
    public Bucket resolveIpBucket(String ipAddress) {
        return ipBuckets.computeIfAbsent(ipAddress, ip -> createStandardBucket());
    }

    /**
     * Standard bucket: 20 requests/minute.
     * Refills greedily (tokens added continuously as they become available).
     */
    public Bucket createStandardBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(20)
                .refillGreedy(20, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Strict bucket: 5 requests/minute.
     * Suitable for sensitive endpoints (login, password reset).
     */
    public Bucket createStrictBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(5)
                .refillGreedy(5, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Tiered bucket: burst + sustained limits.
     *
     * Burst:     10 requests per 10 seconds  (handles traffic spikes)
     * Sustained: 100 requests per hour        (overall throughput cap)
     *
     * BOTH limits must be satisfied — the more restrictive applies.
     * This mimics real-world API tiers where short bursts are allowed
     * but sustained high traffic is throttled.
     */
    public Bucket createTieredBucket() {
        Bandwidth burst = Bandwidth.builder()
                .capacity(10)
                .refillGreedy(10, Duration.ofSeconds(10))
                .build();
        Bandwidth sustained = Bandwidth.builder()
                .capacity(100)
                .refillGreedy(100, Duration.ofHours(1))
                .build();
        return Bucket.builder()
                .addLimit(burst)
                .addLimit(sustained)
                .build();
    }
}
