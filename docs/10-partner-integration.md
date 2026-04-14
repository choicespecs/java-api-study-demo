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

### Authentication
- [ ] Issue per-partner API keys (never shared across partners)
- [ ] Hash keys before storing — never store plaintext
- [ ] Support two active keys per partner (key rotation without downtime)
- [ ] Set up alerting on sustained 401s per partner

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
