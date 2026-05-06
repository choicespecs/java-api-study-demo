# B2B Partner Integration

## The Difference: Users vs Partners

When you open your API to external companies (ISVs, enterprise clients, platform integrations), the design constraints change fundamentally from serving individual users.

|  | User-Facing API | B2B Partner API |
|--|----------------|-----------------|
| Identity unit | Individual user | Organization / company |
| Auth credential | JWT (per session) | Long-lived partner API key (per org) |
| Rate limits | Per IP or per user | Per partner, tiered by contract |
| Data access | Own records only | Tenant-scoped subset |
| Breaking changes | Notify users, soft-launch | Formal 6–12 month deprecation period |
| Audit trail | Session logs | Immutable, compliance-grade per-partner log |
| Event delivery | Optional webhooks | You push signed events to partner callback URLs |
| Auth failures | Redirect to login | Alert partner's engineering team |

---

## Pattern 1: Partner Authentication

### Key Characteristics

A partner API key identifies an **organization**, not an individual user:

```
User JWT:       "This request is from Alice (ROLE_USER)"
Partner key:    "This request is from Alpha Corp (PREMIUM tier)"
```

Partner keys are:
- Long-lived (months or years, not hours)
- Provisioned offline through a business process, not self-signup
- Associated with a company identity, not a person identity
- Rotated on a schedule or after compromise — never on logout

### Key Design Rules

```
✗ Never store API keys in plaintext — hash them (HMAC-SHA256 of the key)
✓ Support two active keys per partner (overlap window for zero-downtime rotation)
✓ Assign scopes and quota at provisioning time, not per-request
✓ Alert ops on sustained 401s — a partner is misconfigured, not "logged out"
✓ Rate-limit failed auth attempts per source IP to block brute force
```

### Request Pattern

```http
GET /api/partner/catalog
X-Partner-Key: partner-alpha-key-12345
```

Separate from `X-API-Key` (user API keys). Using a distinct header prevents mixing user-facing and partner-facing traffic in logs and routing rules.

### Why API Keys Alone Are Not Enough

An API key in a header proves the caller *knows the key* — it does not prove the request was not tampered with, is not a replay, or came from an authorized source. These risks persist even over HTTPS:

| Risk | What Happens | Mitigation |
|------|-------------|------------|
| **Key leakage** | Partner embeds key in source code or CI/CD logs — compromised without a breach of your system | HMAC signing: knowing the key alone is not enough without the signing secret |
| **Replay attack** | Attacker captures a valid request and resends it unchanged — the API key is still valid | HMAC + timestamp: server rejects requests where `|now − timestamp| > 300s` |
| **Body tampering** | Body is modified in transit; the key header is preserved — the API key covers identity, not payload integrity | HMAC signs the full request body — any modification invalidates the signature |
| **Impersonation** | Any client that learns the key can call your API — the key proves knowledge of a secret, not caller identity | mTLS: client must present a certificate at the TLS handshake level |

Real enterprise integrations layer at least three of the mechanisms described in the sections below.

---

## Pattern 2: Tenant Isolation

Each partner key scopes all data access to that partner's subset. Partners sharing infrastructure must never see each other's data.

### Implementation Approaches

| Level | How | When to use |
|-------|-----|-------------|
| **Row-level** | `WHERE partner_id = ?` on every query | Most common — simple, efficient |
| **Schema-level** | Separate DB schema per partner | Medium compliance: SOC 2 Type II |
| **Database-level** | Separate DB instance per partner | High compliance: HIPAA, PCI-DSS |

```java
// Row-level: every query includes the partner scope
List<Item> items = itemRepo.findAllByPartnerId(partner.getId());

// ❌ Never: queries without tenant filter leak cross-partner data
List<Item> items = itemRepo.findAll(); // returns ALL partners' data
```

The partner key is both the **authentication credential** and the **tenant identifier**.

---

## Pattern 3: Tiered Rate Limits

Partners pay for different tiers; the tier determines their quota:

| Tier | Requests/min | Scopes |
|------|-------------|--------|
| FREE | 100 | `catalog:read` |
| STANDARD | 1,000 | `catalog:read/write`, `orders:read/write` |
| PREMIUM | 10,000 | All scopes including `analytics:read` |

### Rate Limit by Partner ID, Not IP

Partners call from fleets of servers behind NAT gateways — their requests may come from dozens of IP addresses. Rate limiting by IP would unfairly restrict legitimate usage.

```java
// ❌ Wrong: per-IP counting
String bucketKey = request.getRemoteAddr();

// ✓ Correct: per-partner counting
String bucketKey = partner.getId(); // stable, regardless of source IP
```

### Surfacing Quota in Response Headers

Always tell partners how much quota they've used:

