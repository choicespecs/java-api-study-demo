package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CONCEPT: Designing Your REST API for Third-Party Partner Integration (B2B)
 *
 * When you open your API to external companies (partners, ISVs, enterprise clients),
 * the design constraints differ fundamentally from serving your own end users.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │                    User-Facing API              B2B Partner API              │
 * ├──────────────────────────────────────────────────────────────────────────────┤
 * │  Identity unit     Individual user              Organization / company       │
 * │  Auth credential   JWT (per login session)      Long-lived key (per org)     │
 * │  Rate limits       Per IP / per user            Per partner (tiered quotas)  │
 * │  Data access       Own data only                Tenant-scoped subset         │
 * │  Contract          Implicit UX expectations     Explicit SLA + versioning    │
 * │  Breaking changes  Notify users, soft-launch    Formal deprecation period    │
 * │  Event delivery    Optional webhooks            You push signed events        │
 * │  Audit trail       Session logs                 Immutable per-partner log    │
 * │  Auth failures     Redirect to login page       Alert partner's ops team     │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * FIVE KEY PATTERNS DEMOED:
 *
 *   1. PARTNER AUTHENTICATION  — X-Partner-Key identifies the ORGANIZATION.
 *      Unlike user JWT (identifies a person for one session), a partner key
 *      identifies a company and stays valid for months or years.
 *      Partners are provisioned offline through a business process.
 *
 *   2. TENANT ISOLATION  — each partner key scopes access to its own data subset.
 *      Alpha Corp cannot see Beta Inc's records even though they share the same
 *      infrastructure and hit the same endpoint.
 *
 *   3. TIERED RATE LIMITS  — quota differs by partner contract tier.
 *      FREE: 100 req/min  |  STANDARD: 1,000 req/min  |  PREMIUM: 10,000 req/min.
 *      Surfaced in X-RateLimit-* response headers so partners can self-throttle.
 *
 *   4. API CONTRACT OBLIGATIONS  — breaking changes require formal notice.
 *      Deprecation header warns; Sunset header gives the retirement date.
 *      Partners need 6–12 months to update their integration — not days.
 *
 *   5. OUTBOUND WEBHOOKS  — you PUSH signed events to the partner's callback URL.
 *      This is the INVERSE of the Third-Party APIs section:
 *        Third-Party APIs: provider → your endpoint (you verify their signature)
 *        Partner API:      your system → partner's endpoint (they verify yours)
 */
@RestController
@RequestMapping("/api/partner")
@Tag(name = "13. Partner Integration", description = "B2B API design: partner auth, tenant isolation, tiered limits, versioning contracts, outbound webhooks")
public class PartnerApiController {

    // ────────────────────────────────────────────────────────────
    // DATA MODEL
    // ────────────────────────────────────────────────────────────

    /**
     * A record representing one registered B2B partner.
     *
     * In production this lives in a database table:
     *   CREATE TABLE partners (
     *     id           VARCHAR PRIMARY KEY,
     *     name         VARCHAR NOT NULL,
     *     key_hash     VARCHAR NOT NULL,  -- HMAC of the API key; never store plaintext
     *     tier         VARCHAR NOT NULL,
     *     scopes       TEXT[],
     *     callback_url VARCHAR,
     *     active       BOOLEAN DEFAULT TRUE
     *   );
     *
     * Two active keys per partner (key_hash_primary, key_hash_secondary) supports
     * zero-downtime rotation: activate secondary → migrate traffic → revoke primary.
     */
    record Partner(String id, String name, Tier tier, List<String> scopes, String callbackUrl) {}

    /**
     * Contract tier — determines quota and accessible scopes.
     * Ordered so FREE < STANDARD < PREMIUM comparisons work naturally.
     */
    enum Tier { FREE, STANDARD, PREMIUM }

    // ── In-memory partner registry (production: DB lookup + cache) ──
    //
    // Keys are the raw API key values the partner sends in X-Partner-Key.
    // In production: store HMAC-SHA256(key) in DB, never the plaintext key.
    // Lookup: hash the incoming key, then query WHERE key_hash = ?
    //
    private static final Map<String, Partner> PARTNERS = Map.of(
            // PREMIUM: full access — all scopes, highest quota
            "partner-alpha-key-12345", new Partner(
                    "alpha", "Alpha Corp", Tier.PREMIUM,
                    List.of("catalog:read", "catalog:write", "orders:read", "orders:write", "analytics:read"),
                    "http://localhost:8080/api/partner/callback-echo"  // demo: self-callback
            ),
            // STANDARD: read/write for catalog and orders, no analytics
            "partner-beta-key-12345", new Partner(
                    "beta", "Beta Inc", Tier.STANDARD,
                    List.of("catalog:read", "orders:read", "orders:write"),
                    "http://localhost:8080/api/partner/callback-echo"
            ),
            // FREE: catalog read-only, lowest quota — no write, no analytics
            "partner-gamma-key-12345", new Partner(
                    "gamma", "Gamma LLC", Tier.FREE,
                    List.of("catalog:read"),
                    "http://localhost:8080/api/partner/callback-echo"
            )
    );

