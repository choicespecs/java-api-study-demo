# Common REST API Problems & Solutions

Practical issues that come up in real implementations.

---

## 1. CORS Errors

**Symptom:** Browser console: `Access to fetch at '...' from origin '...' has been blocked by CORS policy`

**Why:** Browsers block cross-origin requests by default. The server must opt in.

**Solution:**
```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://myapp.com")); // Be explicit!
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L); // Cache preflight for 1 hour

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

**Common mistake:** Using `allowedOrigins("*")` with `allowCredentials(true)` — browsers reject this combination.

---

## 2. Token Expiry Not Handled

**Symptom:** Users get logged out mid-session; 401 errors after 15 minutes.

**Solution:** Implement silent token refresh:
```javascript
async function apiCall(url) {
    let response = await fetch(url, {
        headers: { Authorization: `Bearer ${getAccessToken()}` }
    });

    if (response.status === 401) {
        // Try to refresh
        const refreshed = await refreshAccessToken();
        if (refreshed) {
            // Retry original request with new token
            response = await fetch(url, {
                headers: { Authorization: `Bearer ${getAccessToken()}` }
            });
        } else {
            // Refresh failed → redirect to login
            redirectToLogin();
        }
    }
    return response;
}
```

---

## 3. Missing Idempotency

**Problem:** Network retries cause duplicate operations (charging a card twice, sending duplicate emails).

**Solution:** Idempotency keys — client generates a unique ID per operation; server deduplicates.

```bash
curl -X POST https://api.payment.com/charges \
  -H "Idempotency-Key: a8098c1a-f86e-11da-bd1a-00112444be1e" \
  -d "amount=100&currency=USD"
```

The server stores the key → if it sees the same key again, returns the original response without re-processing. Used by Stripe, Square, and other payment APIs.

---

## 4. N+1 Query Problem

**Symptom:** Slow API response; DB shows 1 query for the list + N queries for each item's details.

```java
// BAD — 1 query to get products, then 1 query per product for its reviews
List<Product> products = productRepository.findAll();
products.forEach(p -> p.getReviews().size()); // Each triggers a DB query!
```

**Solution:** Eager fetch with JOIN or use batch loading:
```java
// GOOD — single JOIN query
@Query("SELECT p FROM Product p LEFT JOIN FETCH p.reviews")
List<Product> findAllWithReviews();
```

Or use Spring Data projections, DTOs, or Entity Graphs.

---

## 5. Inconsistent Error Responses

**Problem:** Some errors return `{"error": "..."}`, others return `{"message": "..."}`, others return HTML.

**Solution:** Centralized exception handler with consistent format:
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApiException(ApiException ex) {
        // All errors return the same RFC 7807 ProblemDetail structure
    }
}
```

---

## 6. Race Conditions / Lost Updates

**Problem:** Two users update the same record simultaneously — one update is silently lost.

```
User A reads order (version 1)
User B reads order (version 1)
User A updates → version 2
User B updates → version 2 (overwrites User A's change!)
```

**Solution:** Optimistic locking with a version field:
```java
@Entity
public class Order {
    @Version
    private Long version;  // JPA automatically checks and increments
}
```

```bash
# Client must include the version they read
PUT /api/orders/123
{"status": "SHIPPED", "version": 1}

# If version doesn't match current DB version → 409 Conflict
```

---

## 7. Sensitive Data in URLs

**Problem:** Auth tokens or passwords in GET params end up in:
- Browser history
- Server access logs
- CDN/proxy logs
- Referrer headers

```
BAD: GET /api/login?password=secret123
BAD: GET /api/data?apiKey=sk-prod-12345
```

**Solution:**
- Credentials always in headers (`Authorization: Bearer ...`, `X-API-Key: ...`)
- Sensitive data in POST body (not URL params)

---

## 8. Missing Request Validation

**Problem:** Processing malformed input causes unexpected errors or security vulnerabilities.

```java
// BAD — trusting client input directly
productRepository.deleteByCategory(request.getCategory()); // What if category is "../../../etc"?

// GOOD — validate first
@PostMapping("/products")
public ResponseEntity<?> create(@Valid @RequestBody CreateProductRequest request) {
    // @Valid triggers bean validation before method body runs
}

@Data
class CreateProductRequest {
    @NotBlank @Size(max = 100)
    private String name;

    @Positive
    private BigDecimal price;
}
```

---

## 9. Blocking Threads with Synchronous I/O

**Problem:** A slow DB query or external API call blocks a server thread for its entire duration.  
Under load, this exhausts the thread pool.

**Solution:** Either:
1. Use circuit breakers + timeouts (defensive)
2. Use reactive programming (WebFlux + R2DBC) for truly non-blocking I/O
3. Use virtual threads (Java 21) with `spring.threads.virtual.enabled=true`

---

## 10. No Request Tracing

**Problem:** A client reports "request ID 12345 failed at 2:47pm" — you have no way to find the logs.

**Solution:** Assign a correlation ID to every request and log it everywhere:
```java
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) {
        String correlationId = Optional.ofNullable(request.getHeader("X-Correlation-Id"))
                .orElse(UUID.randomUUID().toString());

        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-Id", correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
```

Then every log line automatically includes the `correlationId`. Clients can report this ID when something goes wrong.
