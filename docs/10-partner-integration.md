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

An API key in a header answers one question: **"Who are you?"**
It does not answer: *"Did you intend to send exactly this request, right now, unmodified?"*
HMAC signing adds that second guarantee. The two work as a pair — neither is sufficient alone.

---

### The Two-Credential System

Partners are provisioned with **two separate credentials** at onboarding:

| | API Key | Signing Secret |
|--|---------|---------------|
| **Purpose** | Identity — "this is Alpha Corp" | Integrity — "Alpha Corp sent this exact request right now" |
| **Sent in every request?** | Yes — in `X-Partner-Key` header | **Never** — stays on disk at both ends, never transmitted |
| **What an attacker gets if stolen** | Can impersonate Alpha Corp | Useless alone — needs the API key too, plus a target request to sign |
| **What it proves** | Caller knows the key | Caller possesses the secret AND computed the signature over this exact content |
| **Rotated independently?** | Yes | Yes — rotating the signing secret does not affect the API key |

> **Key insight:** The signing secret is a *pre-shared secret* — like a passphrase both sides agreed on before the conversation started. Because it is never in a request, an attacker who captures your network traffic cannot obtain it. They can see the API key; they can never see the signing secret.

---

### What HMAC Actually Computes — Step by Step

HMAC stands for **Hash-based Message Authentication Code**. It is a keyed hash — it produces a fingerprint of your message that only someone who knows the secret key can reproduce.

**Step 1 — Build the canonical string** (the exact bytes that will be signed):
```
POST
/api/partner/orders
1704067200
ba7816bf8f01cfea414140de5dae2ec73b00361bbef0469df84c6a2d3a6afbe
```
That last line is `SHA-256(rawBody)`. Each component is on its own newline-separated line.

**Step 2 — Feed it into HMAC-SHA256** using the signing secret as the key:
```
signature = HMAC-SHA256(key=signingSecret, message=canonicalString)
```
Output: a 256-bit (32-byte) value — impossible to predict without the key, even if you know the message.

**Step 3 — Attach to the request:**
```http
X-Partner-Key: partner-alpha-key-12345
X-Timestamp: 1704067200
X-Signature: sha256=a7f3d2b1c9e4f8a2b3c4d5e6f7a8b9c0...
```

**Step 4 — Server recomputes independently:**
The server has its own copy of the signing secret (from DB — never in transit). It builds the same canonical string from the request it received and runs the same HMAC. If the outputs match, the request is authentic and unmodified.

---

### Why Each Component of the Canonical String Matters

| Component | What it locks in | Attack it defeats |
|-----------|-----------------|-------------------|
| `METHOD` | The HTTP verb | Cannot replay a signed GET as a POST to trigger a write |
| `PATH` | The exact endpoint | Cannot reuse a valid signature for `/orders` on `/admin/orders` |
| `TIMESTAMP` | Unix seconds at signing time | **Replay attack** — server rejects `\|now − timestamp\| > 300s`. A captured valid request expires in 5 minutes. |
| `SHA-256(body)` | Cryptographic fingerprint of the full body | **Body tampering** — change one byte (`amount: 99` → `amount: 9999`), the body hash changes, HMAC changes, rejected. |

---

### What Each Attack Steals — And What Stops It

| Attack | What attacker has | What they can do | Why it fails |
|--------|------------------|-----------------|-------------|
| Steal API key only | API key from leaked env var / logs | Try to call the API | Cannot produce `X-Signature` without the signing secret — 401 |
| Intercept a request | Full request with valid API key + signature | Replay the exact request | Timestamp is baked into the signature; after 5 min the server rejects it |
| Modify intercepted request | Full request in transit | Change `amount: 99` to `amount: 9999` | Body change alters SHA-256(body), alters HMAC — server sees mismatch, 401 |
| Steal signing secret only | Signing secret but no API key | Nothing | Server needs both identity (API key) + integrity (signature) |
| Steal both credentials | API key + signing secret | Full impersonation | No defense from HMAC alone — mTLS + IP allowlisting are the next layer |

---

### One Subtle Rule: Constant-Time Comparison

When comparing the expected signature against the received signature, **never use `String.equals()`**.

`String.equals()` returns `false` as soon as it finds the first differing character — meaning it returns *faster* when strings differ at position 0 than at position 30. An attacker can measure this timing difference across thousands of requests and brute-force the correct signature one byte at a time. This is a **timing attack**.

`MessageDigest.isEqual()` always compares all bytes regardless of the first mismatch — it takes the same time whether the strings match at byte 0 or byte 31. This eliminates the timing signal.

```java
// ✗ Vulnerable to timing attack
if (expected.equals(incoming)) { ... }

// ✓ Constant-time — always compare all bytes
if (MessageDigest.isEqual(expected.getBytes(), incoming.getBytes())) { ... }
```

---

### Client-Side Signing (Java)

```java
// Step 1: hash the raw body (before any JSON parsing)
String rawBody = objectMapper.writeValueAsString(orderRequest);
String bodyHash = sha256Hex(rawBody);

// Step 2: build the canonical string
long ts = Instant.now().getEpochSecond();
String canonical = "POST\n"
    + "/api/partner/orders\n"
    + ts + "\n"
    + bodyHash;

// Step 3: compute HMAC-SHA256 with the signing secret
//   signingSecret is NEVER sent in the request — pre-shared at onboarding
String signature = "sha256=" + hmacSha256(signingSecret, canonical);

// Step 4: attach to the request
HttpRequest request = HttpRequest.newBuilder()
    .header("X-Partner-Key",  partnerKey)    // identity
    .header("X-Timestamp",    String.valueOf(ts))
    .header("X-Signature",    signature)      // proof of intent
    .header("Content-Type",   "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(rawBody))
    .build();
```

### Common Mistakes

```
✗ Signing the parsed/re-serialized body instead of rawBody
  → JSON parsers may normalize whitespace or key order → hash differs → 401
  → Always sign the raw bytes you are about to send

✗ Using the API key itself as the signing secret
  → Both credentials are now in every request
  → Attacker who captures the X-Partner-Key header gets both at once
  → Use a separate, independently rotatable signing secret

✗ Not including the body hash for GET requests
  → GET requests have no body, but still include PATH and TIMESTAMP
  → Use SHA-256("") = e3b0c44298fc1c149afb for empty body, or omit body hash component for GETs

✗ Clock drift
  → Servers and clients must be NTP-synchronized
  → A 5-minute window accommodates typical drift; wider windows reduce replay protection
```

---

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