```
X-RateLimit-Tier: PREMIUM
X-RateLimit-Limit: 10000
X-RateLimit-Used: 43
X-RateLimit-Remaining: 9957
```

Partners use these headers to throttle themselves and avoid hitting 429.

---

## Pattern 4: API Contract and Versioning

With B2B partners, a breaking API change breaks their **production system** — possibly at 3am without their team noticing for hours. Treat your API surface as a contractual obligation.

### What Counts as Breaking

```
Breaking:
  - Removing a field from a response
  - Renaming a field (price → amount)
  - Changing a field type (number → object)
  - Removing an endpoint
  - Changing authentication scheme

Non-breaking:
  - Adding a new optional field
  - Adding a new endpoint
  - Adding a new optional query parameter
  - Expanding an enum with new values (be careful — partners may handle exhaustively)
```

### Deprecation Lifecycle

```
1. Release /v2 alongside /v1 simultaneously — never take down v1 the same day
2. Add Deprecation: true header to all v1 responses
3. Add Sunset: <date> header — at least 6 months out (enterprise: 12+ months)
4. Add Link header pointing to the successor version
5. Email partners with migration guide
6. On Sunset date: return 410 Gone with migration instructions
```

```http
HTTP/1.1 200 OK
X-API-Version: v1
Deprecation: true
Sunset: Sat, 01 Jan 2026 00:00:00 GMT
Link: </api/v2/items>; rel="successor-version"
```

```java
return ResponseEntity.ok()
    .header("Deprecation", "true")
    .header("Sunset", "Sat, 01 Jan 2026 00:00:00 GMT")
    .header("Link", "</api/v2/items>; rel=\"successor-version\"")
    .body(v1Response);
```

---

## Pattern 5: Outbound Webhooks to Partners

Instead of partners polling your API for status updates, you push events to their registered callback URL. This is the **inverse** of receiving webhooks from an external provider:

```
Receiving (Third-Party APIs):  Provider → signed event → Your webhook endpoint
Dispatching (Partner API):     Your system → signed event → Partner's callback URL
```

### Why Partners Need Webhooks

Without webhooks, partners must poll:
```
Partner polling /api/orders/status every 5 seconds
= 12 requests/min × 100 partners = 1,200 req/min just for polling
```

With webhooks:
```
Your system pushes order.updated exactly when the order changes
= 1 request per event, no wasted polling traffic
```

### Signing Outbound Webhooks

Sign every outbound event with HMAC-SHA256 so partners can verify origin:

```java
String timestamp = String.valueOf(Instant.now().getEpochSecond());
String signedPayload = timestamp + "." + bodyJson;
String signature = "sha256=" + hmacSha256(partnerSecret, signedPayload);

HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(partner.getCallbackUrl()))
    .header("X-Webhook-Signature", signature)
    .header("X-Webhook-Timestamp", timestamp)
    .header("X-Partner-Event-Id", event.getId())
    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
    .build();
```

Use a **per-partner secret** (not a global secret). If one partner's secret is compromised, you rotate only that partner's secret — other partners are unaffected.

### Delivery Reliability

```
❌ Fire-and-forget with no retry
✓ Retry on failure with exponential backoff:
     attempt 1 → immediate
     attempt 2 → 30 seconds
     attempt 3 → 2 minutes
     attempt 4 → 10 minutes
     attempt 5 → 1 hour

✓ Track delivery status: pending → delivered | failed
✓ Expose a manual redeliver endpoint for recovery
✓ Deduplicate by X-Partner-Event-Id (idempotency)
✓ Partners must return 200 within 10 seconds or treat as failed
```

---

## HMAC Request Signing

Partners sign every inbound request with HMAC-SHA256 using a shared signing secret. The server recomputes the expected signature and rejects any mismatch. This is distinct from outbound webhook signing (Pattern 5) — here, *partners sign their requests to you*.

### What Gets Signed

```
Canonical string: METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + SHA256(rawBody)

Example:
POST
/api/partner/orders
1704067200
ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469df84c6a2d3a6afbe
```

### Why Each Component Matters

| Component | Reason |
|-----------|--------|
| `METHOD` | Prevents a signed POST from being replayed as a GET on the same path |
| `PATH` | Prevents cross-endpoint replay (same body, different endpoint) |
| `TIMESTAMP` | Replay protection — server rejects `|now − timestamp| > 300s` |
| `SHA256(body)` | Body tamper detection — any modification to the body changes the hash |

### Server-Side Verification (Java)

