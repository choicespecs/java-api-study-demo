# Third-Party API Integration

## The Fundamental Shift

When your service integrates with external providers (Stripe, GitHub, Twilio, AWS, etc.), the design constraints change fundamentally compared to building a user-facing API.

|  | User-Facing API | Third-Party Integration |
|--|----------------|------------------------|
| Auth direction | Client → your API | You → provider (outbound)<br>Provider → your webhook (inbound) |
| Auth mechanism | JWT / session / API key you issue | HMAC signature verify (inbound)<br>API key / OAuth2 client creds (outbound) |
| Rate limits | You set them | Provider sets them — you must obey |
| Availability | You control your uptime | Provider controls theirs — design for their outages |
| Error format | You standardize | Map their errors to your domain |
| Schema changes | You control rollouts | Provider can change anytime — parse defensively |
| Credentials | Users manage theirs | You manage provider keys as secrets |

---

## Pattern 1: Receiving Webhooks

Providers POST events to your URL when something happens asynchronously (payment succeeded, code pushed, subscription cancelled). Your endpoint must be public but **secure**.

### The Security Problem

Your webhook URL is public. Anyone can POST to it. Without verification, an attacker can:
- Send fake "payment.succeeded" events to unlock features they didn't pay for
- Replay a previously captured legitimate event
- Flood your endpoint with garbage data

### HMAC-SHA256 Signature Verification

Providers sign the request body with a shared secret before sending. You verify the signature on every request.

```
Provider                              Your Server
   |                                       |
   | 1. Build payload: {"event": "..."}    |
   | 2. Compute: HMAC-SHA256(secret, ts.payload)
   | 3. POST /your/webhook                 |
   |    X-Webhook-Timestamp: 1710000000    |
   |    X-Webhook-Signature: sha256=abc123 |
   |    Body: {"event": "payment.succeeded"}
   |                                       |
   |                          4. Recompute HMAC
   |                          5. Compare (constant-time)
   |                          6. Reject if mismatch
   |                          7. Check timestamp freshness
   |←── 200 OK ────────────────────────── |
```

```java
@PostMapping("/webhook")
public ResponseEntity<?> receiveWebhook(
        @RequestHeader("X-Webhook-Signature") String signature,
        @RequestHeader("X-Webhook-Timestamp") String timestamp,
        @RequestBody String rawBody) {

    // 1. Validate timestamp (replay protection: reject events > 5 min old)
    long ts = Long.parseLong(timestamp);
    if (Math.abs(Instant.now().getEpochSecond() - ts) > 300) {
        return ResponseEntity.badRequest().body("Timestamp expired");
    }

    // 2. Recompute expected signature
    String signed = timestamp + "." + rawBody;
    String expected = "sha256=" + hmacSha256(webhookSecret, signed);

    // 3. Constant-time comparison — prevents timing attacks
    // ❌ Never: if (!signature.equals(expected)) { ... }
    // ✓ Always: MessageDigest.isEqual(a.getBytes(), b.getBytes())
    if (!MessageDigest.isEqual(expected.getBytes(), signature.getBytes())) {
        return ResponseEntity.status(401).body("Invalid signature");
    }

    // 4. Idempotency check — providers retry failed deliveries
    String eventId = parseEventId(rawBody);
    if (eventRepository.exists(eventId)) {
        return ResponseEntity.ok("Already processed");
    }

    // 5. Store and ACK immediately — then process async
    eventRepository.save(eventId, rawBody);
    eventQueue.enqueue(eventId);            // process in background
    return ResponseEntity.ok("received");   // ← Must return within ~5s
}
```

**Why include timestamp in the signed payload?** A replay attack captures a legitimate request and resends it later. Without timestamp binding, the signature is still valid. With it, the signature covers the specific moment — replay detection becomes timestamp validation.

**Why constant-time comparison?** A naive `equals()` short-circuits on the first mismatched byte. Measuring response times across thousands of probes lets an attacker recover the expected signature one byte at a time. `MessageDigest.isEqual` always compares all bytes.

### Idempotency

Providers retry failed webhooks (usually 3–10 times with exponential backoff). Design every event handler to be idempotent: processing the same event twice produces the same result with no side effects.

```java
// Bad: blindly process
chargeCustomer(event.customerId, event.amount);

// Good: guard with event ID
if (!processedEvents.contains(event.id)) {
    chargeCustomer(event.customerId, event.amount);
    processedEvents.add(event.id);
}
```

### Respond Quickly, Process Async

Providers time out webhook deliveries (typically 5–30 seconds). If you take longer, they mark delivery as failed and retry — causing duplicates.

```
❌ Bad:
POST /webhook → verify → process payment → send email → update DB → 200 OK (15 seconds)
Provider sees timeout, retries → duplicate charge

✓ Good:
POST /webhook → verify → save raw event to DB → 200 OK (< 1 second)
Background job → process payment → send email → update DB
```

---

## Pattern 2: Outbound API Calls

When your service calls a provider's API, you must handle their failures gracefully.

