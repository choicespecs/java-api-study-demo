# API Versioning

## Why Versioning Exists

When you change field names, types, or remove endpoints, existing clients that were built against the old shape break silently or loudly. Versioning lets you introduce breaking changes under a new API version while old clients continue working unchanged.

---

## The Four Strategies

| Strategy | Example | Pros | Cons | Used By |
|----------|---------|------|------|---------|
| **URI Path** | `/api/v2/items` | Visible, cacheable, easy to test and share | Version embedded in URI violates REST semantics | Stripe, Twilio, Twitter |
| **Query Parameter** | `?version=2` | Backward-compatible default (param is optional) | Easy to omit; cache-key complications | Google (some APIs) |
| **Request Header** | `X-API-Version: 2` | Clean URLs; version treated as metadata | Not visible in browser; must add to `Vary` header for caching | GitHub v3, Microsoft |
| **Accept Header** | `Accept: application/vnd.co.v2+json` | Most RESTful; proper HTTP content negotiation | Complex to implement; hard to test in a browser | GitHub (media type) |

**In practice:** URI path versioning wins on discoverability and simplicity. Use Accept header versioning only if REST purity is a hard requirement. Most teams choose URI and never look back.

---

## Breaking vs. Non-Breaking Changes

### Non-Breaking (no new version needed)
- Adding a new optional response field
- Adding a new endpoint or HTTP method
- Adding a new optional query parameter
- Making a required request field optional
- Adding a new enum value *(see caveat)*
- Increasing rate limits or quotas
- Improving response time, reliability, or error messages

### Breaking (require a new version)
- Renaming or removing a response field
- Changing a field's type (`number` → `object`, `string` → `array`)
- Removing an endpoint or HTTP method
- Making an optional field required
- Changing the authentication scheme
- Changing the error response format
- A bug fix that changes behavior clients rely on
- Narrowing accepted enum or input values

### Enum Caveat

Adding a new enum value looks additive, but clients using exhaustive switch/match break when they encounter an unrecognized value:

```java
// Client code that breaks on new enum value:
switch (status) {
    case PENDING: ...; break;
    case ACTIVE:  ...; break;
    // If the API adds SUSPENDED, this throws
    default: throw new RuntimeException("Unknown status: " + status);
}
```

Flag new enum values in release notes. Document an `UNKNOWN` catch-all convention and recommend clients always implement a safe default branch.

---

## The Hard Question: What If the Change Only Affects Some Users?

A change breaks 20% of callers; 80% still work on the old behavior. Do you version? Do you force migration? The right answer depends on *who* those callers are — not how many.

| Who Is Still on Legacy? | Recommended Approach | Why |
|------------------------|---------------------|-----|
| **B2B / partner clients** | Version it. 6–12 month sunset window. Contact holdouts individually near deadline. | Partners have production integrations you cannot touch. You cannot deploy a change on their behalf overnight. |
| **Mobile app users (old versions)** | Version it, possibly indefinitely. Old app store versions persist for years. | You cannot force an app update. Breaking old clients causes silent crashes in the wild with no recovery path. |
| **Internal services you own** | Coordinated deploy — no versioning needed. Migrate caller and service together. | You control both sides. Adding a version number to internal APIs adds overhead with little benefit. |
| **Public third-party developers** | Version it. Minimum 6 months notice with migration guide and code samples. | Unknown count of callers. You cannot enumerate them. Some will be slow to migrate even with ample notice. |

---

## When to Force Migration vs. Keep Legacy Alive

| Reason for Change | Force Migration? | Timeline |
|------------------|-----------------|---------|
| **Security vulnerability in old version** | Yes — immediately | Communicate now; give days to weeks, not months |
| **GDPR / legal compliance requirement** | Yes — with legal deadline | As short as technically feasible; the legal deadline is hard |
| **Infrastructure cost reduction** | Yes — by Sunset date | Standard 6–12 months; announce early |
| **Performance or reliability improvement** | No | Encourage opt-in via migration guide; never force pure improvements |
| **Developer experience improvement** | No | Keep old version; new clients adopt naturally over time |
| **Bug fix changing relied-upon behavior** | Version it | Check adoption data; coordinate with known callers first |

---

## The Sunset Lifecycle (Step by Step)

```
1. Release /v2 alongside /v1 simultaneously
   → Never remove v1 the same day v2 ships

2. Add deprecation headers to every v1 response immediately:
   Deprecation: true
   Sunset: Sat, 01 Jul 2026 00:00:00 GMT
   Link: </api/v2/items>; rel="successor-version"
   X-API-Version: v1

3. Publish migration guide
   → Changelog, developer portal, email
   → Include working code examples for the new version

4. 60-day countdown
   → Email all clients still calling v1 (use version tracking logs)

5. 30-day countdown
   → Email again
   → Escalate to account manager for enterprise clients
   → Offer live migration office hours

6. Sunset date: return 410 Gone
   → Include migration link in the response body
   → Never return 404 — it hides the reason
   HTTP/1.1 410 Gone
   Content-Type: application/problem+json

   {
     "type": "/errors/gone",
     "title": "Gone",
     "status": 410,
     "detail": "API v1 was retired on 2026-07-01. Migrate to /api/v2/items.",
     "migrationGuide": "https://docs.example.com/migration/v1-to-v2"
   }

7. Post-sunset monitoring
   → Watch for clients still hitting the retired endpoint
   → They need follow-up support, not just a 410
```

Minimum notice: **6 months** for external/public APIs. **12 months** for enterprise B2B partners. Security and legal exceptions may shorten this — communicate why and offer direct migration support.

---

## Monitor Version Usage Before You Sunset