    // ── Quota limits per tier (requests per minute) ──────────────
    //
    // Enforced via separate per-partner counters — NOT per IP address.
    // Partners may call from a fleet of servers behind a single NAT gateway,
    // so their traffic appears to come from one IP even when it's 50 machines.
    //
    private static final Map<Tier, Integer> TIER_QUOTA = Map.of(
            Tier.FREE,     100,    // free tier — small enough to prevent DoS abuse
            Tier.STANDARD, 1_000,  // paid tier — sufficient for typical integrations
            Tier.PREMIUM,  10_000  // enterprise tier — matches their contractual SLA
    );

    // ── Per-partner request counter (production: Redis with 60s TTL) ─
    //
    // ConcurrentHashMap is thread-safe for concurrent requests.
    // merge(key, 1, Integer::sum) atomically increments without explicit locking.
    // In production, Redis INCR + EXPIRE gives an automatic sliding window.
    //
    private final Map<String, Integer> requestCounts = new ConcurrentHashMap<>();

    // ── Per-partner audit log ─────────────────────────────────────
    //
    // CopyOnWriteArrayList: thread-safe reads without locking (reads >> writes).
    // Outer map keyed by partner ID — logs are never shared across tenants.
    // Production: append-only DB table with DB-level constraints preventing UPDATE/DELETE.
    //
    private final Map<String, List<Map<String, Object>>> auditLogs = new ConcurrentHashMap<>();

    // ── Simulated tenant-scoped catalog data ──────────────────────
    //
    // Keyed by partner ID. In production this is enforced at the DB layer:
    //   SELECT * FROM catalog_items WHERE partner_id = ?
    //
    // Note item IDs are prefixed by partner (A001, B001, G001) — in real
    // multi-tenant systems this prefix is often omitted and the tenant filter
    // in the WHERE clause is the only isolation mechanism. Relying on naming
    // alone is NOT a security control.
    //
    private static final Map<String, List<Map<String, Object>>> TENANT_CATALOG = Map.of(
            "alpha", List.of(
                    Map.of("id", "A001", "name", "Enterprise Widget",       "price", 499.99, "available", true),
                    Map.of("id", "A002", "name", "Pro Dashboard License",   "price", 299.99, "available", true),
                    Map.of("id", "A003", "name", "Advanced Analytics Pack", "price", 199.99, "available", false)
            ),
            "beta", List.of(
                    Map.of("id", "B001", "name", "Starter Plan", "price", 49.99,  "available", true),
                    Map.of("id", "B002", "name", "Growth Plan",  "price", 99.99,  "available", true)
            ),
            "gamma", List.of(
                    Map.of("id", "G001", "name", "Community Edition", "price", 0.0, "available", true)
            )
    );

    // ── Outbound webhook signing secret ───────────────────────────
    //
    // In production: use a DIFFERENT secret per partner.
    // If one partner's secret leaks, you rotate only that partner's secret;
    // all other partners remain secure.
    //
    // Storage: environment variable or secrets manager (Vault, AWS Secrets Manager).
    // Never hardcode production secrets — this is a demo-only placeholder.
    //
    private static final String WEBHOOK_SIGNING_SECRET = "partner_signing_secret_demo"; // DEMO ONLY

    // ────────────────────────────────────────────────────────────
    // SHARED HELPERS
    // ────────────────────────────────────────────────────────────

    /**
     * Resolves X-Partner-Key to a Partner record.
     * Returns null if the key is missing, blank, or unrecognized.
     *
     * Callers must check for null and return 401 before proceeding.
     *
     * Production hardening:
     *   - Hash the incoming key (HMAC-SHA256) and compare against stored hash
     *   - Track failed lookups per source IP and rate-limit after N misses
     *   - Add a short cache (e.g. 60s) to avoid DB hits on every request
     *   - Check an `active` flag so revoked keys fail immediately
     */
    private Partner resolvePartner(String partnerKey) {
        // Reject missing or blank key before map lookup
        if (partnerKey == null || partnerKey.isBlank()) return null;
        // In production: hash(partnerKey) → DB lookup → Partner object
        return PARTNERS.get(partnerKey);
    }