### Error Mapping

Never expose provider error details to your users:

```
❌ Bad user-facing error:
"Stripe error: No such customer: 'cus_XYZ' on account acct_123 [request-id: req_abc]"

✓ Good user-facing error:
"Payment processing temporarily unavailable. Please try again."
```

Always map provider errors to your domain:

```java
try {
    ProviderResponse r = providerClient.charge(amount, customerId);
    return PaymentResult.success(r.getId());
} catch (ProviderRateLimitException e) {
    log.warn("Provider rate limited us: {}", e.getRetryAfter());
    throw new ServiceUnavailableException("Payment temporarily unavailable");
} catch (ProviderAuthException e) {
    log.error("Provider auth failed — API key issue", e);
    alertOps("Provider API key rejected");
    throw new ServiceUnavailableException("Payment temporarily unavailable");
} catch (ProviderException e) {
    log.error("Provider error: {}", e.getProviderCode(), e);
    throw new PaymentFailedException(mapProviderError(e.getProviderCode()));
}
```

### Handling 429 Too Many Requests

```java
if (response.status() == 429) {
    // ❌ Don't: retry immediately — makes rate limit worse
    // ✓ Do: read Retry-After, wait, then retry with backoff
    int retryAfter = Integer.parseInt(
        response.headers().firstValue("Retry-After").orElse("60"));
    
    // Queue for retry with delay — don't block the current thread
    scheduler.schedule(() -> retryCharge(amount, customerId), retryAfter, SECONDS);
    
    // Return 202 Accepted to your caller — the work will complete eventually
    return ResponseEntity.accepted().body(Map.of(
        "status", "queued",
        "message", "Processing delayed due to high demand"
    ));
}
```

### Defensive Parsing (Schema Drift)

Provider APIs evolve. Fields get renamed, types change, new fields appear. Map to your own domain model at the integration boundary:

```java
// ❌ Bad: reference provider fields throughout your code
payment.stripePaymentIntentId; // breaks when Stripe renames the field
payment.amount_received;       // breaks when they change it to amount_received_minor_units

// ✓ Good: map once at the boundary
PaymentRecord fromStripeResponse(StripePaymentIntent r) {
    return new PaymentRecord(
        r.getId(),                                     // your domain field
        r.getAmountReceived() ?? r.getAmount(),        // defensive: handle old + new field name
        Currency.of(r.getCurrency().toUpperCase()),
        mapStatus(r.getStatus())                       // map their status vocabulary to yours
    );
}
```

---

## Credential Management

### Storage

```
❌ Hardcoded in source:   private static final String KEY = "sk_live_abc123";
❌ In application.yml:    provider.api-key: sk_live_abc123  (committed to git)
❌ In a .env file:        PROVIDER_KEY=sk_live_abc123  (if committed)

✓ Environment variable:   System.getenv("PROVIDER_API_KEY")
✓ Secrets manager:        vaultClient.getSecret("provider/api-key")
✓ Injected at deploy:     Kubernetes secret → env var → app reads env var
```

### Rotation Without Downtime

```
Day 1:  App uses Key A
Day 2:  Create Key B in provider dashboard — do NOT delete Key A yet
Day 3:  Deploy app configured to use Key B
Day 4:  Verify all traffic uses Key B successfully
Day 5:  Revoke Key A
```

If you delete Key A before Key B is live, you have a downtime window. The overlap period is essential.

### Minimum Scope

Create separate API keys per service with only the permissions each service needs:

```
Payment service:  key with  charge, refund  permissions
Reporting service: key with  read-only       permissions
Admin service:     key with  all             permissions — protected separately
```

If one key is compromised, the blast radius is limited.

---

## Webhook Security Checklist

- [ ] Verify HMAC-SHA256 signature on **every** request
- [ ] Use constant-time comparison (`MessageDigest.isEqual`, not `equals`)
- [ ] Include timestamp in signed payload
- [ ] Reject events with timestamps older than 5 minutes
- [ ] Check event ID for duplicates before processing
- [ ] Store raw event immediately, return 200 within 5 seconds
- [ ] Process event asynchronously in a background job or queue
- [ ] Handle out-of-order delivery (events may arrive before dependencies)
- [ ] Expose a manual replay endpoint for recovery and debugging
- [ ] Log event IDs for traceability (never log the full payload if it has PII)

## Outbound Call Checklist

- [ ] Set both `connectTimeout` and `readTimeout` on every HTTP client
- [ ] Map all provider HTTP status codes to your domain errors
- [ ] Map all provider error codes/bodies to user-safe messages
- [ ] Respect `Retry-After` on 429 — never retry immediately
- [ ] Alert ops on 401 — do not silently degrade or retry
- [ ] Circuit break on sustained 5xx from provider
- [ ] Parse responses defensively — use defaults for unknown/missing fields
- [ ] Never expose provider field names, error messages, or IDs to end users
- [ ] Load credentials from environment variables or secrets manager
- [ ] Use separate keys per environment (dev/staging/prod)