```java
@PostMapping("/orders")
public ResponseEntity<?> createOrder(
        @RequestHeader("X-Partner-Key") String partnerKey,
        @RequestHeader("X-Timestamp") long timestamp,
        @RequestHeader("X-Signature") String incomingSignature,
        @RequestBody String rawBody,
        HttpServletRequest request) {

    // 1. Reject stale requests (replay protection)
    long now = Instant.now().getEpochSecond();
    if (Math.abs(now - timestamp) > 300) {
        return ResponseEntity.status(401)
            .body(Map.of("error", "Request expired — timestamp outside 5-minute window"));
    }

    // 2. Resolve partner and rebuild the canonical signed string
    Partner partner = partnerService.resolve(partnerKey);
    String canonical = request.getMethod() + "\n"
        + request.getRequestURI() + "\n"
        + timestamp + "\n"
        + sha256Hex(rawBody);

    // 3. Recompute expected signature
    String expected = "sha256=" + hmacSha256(partner.getSigningSecret(), canonical);

    // 4. Constant-time comparison — never String.equals() (timing attack)
    if (!MessageDigest.isEqual(expected.getBytes(), incomingSignature.getBytes())) {
        return ResponseEntity.status(401)
            .body(Map.of("error", "Invalid signature — request may have been tampered with"));
    }

    return ResponseEntity.status(201).body(orderService.create(partner, rawBody));
}

private String sha256Hex(String data) throws NoSuchAlgorithmException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(
        digest.digest(data.getBytes(StandardCharsets.UTF_8)));
}

private String hmacSha256(String secret, String data) {
    try {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
        throw new RuntimeException(e);
    }
}
```

### Key Rules

```
✓ Use a separate signing secret per partner (not the API key itself)
✓ Reject |now − timestamp| > 300 seconds — 5-minute replay window
✓ Always use MessageDigest.isEqual() — never String.equals() (timing attack)
✓ Sign the raw request body before any JSON parsing (parsing may normalize whitespace)
✓ Include HTTP method and full path in the canonical string (prevents cross-endpoint replay)
✗ Never log the signing secret
```

---

## Mutual TLS (mTLS)

Standard TLS has the server prove its identity to the client. Mutual TLS requires *both* sides to authenticate — the client must present a certificate signed by your CA at the TLS handshake, before a single HTTP byte is processed by your application.

### Standard TLS vs Mutual TLS

| | Standard TLS | Mutual TLS |
|--|-------------|-----------|
| Server cert required | ✓ | ✓ |
| Client cert required | ✗ | ✓ |
| Enforced at | TLS handshake (server only) | TLS handshake (before your code runs) |
| Stops | Eavesdropping | Eavesdropping + unauthorized connections |
| Revocation | Not applicable | CRL / OCSP — immediate, no deploy needed |
| Onboarding cost | None | Certificate issuance + lifecycle management |

### How It Works

```
Partner onboarding:
1. You issue a client certificate signed by your CA (or partner provides a CSR and you sign it)
2. Partner configures their HTTP client to present the cert on every request
3. Your API gateway rejects connections without a valid cert at the TLS layer
4. Gateway injects X-Client-Cert-DN (Distinguished Name) into the upstream request
5. Your application maps the DN to a partner identity — no client self-reporting trusted

Revocation:
  Revoke via CRL/OCSP — effective immediately across all load balancer nodes
  without a code deploy or configuration change
```

### Gateway + Application Configuration

```nginx
# nginx: enforce client cert at TLS layer, inject DN for application use
ssl_client_certificate /etc/ssl/partner-ca.crt;
ssl_verify_client       on;
proxy_set_header X-Client-Cert-DN $ssl_client_s_dn;
```

```java
// Spring: read the DN injected by the gateway
// Never trust a client-supplied X-Client-Cert-DN header — always sourced from gateway
@GetMapping("/orders")
public ResponseEntity<?> getOrders(
        @RequestHeader("X-Client-Cert-DN") String certDn) {
    // certDn: "CN=alpha-corp,O=Alpha Corp,C=US"
    Partner partner = partnerService.resolveByDn(certDn);
    return ResponseEntity.ok(partnerService.getOrders(partner));
}
```

```
Use mTLS when:
  ✓ Partners are enterprises with dedicated IT teams who can manage cert rotation
  ✓ Compliance requires cryptographic proof of client identity (PCI-DSS, HIPAA)
  ✓ Defense-in-depth against stolen API keys is required

Skip mTLS when:
  ✗ Partners are small teams who cannot manage certificate lifecycle
  ✗ Early-stage product — mTLS adds real onboarding friction
  ✗ HMAC request signing already meets your threat model
```

---

## IP Allowlisting

Partners register their egress IP ranges at onboarding. Requests from non-allowlisted IPs are rejected at the load balancer — before reaching your application.

