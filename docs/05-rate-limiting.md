# Rate Limiting

Rate limiting controls how many requests a client can make in a time window.

---

## Why Rate Limit?

- **DDoS protection** — prevent a single client from overwhelming the server
- **Brute force prevention** — limit login attempts
- **Fair usage** — prevent one tenant from starving others
- **Cost control** — throttle expensive operations
- **API monetization** — free tier vs paid tier

---

## Token Bucket Algorithm (used in this demo)

Each client has a "bucket" of tokens:
- Bucket starts full (capacity N)
- Each request consumes 1 token
- Tokens refill at a fixed rate
- If bucket is empty → 429 Too Many Requests

```
Capacity: 20 tokens
Refill: 20 tokens per minute

Minute 1: 20 requests → bucket empty
Minute 2: bucket refills → 20 more requests allowed
```

**Burst handling:** The bucket allows a full burst up to capacity immediately, then throttles at the refill rate. Great for APIs where clients batch requests.

---

## Demo Endpoints

```bash
# Standard: 20 req/min per IP
curl http://localhost:8080/api/rate/standard

# Strict: 5 req/min total (simulates login endpoint)
curl http://localhost:8080/api/rate/strict

# Tiered: 10 per 10s (burst) + 100 per hour (sustained)
curl http://localhost:8080/api/rate/tiered

# See 429 in action:
for i in {1..7}; do
  echo "Request $i:"
  curl -s http://localhost:8080/api/rate/strict | jq .
  sleep 0.1
done
```

---

## Rate Limit Response Headers

Communicate limits to clients so they can back off gracefully:

```
HTTP/1.1 200 OK
X-Rate-Limit-Limit: 20
X-Rate-Limit-Remaining: 17
```

On 429:
```
HTTP/1.1 429 Too Many Requests
X-Rate-Limit-Limit: 20
X-Rate-Limit-Remaining: 0
X-Rate-Limit-Retry-After-Seconds: 23
Retry-After: 23
```

The `Retry-After` header is standard (RFC 7231) and tells clients exactly when to retry.

---

## Algorithms Comparison

| Algorithm | Description | Pros | Cons |
|-----------|-------------|------|------|
| **Token Bucket** | Burst allowed, fixed refill rate | Handles traffic spikes | Allows burst |
| **Leaky Bucket** | Fixed output rate, queue excess | Smooth traffic | Drops excess immediately |
| **Fixed Window** | Count resets at each window | Simple | "Edge attack" — 2x requests at window boundary |
| **Sliding Window Log** | Track each request timestamp | Accurate | High memory (one entry per request) |
| **Sliding Window Counter** | Weighted average of two windows | Accurate + efficient | Slightly approximate |

---

## Common Rate Limiting Strategies

### Per-IP
Simple, but clients behind NAT/proxy share one limit.
Good for public APIs without authentication.

### Per-API-Key or Per-User
More accurate — each authenticated client has its own bucket.
Enables tiered pricing.

### Per-Endpoint
Different limits for different endpoints:
- Login: 5/min (brute force protection)
- Read: 1000/min
- Write: 100/min
- Export: 5/hour

### Global
Circuit-breaker style: cap total requests to protect the backend regardless of who's sending.

---

## Production Implementation

**Single node:** In-memory (Bucket4j, Guava RateLimiter) — simple, fast, works for one instance.

**Multi-node:** Must use distributed storage:
```xml
<!-- bucket4j-redis for distributed rate limiting -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
</dependency>
```

**Or delegate to infrastructure:**
- API Gateway (AWS API Gateway, Kong, NGINX)
- Service mesh (Istio, Envoy)

---

## Handling 429 in Clients

Good clients implement exponential backoff with jitter:

```python
import time, random

def call_with_retry(fn, max_attempts=5):
    for attempt in range(max_attempts):
        response = fn()
        if response.status_code != 429:
            return response
        
        retry_after = int(response.headers.get('Retry-After', 2 ** attempt))
        jitter = random.uniform(0, 1)
        time.sleep(retry_after + jitter)
    
    raise Exception("Max retries exceeded")
```

The `Retry-After` header tells clients exactly how long to wait — use it instead of guessing.