    /**
     * 401 Unauthorized — key missing or unrecognized.
     *
     * 401 means "I don't know who you are."
     * Distinct from 403 which means "I know who you are, but you can't do this."
     */
    private ResponseEntity<Map<String, Object>> unauthorized(String detail) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", "INVALID_PARTNER_KEY",
                "detail", detail,
                "lesson", "Partner keys identify organizations, not users. They are long-lived " +
                           "and provisioned offline. Always send X-Partner-Key on every request."
        ));
    }

    /**
     * 403 Forbidden — partner is authenticated but their tier lacks the required scope.
     *
     * Do NOT return 404 here to hide the endpoint's existence — that pattern
     * makes debugging extremely painful for partners and provides no real security.
     * Return 403 with a clear explanation of which scope is needed.
     */
    private ResponseEntity<Map<String, Object>> forbidden(String scope) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "INSUFFICIENT_SCOPE",
                "detail", "Your partner tier does not include the '" + scope + "' scope",
                "lesson", "Assign scopes at provisioning time based on tier. " +
                           "Free tier gets read-only; paid tiers unlock write and analytics. " +
                           "Document which scopes each tier includes in your partner portal."
        ));
    }

    /**
     * Appends one entry to the partner's immutable audit log.
     *
     * WHY THIS IS CALLED ON EVERY ENDPOINT:
     *   Enterprise and compliance-regulated partners require a full audit trail.
     *   Without it you cannot answer: "Did your API receive our request on Tuesday?"
     *
     * WHAT TO LOG vs WHAT NOT TO LOG:
     *   Log:    partner_id, method, endpoint, status, timestamp, request_id
     *   Do NOT: request body (may contain PII), Authorization header (contains the key)
     *
     * The list is prepended (index 0) so the most recent entry is always first —
     * this avoids reversing the list on every read.
     */
    private void audit(String partnerId, String method, String endpoint, int status) {
        // computeIfAbsent is atomic — safe under concurrent requests
        auditLogs.computeIfAbsent(partnerId, k -> new CopyOnWriteArrayList<>())
                .add(0, Map.of(        // prepend: newest entry first
                        "timestamp", Instant.now().toString(),
                        "partnerId", partnerId,
                        "method",    method,
                        "endpoint",  endpoint,
                        "status",    status
                ));

        // Cap log size in the demo to avoid unbounded memory growth.
        // In production: logs persist to DB forever (or until regulatory retention limit).
        List<Map<String, Object>> log = auditLogs.get(partnerId);
        if (log.size() > 50) log.subList(50, log.size()).clear();
    }

    /**
     * Builds the standard X-RateLimit-* response headers for this partner's tier.
     *
     * These headers let partners self-throttle before hitting 429:
     *   X-RateLimit-Tier      — partner's contract tier name
     *   X-RateLimit-Limit     — total allowed requests per window
     *   X-RateLimit-Used      — how many have been consumed in this window
     *   X-RateLimit-Remaining — how many are left (never goes below 0)
     *
     * Partners implement client-side throttling by watching Remaining and
     * slowing down when it approaches zero, before the server starts returning 429.
     */
    private Map<String, String> quotaHeaders(Partner partner, int used) {
        int quota = TIER_QUOTA.get(partner.tier());
        return Map.of(
                "X-RateLimit-Tier",      partner.tier().name(),
                "X-RateLimit-Limit",     String.valueOf(quota),
                "X-RateLimit-Used",      String.valueOf(used),
                "X-RateLimit-Remaining", String.valueOf(Math.max(0, quota - used))
        );
    }

    // ────────────────────────────────────────────────────────────
    // 1. PARTNER AUTHENTICATION INFO
    // ────────────────────────────────────────────────────────────

    /**
     * Returns the resolved partner's identity, tier, and granted scopes.
     *
     * CONCEPT: A partner key identifies an ORGANIZATION, not a person.
     *
     *   User JWT:   "Alice (USER role) is making this request"
     *   Partner key: "Alpha Corp's integration is making this request"
     *
     * One organization may have many engineers all calling the API using the same
     * partner key. There is no individual user identity attached — only the org.
     *
     * This is intentional: the access rights belong to the business relationship
     * (the contract), not to any particular employee of the partner company.
     */
    @GetMapping("/auth-info")
    @Operation(
            summary = "Return partner identity and tier",
            description = "Resolves X-Partner-Key to an organization. Try: partner-alpha-key-12345, partner-beta-key-12345, partner-gamma-key-12345"
    )
    public ResponseEntity<Map<String, Object>> authInfo(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        // ── Step 1: Authenticate ────────────────────────────────
        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        // ── Step 2: Audit every call, including auth-info ───────
        // Even "info" endpoints are audited — partners expect a complete trail.
        audit(partner.id(), "GET", "/api/partner/auth-info", 200);

        // ── Step 3: Increment quota counter ─────────────────────
        // merge(key, 1, Integer::sum) is equivalent to:
        //   counts[partner.id] = (counts[partner.id] ?? 0) + 1
        // but is atomic (no race condition under concurrent requests).
        int used = requestCounts.merge(partner.id(), 1, Integer::sum);

        // ── Step 4: Build X-RateLimit-* headers ─────────────────
        Map<String, String> rateHeaders = quotaHeaders(partner, used);

        // ── Step 5: Build response body ──────────────────────────
        // LinkedHashMap preserves insertion order for readable JSON output.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId",   partner.id());
        body.put("partnerName", partner.name());
        body.put("tier",        partner.tier().name());
        body.put("scopes",      partner.scopes());
        body.put("quota", Map.of(
                "tier",              partner.tier().name(),
                "requestsPerMinute", TIER_QUOTA.get(partner.tier()),
                "description", switch (partner.tier()) {
                    case FREE     -> "Read-only catalog access; 100 req/min";
                    case STANDARD -> "Catalog + orders read/write; 1,000 req/min";
                    case PREMIUM  -> "Full access including analytics; 10,000 req/min";
                }
        ));
        body.put("lesson", "Partner keys are organization-scoped. Tier determines quota and scope. " +
                            "Unlike user JWT (per session), partner keys are long-lived — " +
                            "rotation requires an overlap window so the partner never has downtime.");

        // ── Step 6: Apply rate limit headers and return ──────────
        // .header() must be called before .body() — BodyBuilder is a builder pattern.
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        rateHeaders.forEach(builder::header);
        return builder.body(body);
    }

    // ────────────────────────────────────────────────────────────
    // 2. TENANT-ISOLATED CATALOG
    // ────────────────────────────────────────────────────────────

    /**
     * Returns the partner's tenant-scoped catalog items.
     *
     * CONCEPT: Tenant isolation.
     *   Multiple partners share the same infrastructure and hit the same URL,
     *   but each sees only their own data. The partner key doubles as a
     *   tenant identifier — it is both the authentication credential AND the
     *   data-scope selector.
     *
     * ISOLATION LEVELS (strongest to weakest):
     *
     *   Database-level: Separate DB instance per partner
     *     → Required for HIPAA BAA, PCI-DSS level 1, some government contracts
     *     → Expensive; only justified by contract or compliance obligation
     *
     *   Schema-level: Separate DB schema per partner
     *     → Satisfies most SOC 2 Type II audits
     *     → Moderate overhead; schemas can be spun up via migrations
     *
     *   Row-level: WHERE partner_id = ? on every query  ← most common
     *     → Sufficient for the majority of B2B SaaS products
     *     → Risk: a missing WHERE clause leaks cross-tenant data
     *       Mitigation: use a query interceptor or repository base class
     *       that automatically appends the filter, so devs can't forget it
     *
     * COMMON MISTAKE: adding tenant filtering in the service layer but not
     * at the DB layer. A developer who calls the repository directly (e.g.
     * in a background job) then bypasses the service-layer filter.
     * Always enforce at the query level.
     */
    @GetMapping("/catalog")
    @Operation(
            summary = "Get partner-scoped catalog (tenant isolated)",
            description = "Same endpoint, different data per key. Alpha sees 3 items, Beta 2, Gamma 1."
    )
    public ResponseEntity<Map<String, Object>> catalog(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        // ── Step 1: Authenticate ────────────────────────────────
        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        // ── Step 2: Scope check — catalog:read required ─────────
        // Check BEFORE hitting the data store — don't reveal data exists if the
        // partner lacks permission to see it.
        if (!partner.scopes().contains("catalog:read")) return forbidden("catalog:read");

        // ── Step 3: Audit and increment quota ───────────────────
        audit(partner.id(), "GET", "/api/partner/catalog", 200);
        int used = requestCounts.merge(partner.id(), 1, Integer::sum);
        Map<String, String> rateHeaders = quotaHeaders(partner, used);

        // ── Step 4: Fetch tenant-scoped data ────────────────────
        // In production: SELECT * FROM catalog WHERE partner_id = ?
        // partner.id() IS the tenant identifier — it comes from the validated key,
        // not from a query parameter the partner could forge.
        List<Map<String, Object>> items = TENANT_CATALOG.getOrDefault(partner.id(), List.of());

        // ── Step 5: Build response ───────────────────────────────
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId", partner.id());
        body.put("items",     items);
        body.put("count",     items.size());
        body.put("lesson",
                "Alpha Corp (3 items), Beta Inc (2 items), Gamma LLC (1 item) — same endpoint, " +
                "different data. The partner key IS the tenant identifier. Never derive the tenant " +
                "from a query parameter; always resolve it from the authenticated credential.");

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        rateHeaders.forEach(builder::header);
        return builder.body(body);
    }

    // ────────────────────────────────────────────────────────────
    // 3. PER-PARTNER AUDIT LOG
    // ────────────────────────────────────────────────────────────

    /**
     * Returns the per-partner API call audit trail.
     *
     * CONCEPT: Immutable, compliance-grade audit logging.
     *
     *   Enterprise partners typically require an audit trail for:
     *     Compliance  — SOC 2 Type II, PCI-DSS, HIPAA, ISO 27001
     *     Billing     — usage-based pricing needs a verifiable call count
     *     Debugging   — "did your API receive our request at 14:03 UTC?"
     *     Incidents   — forensic investigation after a data anomaly
     *
     *   APPEND-ONLY is non-negotiable:
     *     - Modifying or deleting audit entries is a compliance violation
     *     - Implement at the DB layer: grants INSERT but not UPDATE/DELETE
     *       on the audit table; even your own application cannot erase entries
     *
     *   DO NOT log request bodies — they may contain:
     *     - PII (names, addresses, payment data)
     *     - Secrets the partner sent (though they shouldn't)
     *   Log the request ID instead; correlate with your internal trace logs.
     *
     *   Scope-gated to PREMIUM (analytics:read) — only enterprise partners
     *   who need compliance audit access pay for it.
     */
    @GetMapping("/audit")
    @Operation(
            summary = "Partner audit trail (PREMIUM only)",
            description = "Returns per-partner call history. Requires analytics:read scope. Try with Alpha key (PREMIUM) vs Beta/Gamma."
    )
    public ResponseEntity<Map<String, Object>> audit(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        // ── Step 1: Authenticate ────────────────────────────────
        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        // ── Step 2: Scope check — analytics:read is PREMIUM-only ─
        // Beta (STANDARD) and Gamma (FREE) will receive 403 here.
        // This is intentional — access to compliance data is a premium feature.
        if (!partner.scopes().contains("analytics:read")) return forbidden("analytics:read");

        // ── Step 3: Audit the audit request itself ───────────────
        // Yes, accessing the audit log is itself an auditable event.
        audit(partner.id(), "GET", "/api/partner/audit", 200);

        // ── Step 4: Return only THIS partner's log ───────────────
        // getOrDefault returns an empty list if the partner has no calls yet,
        // avoiding a NullPointerException. Each partner sees ONLY their own entries.
        List<Map<String, Object>> log = auditLogs.getOrDefault(partner.id(), List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId",   partner.id());
        body.put("entryCount",  log.size());
        body.put("entries",     log);
        body.put("lesson",
                "Audit logs are scoped per partner and never shared across tenants. " +
                "Make logs append-only at the DB layer — even your own app should not be able " +
                "to delete entries. Compliance auditors will ask to see these.");

        return ResponseEntity.ok(body);
    }

    // ────────────────────────────────────────────────────────────
    // 4. QUOTA / TIER INFO
    // ────────────────────────────────────────────────────────────

    /**
     * Returns the partner's current quota usage and tier comparison.
     *
     * CONCEPT: Tiered rate limits — different quotas per contract tier.
     *
     *   Why tiered limits?
     *     Revenue:   sell higher-quota tiers as a product feature
     *     Fairness:  one noisy partner's traffic can't starve others
     *     Protection: free tier partners can't accidentally DoS your API
     *
     *   Always surface quota state in response headers so partners can
     *   implement client-side throttling BEFORE getting a 429:
     *
     *     X-RateLimit-Limit     — their total quota per window
     *     X-RateLimit-Used      — consumed in the current window
     *     X-RateLimit-Remaining — remaining budget
     *
     *   If Remaining approaches 0, a well-behaved partner SDK adds a sleep
     *   or backs off — avoiding 429s entirely. Without these headers, partners
     *   must guess their quota state and will inevitably over-fire.
     */
    @GetMapping("/quota")
    @Operation(summary = "Current quota usage and tier limits")
    public ResponseEntity<Map<String, Object>> quota(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        // ── Step 1: Authenticate ────────────────────────────────
        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        audit(partner.id(), "GET", "/api/partner/quota", 200);

        // ── Step 2: Read current usage (do NOT increment here) ──
        // The quota endpoint tells you how many you've used — it should not
        // itself consume a quota token. Callers check this frequently.
        // getOrDefault avoids NullPointerException for first-time callers.
        int used = requestCounts.getOrDefault(partner.id(), 0);
        int quota = TIER_QUOTA.get(partner.tier());
        Map<String, String> rateHeaders = quotaHeaders(partner, used);

        // ── Step 3: Build detailed tier comparison ───────────────
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerId",  partner.id());
        body.put("tier",       partner.tier().name());
        body.put("limit",      quota);
        body.put("used",       used);
        body.put("remaining",  Math.max(0, quota - used)); // never negative in the response body
        body.put("tierComparison", Map.of(
                "FREE",     "100 req/min — catalog:read only",
                "STANDARD", "1,000 req/min — catalog + orders read/write",
                "PREMIUM",  "10,000 req/min — full access + analytics"
        ));
        body.put("lesson",
                "Rate-limit by partner ID, not by IP. " +
                "Partners may call from a fleet of servers behind a NAT gateway, " +
                "making all their requests appear to come from one IP address. " +
                "Always return X-RateLimit-* headers so partners can self-throttle.");

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        rateHeaders.forEach(builder::header);
        return builder.body(body);
    }

    // ────────────────────────────────────────────────────────────
    // 5. API VERSIONING CONTRACT  (Deprecation + Sunset headers)
    // ────────────────────────────────────────────────────────────

    /**
     * V1 — deprecated endpoint demonstrating Deprecation + Sunset headers (RFC 8594).
     *
     * CONCEPT: B2B API versioning is a CONTRACT obligation.
     *
     *   With end users you can push a UI update and they adapt immediately.
     *   With B2B partners, their integration is CODE you don't control.
     *   A renamed field breaks their production system — possibly at 3am —
     *   without anyone noticing until automated alerts fire.
     *
     *   WHAT COUNTS AS A BREAKING CHANGE:
     *     - Removing or renaming a field
     *     - Changing a field's type   (number → object)
     *     - Removing an endpoint
     *     - Changing authentication scheme
     *     - Narrowing accepted inputs (adding required fields)
     *
     *   WHAT IS NOT BREAKING:
     *     - Adding a new optional field
     *     - Adding a new endpoint
     *     - Adding a new optional query parameter
     *
     *   DEPRECATION LIFECYCLE:
     *     1. Release /v2 ALONGSIDE /v1 — never remove v1 the same day
     *     2. Add Deprecation: true to ALL v1 responses immediately
     *     3. Add Sunset: <date> — at least 6 months out (enterprise: 12+)
     *     4. Add Link: rel="successor-version" pointing to /v2
     *     5. Email partners with a migration guide
     *     6. On Sunset date → return 410 Gone with migration instructions
     *
     *   Monitoring: track which partners are still calling v1 near the Sunset
     *   date so you can proactively reach out before breaking their integration.
     */
    @GetMapping("/v1/items")
    @Operation(
            summary = "Deprecated v1 items — shows Deprecation + Sunset headers",
            description = "Returns the old price-as-number schema with deprecation headers. See /v2/items for the current shape."
    )
    public ResponseEntity<Map<String, Object>> v1Items(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        if (!partner.scopes().contains("catalog:read")) return forbidden("catalog:read");

        audit(partner.id(), "GET", "/api/partner/v1/items", 200);

        // ── V1 response shape: price is a bare floating-point number ──
        // Breaking change in v2: price becomes a structured money object.
        // Partners parsing  `(double) item.get("price") * 1.1`  will break on v2.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(
                Map.of("id", "P001", "name", "Product A", "price", 99.99),
                Map.of("id", "P002", "name", "Product B", "price", 49.99)
        ));
        body.put("version", "v1");
        body.put("warning",           "This endpoint is deprecated. Migrate to /api/partner/v2/items before the Sunset date.");
        body.put("breaking_change",   "In v2, 'price' changed from number → object {amount, currency, minor_units}");

        return ResponseEntity.ok()
                // RFC 8594: Deprecation header signals this resource version is deprecated.
                .header("Deprecation", "true")
                // Sunset: the exact date this endpoint will stop working (return 410 Gone).
                // Partners must migrate before this date.
                .header("Sunset", "Sat, 01 Jan 2026 00:00:00 GMT")
                // Link: rel="successor-version" points partners directly to the migration target.
                .header("Link", "</api/partner/v2/items>; rel=\"successor-version\"")
                // X-API-Version: helps partners identify which version they're hitting
                // (useful when the version is negotiated by Accept header, not URL path).
                .header("X-API-Version", "v1")
                .body(body);
    }

    /**
     * V2 — current endpoint with the updated price schema.
     *
     * Price changed from a bare float to a structured money object:
     *   V1: { "price": 99.99 }
     *   V2: { "price": { "amount": 9999, "currency": "USD", "minor_units": 2 } }
     *
     * WHY THIS IS BETTER IN V2:
     *   Floating-point arithmetic on money is a classic bug source.
     *   0.1 + 0.2 = 0.30000000000000004 in IEEE 754.
     *   Storing as minor units (cents, pennies) keeps everything integer math.
     *   Including currency makes the API multi-currency-ready without another
     *   breaking change when you expand internationally.
     */
    @GetMapping("/v2/items")
    @Operation(summary = "Current v2 items — price as structured money object")
    public ResponseEntity<Map<String, Object>> v2Items(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey) {

        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        if (!partner.scopes().contains("catalog:read")) return forbidden("catalog:read");

        audit(partner.id(), "GET", "/api/partner/v2/items", 200);

        // ── V2 response shape: price is a structured money object ──
        // amount is in minor units (cents): 9999 = $99.99
        // This avoids floating-point rounding and is explicit about currency.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(
                Map.of("id", "P001", "name", "Product A",
                       "price", Map.of("amount", 9999, "currency", "USD", "minor_units", 2)),
                Map.of("id", "P002", "name", "Product B",
                       "price", Map.of("amount", 4999, "currency", "USD", "minor_units", 2))
        ));
        body.put("version", "v2");
        body.put("lesson",
                "V2 changed 'price' from float to a money object — a BREAKING CHANGE. " +
                "This requires ≥6 months notice, and /v1 must stay live until the Sunset date. " +
                "Minor units + currency avoid floating-point rounding bugs and support multi-currency.");

        return ResponseEntity.ok()
                .header("X-API-Version", "v2")
                .body(body);
    }

    // ────────────────────────────────────────────────────────────
    // 6. OUTBOUND WEBHOOK DISPATCH
    // ────────────────────────────────────────────────────────────

    /**
     * Dispatches a signed webhook event to the partner's registered callback URL.
     *
     * CONCEPT: Outbound webhooks — pushing events TO partners.
     *
     *   This is the INVERSE of the ThirdPartyApiController webhook pattern:
     *     ThirdPartyApiController:  provider posts to YOUR endpoint → YOU verify
     *     PartnerApiController:     YOU post to PARTNER's endpoint → THEY verify
     *
     *   WHY WEBHOOKS OVER POLLING:
     *     Without webhooks, partners poll your API for status updates:
     *       GET /api/partner/orders/ORD-001 every 5 seconds × 100 partners
     *       = 1,200 unnecessary requests per minute just for polling overhead
     *
     *     With webhooks, you push one request exactly when the state changes.
     *     Partners get real-time data with zero polling waste.
     *
     *   DELIVERY GUARANTEES:
     *     - Retry on failure: exponential backoff (30s, 2m, 10m, 1h, 4h)
     *     - Include X-Partner-Event-Id so partners deduplicate on their side
     *     - Track delivery status: pending → delivered | permanently_failed
     *     - Expose a "redeliver" endpoint so partners can request replay after
     *       an outage on their side (don't require them to ask via support ticket)
     *
     *   SECURITY:
     *     Sign every event. Partners must reject unsigned or tampered payloads.
     *     The partner verifies your signature exactly as shown in ThirdPartyApiController:
     *       expected = HMAC-SHA256(partnerSecret, timestamp + "." + body)
     *       MessageDigest.isEqual(expected.bytes, received.bytes)
     */
    @PostMapping("/webhook/dispatch")
    @Operation(
            summary = "Dispatch a signed webhook to the partner's callback URL",
            description = "Builds an event, signs it with HMAC-SHA256, and POSTs to the partner's registered callback. " +
                          "Demo callback is /api/partner/callback-echo on this same server."
    )
    public ResponseEntity<Map<String, Object>> dispatchWebhook(
            @RequestHeader(value = "X-Partner-Key", required = false) String partnerKey,
            @RequestBody(required = false) Map<String, Object> options) {

        // ── Step 1: Authenticate ────────────────────────────────
        Partner partner = resolvePartner(partnerKey);
        if (partner == null) return unauthorized("X-Partner-Key header is missing or unrecognized");

        // ── Step 2: Scope check ──────────────────────────────────
        // Webhook dispatch represents an event trigger (e.g. order placed).
        // Require at least one write scope — read-only FREE tier partners
        // cannot generate write events in this demo.
        if (!partner.scopes().contains("orders:write") && !partner.scopes().contains("catalog:write")) {
            return forbidden("orders:write");
        }

        audit(partner.id(), "POST", "/api/partner/webhook/dispatch", 200);

        String eventType = options != null
                ? options.getOrDefault("event_type", "order.created").toString()
                : "order.created";

        // ── Step 3: Build event payload ──────────────────────────
        // Use a stable event ID for idempotency — if the partner's server
        // returned a transient 500 and you retry, they must detect the duplicate
        // and skip reprocessing. The ID makes that detection possible.
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id",         "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        event.put("type",       eventType);
        event.put("partner_id", partner.id());           // scopes the event to this partner
        event.put("created",    Instant.now().getEpochSecond());
        event.put("data", switch (eventType) {
            case "order.created"  -> Map.of("orderId", "ORD-" + (1000 + new Random().nextInt(9000)),
                                            "amount", 149.99, "currency", "USD", "status", "pending");
            case "inventory.low"  -> Map.of("itemId", "P001", "currentStock", 3, "threshold", 10);
            default               -> Map.of("detail", "Generic partner event");
        });

        // ── Step 4: Serialize to JSON ────────────────────────────
        // Must serialize BEFORE signing — the signature covers the exact bytes
        // that will be sent. Any re-serialization after signing (even whitespace
        // differences) would produce a different body and invalidate the signature.
        String bodyJson;
        try {
            bodyJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize event payload", e);
        }

        // ── Step 5: Compute HMAC-SHA256 signature ────────────────
        // Signed payload = timestamp + "." + body
        // Including the timestamp binds the signature to this specific delivery
        // attempt — replaying the same event with a fresh timestamp requires
        // re-signing (which the partner can't do; only we have the secret).
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signedPayload = timestamp + "." + bodyJson;
        String signature = computeHmac(WEBHOOK_SIGNING_SECRET, signedPayload);

        // ── Step 6: Deliver to partner's callback URL ─────────────
        // In production: delivery runs in a background job with retry logic.
        // Here we call it synchronously for demo clarity.
        Map<String, Object> deliveryResult = new LinkedHashMap<>();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))  // fail fast on unreachable hosts
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(partner.callbackUrl()))
                    .timeout(Duration.ofSeconds(10))        // per-request deadline
                    .header("Content-Type", "application/json")
                    // Partner uses these three headers to verify authenticity and deduplicate
                    .header("X-Webhook-Signature",  signature)
                    .header("X-Webhook-Timestamp",  timestamp)
                    .header("X-Partner-Event-Id",   (String) event.get("id"))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 2xx = delivered; anything else = treat as failed and schedule retry
            deliveryResult.put("status",      response.statusCode() >= 200 && response.statusCode() < 300
                                              ? "delivered" : "failed_will_retry");
            deliveryResult.put("httpStatus",  response.statusCode());
            deliveryResult.put("callbackUrl", partner.callbackUrl());

        } catch (Exception e) {
            // Network error, connection timeout, DNS failure, etc.
            // Schedule exponential backoff retry in production.
            deliveryResult.put("status",      "failed");
            deliveryResult.put("error",       e.getMessage());
            deliveryResult.put("retryPolicy", "Exponential backoff: 30s → 2m → 10m → 1h → 4h → give up");
        }

        // ── Step 7: Return dispatch summary ─────────────────────
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventDispatched", event);
        body.put("signatureInfo", Map.of(
                "algorithm",           "HMAC-SHA256",
                "signedPayloadFormat", "<timestamp>.<jsonBody>",
                "timestamp",           timestamp,
                "signature",           signature,
                "partnerVerifiesUsing",
                    "Shared secret from onboarding. Partner recomputes HMAC and calls " +
                    "MessageDigest.isEqual(expected.bytes, received.bytes) for constant-time compare."
        ));
        body.put("delivery", deliveryResult);
        body.put("lesson",
                "Sign every outbound webhook so partners can verify origin. " +
                "Use a per-partner secret (not one global secret) — if one partner's " +
                "secret leaks, you rotate only that partner's key without affecting others.");

        return ResponseEntity.ok(body);
    }

    // ────────────────────────────────────────────────────────────
    // 7. CALLBACK ECHO  (simulates the partner's receiving endpoint)
    // ────────────────────────────────────────────────────────────

    /**
     * Simulates the PARTNER's webhook receiver accepting an event from your API.
     *
     * In a real integration this endpoint would live on the PARTNER's server.
     * In this demo both sides are the same app: dispatchWebhook POSTs here,
     * demonstrating the full round-trip within a single running process.
     *
     * The partner's responsibilities on receiving a webhook:
     *   1. Verify the HMAC-SHA256 signature (same algorithm we used to sign)
     *   2. Validate the timestamp freshness (reject events > 5 min old)
     *   3. Check X-Partner-Event-Id for duplicates (providers retry on failure)
     *   4. Return 200 within ~10 seconds; process the event asynchronously
     *
     * No X-Partner-Key required here — this is an INBOUND endpoint that the
     * partner exposes; they authenticate the caller via the webhook signature,
     * not via their own API key.
     */
    @PostMapping("/callback-echo")
    @Operation(
            summary = "Partner's callback URL (demo receiver)",
            description = "Simulates the partner-side webhook endpoint. Verifies the HMAC signature and returns the received payload."
    )
    public ResponseEntity<Map<String, Object>> callbackEcho(
            @RequestHeader(value = "X-Webhook-Signature",  required = false) String signature,
            @RequestHeader(value = "X-Webhook-Timestamp",  required = false) String timestamp,
            @RequestHeader(value = "X-Partner-Event-Id",   required = false) String eventId,
            @RequestBody String rawBody) {

        // ── Step 1: Verify the signature ─────────────────────────
        // Recompute the expected HMAC using the shared secret and compare
        // against what the sender sent in the X-Webhook-Signature header.
        boolean signatureValid = false;
        if (signature != null && timestamp != null) {
            String expected = computeHmac(WEBHOOK_SIGNING_SECRET, timestamp + "." + rawBody);

            // ✓ CONSTANT-TIME comparison — MessageDigest.isEqual always
            //   compares all bytes regardless of where the first difference is.
            // ✗ NEVER use expected.equals(signature): a naive string equals
            //   short-circuits on the first mismatched byte. Measuring response
            //   time across thousands of probes lets an attacker recover the
            //   expected signature one character at a time (timing attack).
            signatureValid = MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        }

        // ── Step 2: ACK immediately ──────────────────────────────
        // Return 200 right away. If signature is invalid, return 401 so the
        // sender knows to stop retrying (invalid sig won't fix itself).
        // For valid events: store to DB and process in a background job —
        // the sender considers delivery successful once they receive 200.
        return ResponseEntity.ok(Map.of(
                "received",      true,
                "eventId",       eventId != null ? eventId : "none",
                "signatureValid", signatureValid,
                "bodyReceived",  rawBody,
                "partnerAction", signatureValid
                        ? "Signature verified — event queued for async processing"
                        : "Signature invalid — event rejected (sender should stop retrying)"
        ));
    }

    // ────────────────────────────────────────────────────────────
    // HMAC-SHA256 HELPER
    // ────────────────────────────────────────────────────────────

    /**
     * Computes HMAC-SHA256(secret, payload) and returns the result formatted as
     * "sha256=<hex-encoded-digest>" — the standard prefix used by GitHub, Stripe, etc.
     *
     * The "sha256=" prefix lets the verifier identify the algorithm without
     * out-of-band documentation. If you later support HMAC-SHA512, you would
     * return "sha512=..." and the verifier picks the right algorithm by prefix.
     *
     * Mac is NOT thread-safe — always create a new instance per call.
     */
    private String computeHmac(String secret, String payload) {
        try {
            // Mac.getInstance("HmacSHA256") is available in all JVMs (standard JCA algorithm)
            Mac mac = Mac.getInstance("HmacSHA256");
            // SecretKeySpec wraps the raw secret bytes as a proper JCA key object
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            // doFinal produces the raw HMAC bytes; HexFormat converts to lowercase hex string
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // Only thrown if "HmacSHA256" is unavailable — impossible on any compliant JVM
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