Never sunset blind. Before removing any version you must know who is still calling it — and that they have migrated or have a confirmed migration plan.

### What to Track Per Request

| Field | Why |
|-------|-----|
| `api_version` | Which version the client called |
| `client_id` | Which partner or application made the call |
| `last_seen` | Timestamp of most recent v1 call for this client |
| `endpoint` | Which endpoint was called |

### Dashboard Signals

- **Active v1 callers** — distinct client IDs calling v1 in the last 30 days
- **V1 call share** — v1 calls as a % of total traffic; watch for natural decline as clients migrate
- **Holdout list** — clients still on v1 within 30 days of the Sunset date

If v1 traffic is zero for 60 consecutive days, you can sunset with high confidence. If any known partner is still active, contact them before removing the endpoint.

### Spring Boot: Logging the API Version

```java
// Log the called version on every request for monitoring
@GetMapping("/v1/items")
public ResponseEntity<?> v1Items(HttpServletRequest request) {
    log.info("api_version=v1 client_id={} endpoint={} method={}",
        request.getHeader("X-Partner-Key"),
        request.getRequestURI(),
        request.getMethod());

    // Return v1 response with deprecation headers
    return ResponseEntity.ok()
        .header("Deprecation", "true")
        .header("Sunset", "Sat, 01 Jul 2026 00:00:00 GMT")
        .header("Link", "</api/v2/items>; rel=\"successor-version\"")
        .header("X-API-Version", "v1")
        .body(v1Response);
}
```

---

## The Version Coexistence Cost

Every live version you maintain multiplies your maintenance surface. Before accepting a new version, understand the full cost:

| Cost | What It Means in Practice |
|------|--------------------------|
| **Duplicated business logic** | Bug fixes must be applied to every live version — or accepted as intentional version divergence |
| **Test multiplication** | N versions = N integration test suites; flakiness and coverage gaps compound |
| **Documentation debt** | Every doc page must describe behavior across all supported versions |
| **Infrastructure lock-in** | Old versions may pin old runtimes, frameworks, or services you would otherwise retire |
| **Security exposure** | Old versions may lack security hardening added later; each is a separate attack surface |

**Rule of thumb:** Support at most 2 major versions simultaneously. When v3 ships, v1 must have a firm Sunset date.

---

## Spring Boot Implementation Reference

### Adding Deprecation Headers (V1)

```java
return ResponseEntity.ok()
    .header("Deprecation", "true")
    .header("Sunset", "Sat, 01 Jul 2026 00:00:00 GMT")
    .header("Link", "</api/v2/items>; rel=\"successor-version\"")
    .header("X-API-Version", "v1")
    .body(responseBody);
```

### Returning 410 Gone (Post-Sunset)

```java
@GetMapping("/v0/items")
public ResponseEntity<Map<String, Object>> v0Items() {
    return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
        "type",          "/errors/gone",
        "title",         "Gone",
        "status",        410,
        "detail",        "API v0 was retired on 2025-01-01. Migrate to /api/v2/items.",
        "migrationGuide", "https://docs.example.com/migration/v0-to-v2"
    ));
}
```

### Content Negotiation (Accept Header Strategy)

```java
// Spring maps Accept: application/vnd.demo.v1+json → this method
@GetMapping(value = "/api/items", produces = "application/vnd.demo.v1+json")
public ResponseEntity<?> getItemsV1() { ... }

// Spring maps Accept: application/vnd.demo.v2+json → this method
@GetMapping(value = "/api/items", produces = "application/vnd.demo.v2+json")
public ResponseEntity<?> getItemsV2() { ... }
```

---

## Hard Questions & Answers

**Q: A bug fix changes behavior that some clients depend on. Is that a breaking change?**
A: Yes. If clients rely on the buggy behavior as a feature, it is a behavioral breaking change regardless of intent. Version it — keep the old behavior in v1, ship the fix in v2. Communicate what the correct behavior is and why the old behavior was wrong.

---

**Q: We must remove a field for GDPR compliance. Must we give the full 6-month notice?**
A: No. Legal and security requirements override the standard deprecation window. Communicate immediately, explain the legal obligation, and give the shortest timeline that is technically feasible — typically 30–90 days. Offer direct migration support to all known callers.

---

**Q: Only 5 partners still use V1. Can we sunset it?**
A: Only after individually notifying those 5 partners, getting their acknowledgment of the migration deadline, and confirming at least some have already migrated. Never sunset based on low traffic alone — "low traffic" may be a partner's nightly batch job that runs once at 3am and would be silently broken by a 410.

---

**Q: Should every microservice version independently?**
A: Yes. Service A at v2 and service B at v3 is completely normal. An API gateway can present a unified versioned surface to external clients while services evolve independently. Trying to keep all services on the same version number creates false coupling and slows every team to the pace of the slowest service.

---

**Q: Can I add required fields in v2 without affecting v1 clients?**
A: Yes. V1 and V2 are separate code paths. V2 can have fields — including required ones — that did not exist in V1. V1 clients call `/v1/` and never encounter the v2 schema.

---

**Q: A new enum value is non-breaking, right?**
A: Not always. Clients using exhaustive switch/match break when they encounter an unrecognized value. Flag new enum values in release notes. Recommend clients always implement a safe default branch.

---

**Q: The change breaks 10% of users. Do I have to version it for the other 90%?**
A: The percentage is the wrong metric. The question is *who* the 10% are. B2B partners with production integrations → version and coordinate with them individually. Internal services → coordinated deploy, no versioning. Mobile app users → you cannot force an update; version or use feature flags. Unknown public developers → version, because you cannot reach them to coordinate.