```
Onboarding flow:
  Partner provides: "Alpha Corp will call from 203.0.113.0/24 and 198.51.100.45"
  You configure:    allowlist those CIDRs for partner-alpha-key-12345 at the load balancer
  Effect:           Any request from an unregistered IP is dropped with 403 before auth is attempted

Rules:
  ✓ Reject at the edge (load balancer / API gateway), not in application code
  ✓ Require partners to use static egress IPs (NAT gateway), not developer workstation IPs
  ✓ Treat IP range updates as a change request — partner contacts you, you approve; never self-service
  ✓ Combine with API key + HMAC: a stolen key from an unregistered IP is blocked before authentication
  ✗ Does not protect against an attacker who has compromised a server inside the partner's allowlisted range
```

---

## Defense in Depth

No single layer is sufficient. Real enterprise B2B integrations implement at least three:

| Layer | Mechanism | What It Prevents |
|-------|-----------|-----------------|
| **Network** | IP allowlisting (load balancer) | Connections from unknown sources |
| **Transport** | TLS 1.3 + mTLS client certificate | Eavesdropping, unauthorized TLS connections |
| **Request integrity** | HMAC signing + 5-minute timestamp window | Replay attacks, request body tampering |
| **Identity** | Partner API key (HMAC-SHA256 hashed in DB) | Unauthorized API access |
| **Authorization** | Scopes + tenant isolation | Cross-partner data leakage, privilege escalation |
| **Audit** | Immutable append-only logs | Undetected misuse, compliance gaps |

```
Minimum viable B2B security:  TLS + API key + HMAC request signing + audit logs
Full enterprise security:     TLS + mTLS + IP allowlist + HMAC signing + API key + audit logs
```

---

## Audit Logging

Enterprise partners require an immutable audit trail of all API activity for compliance.

### What to Log

| Field | Reason |
|-------|--------|
| `timestamp` | Exact time of request |
| `partner_id` | Which organization made the call |
| `method` | HTTP verb |
| `endpoint` | Path called |
| `status` | Response code |
| `request_id` | Correlation with your internal logs |

**Never log:**
- Request bodies (may contain PII, payment data)
- Authorization headers (contains the API key)
- Partner data payloads

### Design Rules

```
✓ Append-only — never update or delete audit entries
✓ Retain per regulatory requirements (1–7 years typically)
✓ Scope to partner — Alpha Corp cannot see Beta's audit log
✓ Use for billing if you have usage-based pricing
✓ Surface to partners via a scoped audit API endpoint
```

---

## Partner Onboarding Checklist

### Authentication & Request Integrity
- [ ] Issue per-partner API keys (never shared across partners)
- [ ] Hash keys before storing — never store plaintext (HMAC-SHA256 of the key)
- [ ] Issue a separate HMAC signing secret per partner (not the same as the API key)
- [ ] Support two active keys per partner (key rotation without downtime)
- [ ] Enforce 5-minute timestamp window on all HMAC-signed requests (replay protection)
- [ ] Always verify HMAC with `MessageDigest.isEqual()` — never `String.equals()` (timing attack)
- [ ] Set up alerting on sustained 401s per partner

### Transport & Network
- [ ] Enforce TLS 1.2+ on all endpoints (prefer TLS 1.3)
- [ ] mTLS for high-compliance partners (HIPAA, PCI-DSS)
- [ ] Collect partner egress IP ranges at onboarding
- [ ] Enforce IP allowlist at the load balancer layer, not in application code
- [ ] Treat IP range updates as approved change requests, not self-service

### Data Access
- [ ] Enforce tenant isolation on every DB query (`WHERE partner_id = ?`)
- [ ] Assign scopes at provisioning time, not per-request
- [ ] Provide a sandbox environment with realistic test data
- [ ] Use separate keys for sandbox and production

### Rate Limits
- [ ] Rate limit by partner ID, not IP address
- [ ] Return `X-RateLimit-Limit/Used/Remaining` on every response
- [ ] Define tier quotas in contract SLA

### API Contracts
- [ ] Version your API from day one (v1/v2, not unversioned)
- [ ] Add `Deprecation` header when a version enters sunset period
- [ ] Add `Sunset` header with the retirement date
- [ ] Give at least 6 months notice; 12 months for enterprise partners

### Webhooks
- [ ] Sign every outbound event with HMAC-SHA256 (per-partner secret)
- [ ] Include `X-Partner-Event-Id` for idempotency
- [ ] Retry with exponential backoff on failure
- [ ] Track delivery status per event
- [ ] Expose a manual redeliver endpoint

### Audit
- [ ] Log all partner API calls (method, endpoint, status, timestamp)
- [ ] Make audit logs append-only and tamper-evident
- [ ] Scope audit access to the calling partner
- [ ] Retain per regulatory requirements
