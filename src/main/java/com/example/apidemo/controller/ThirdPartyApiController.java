package com.example.apidemo.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * CONCEPT: REST API Design with Third-Party Providers
 *
 * When your service integrates with external providers (Stripe, GitHub, Twilio, etc.)
 * the design challenges shift fundamentally compared to serving your own users:
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │              User-Facing API          Third-Party Integration         │
 * ├──────────────────────────────────────────────────────────────────────┤
 * │ Auth direction   Client → Your API    You → Provider API             │
 * │                                       Provider → Your webhook        │
 * │ Auth mechanism   JWT, Session         API key / OAuth2 (outbound)    │
 * │                                       HMAC signature verify (inbound)│
 * │ Rate limits      You set them         Provider sets them (you obey)  │
 * │ SLA/uptime       You control          Provider controls (plan for it)│
 * │ Schema changes   You control          Provider may change anytime     │
 * │ Error format     You standardize      Map provider errors to yours    │
 * │ Credentials      User manages theirs  You manage provider keys        │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * THREE KEY PATTERNS DEMOED:
 *
 *   1. WEBHOOK RECEIVER with signature verification
 *      Providers POST events to your URL. You must verify the HMAC-SHA256
 *      signature on every request — otherwise anyone can spoof events.
 *      Pattern: GitHub, Stripe, Twilio all use this approach.
 *
 *   2. OUTBOUND API CALLS with provider error handling
 *      Providers return errors in their own formats. You must:
 *        - Map their HTTP codes + error bodies to your domain errors
 *        - Respect their Retry-After header on 429
 *        - Circuit break on sustained 503s
 *        - Never expose provider error details to your end users
 *
 *   3. CREDENTIAL MANAGEMENT
 *      Provider API keys are long-lived secrets. Store them in environment
 *      variables or a secrets manager — never in code or config files.
 *      Support key rotation without downtime (dual-key overlap window).
 */
@RestController
@RequestMapping("/api/third-party")
@Tag(name = "12. Third-Party APIs", description = "Webhook verification, outbound API calls, credential management")
public class ThirdPartyApiController {

    // ── Demo configuration ──────────────────────────────────────
    //
    // In production: load from environment variable or secrets manager.
    //   System.getenv("PROVIDER_WEBHOOK_SECRET")
    //   vaultClient.getSecret("provider/webhook-secret")
    //
    // NEVER hardcode secrets in source code (even in demo apps,
    // a test secret should be clearly labelled as such).
    static final String WEBHOOK_SECRET = "whsec_demo_secret_12345"; // DEMO ONLY

    // In-memory log of received webhook events (demo only — use DB in prod)
    private final List<Map<String, Object>> eventLog = new CopyOnWriteArrayList<>();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── INFO ────────────────────────────────────────────────────

    @GetMapping("/info")
    @Operation(summary = "Third-party API integration concepts")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "key_shift", "When integrating with providers you are the CLIENT, not the server. " +
                             "Authentication, error handling, and availability constraints are all inverted.",
                "patterns", Map.of(
                        "webhook_receiver", "POST /api/third-party/webhook — verify HMAC-SHA256 signatures",
                        "send_test_webhook", "POST /api/third-party/webhook/send-test — simulate a provider posting to you",
                        "outbound_call",     "GET  /api/third-party/outbound?scenario=... — provider API call scenarios",
                        "event_log",         "GET  /api/third-party/events — view received webhook events"
                ),
                "webhook_secret_for_demo", WEBHOOK_SECRET
        ));
    }

    // ══════════════════════════════════════════════════════════════
    // PATTERN 1: WEBHOOK RECEIVER
    // ══════════════════════════════════════════════════════════════

    /**
     * Receives and verifies incoming webhook events from a provider.
     *
     * SECURITY REQUIREMENTS:
     *
     *   1. Signature verification (HMAC-SHA256)
     *      Provider signs the raw request body with a shared secret.
     *      Header: X-Webhook-Signature: sha256=<hex(HMAC-SHA256(secret, body))>
     *      You recompute the HMAC and compare — reject if they don't match.
     *      Use constant-time comparison to prevent timing attacks.
     *
     *   2. Timestamp validation (replay attack protection)
     *      Header: X-Webhook-Timestamp: <unix_seconds>
     *      Reject events older than 5 minutes — prevents attackers from
     *      replaying a captured (valid) webhook days later.
     *
     *   3. Idempotency
     *      Providers retry failed webhooks. Your handler must be idempotent:
     *      processing the same event twice must produce the same result.
     *      Deduplicate using the event ID.
     *
     *   4. Respond quickly — ack first, process async
     *      Return 200 within ~5 seconds or the provider marks delivery as failed
     *      and schedules a retry. For heavy processing, save the event and
     *      return 200 immediately, then process in a background job.
     */
    @PostMapping("/webhook")
    @Operation(
            summary = "Receive a signed webhook",
            description = "Verifies the HMAC-SHA256 signature, timestamp, and logs the event. " +
                          "Use POST /api/third-party/webhook/send-test to generate a valid request."
    )
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestHeader(value = "X-Webhook-Timestamp", required = false) String timestamp,
            @RequestBody String rawBody) {

        // ── Step 1: Require signature header ───────────────────
        if (signature == null || !signature.startsWith("sha256=")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "MISSING_SIGNATURE",
                    "detail", "X-Webhook-Signature header is required",
                    "lesson", "Always reject unsigned webhooks — anyone can POST to a public URL"
            ));
        }

        // ── Step 2: Validate timestamp (replay protection) ─────
        if (timestamp == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "MISSING_TIMESTAMP",
                    "detail", "X-Webhook-Timestamp header is required"
            ));
        }
        try {
            long ts = Long.parseLong(timestamp);
            long now = Instant.now().getEpochSecond();
            if (Math.abs(now - ts) > 300) { // 5-minute window
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                        "error", "TIMESTAMP_EXPIRED",
                        "detail", "Webhook timestamp is outside the 5-minute tolerance window",
                        "lesson", "Replay attack protection: reject old timestamps so a captured " +
                                  "valid webhook cannot be replayed hours later"
                ));
            }
        } catch (NumberFormatException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "INVALID_TIMESTAMP",
                    "detail", "X-Webhook-Timestamp must be a Unix timestamp in seconds"
            ));
        }

        // ── Step 3: Verify HMAC-SHA256 signature ───────────────
        // The provider signs: HMAC-SHA256(secret, timestamp + "." + body)
        // (Including timestamp in the signed payload ties signature to this specific request)
        String signedPayload = timestamp + "." + rawBody;
        String expected = computeHmac(WEBHOOK_SECRET, signedPayload);

        // ✓ Use constant-time comparison — byte-by-byte equality prevents timing attacks
        // (A naive string.equals() leaks how many chars matched via response time)
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                   signature.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "INVALID_SIGNATURE",
                    "detail", "HMAC-SHA256 verification failed — payload may have been tampered with",
                    "lesson", "Signature mismatch means either the secret is wrong, " +
                              "or the body/timestamp was modified in transit"
            ));
        }

        // ── Step 4: Parse event ────────────────────────────────
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "INVALID_JSON",
                    "detail", "Request body is not valid JSON"
            ));
        }

        // ── Step 5: Idempotency check ──────────────────────────
        String eventId = (String) event.getOrDefault("id", UUID.randomUUID().toString());
        boolean duplicate = eventLog.stream()
                .anyMatch(e -> eventId.equals(e.get("eventId")));

        // ── Step 6: Store event and ACK immediately ────────────
        // In production: persist to DB, enqueue for async processing, then return 200.
        // Do NOT do heavy processing here — provider will retry if you take > ~5s.
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("eventId", eventId);
        logEntry.put("receivedAt", Instant.now().toString());
        logEntry.put("type", event.getOrDefault("type", "unknown"));
        logEntry.put("payload", event);
        logEntry.put("duplicate", duplicate);
        if (!duplicate) eventLog.add(0, logEntry);
        if (eventLog.size() > 20) eventLog.subList(20, eventLog.size()).clear();

        return ResponseEntity.ok(Map.of(
                "received", true,
                "eventId", eventId,
                "type", event.getOrDefault("type", "unknown"),
                "duplicate", duplicate,
                "signature_verified", true,
                "timestamp_valid", true,
                "lesson", duplicate
                        ? "Duplicate detected via event ID — idempotent handler skipped reprocessing"
                        : "Signature valid, timestamp fresh, event stored. Returning 200 ACK immediately."
        ));
    }

    /**
     * Simulates a provider sending a signed webhook to your endpoint.
     *
     * This endpoint acts as the PROVIDER side: it generates a proper
     * HMAC-SHA256 signature and POSTs to /api/third-party/webhook.
     * Use "tamper": true in the body to simulate a modified payload.
     */
    @PostMapping("/webhook/send-test")
    @Operation(
            summary = "Simulate a provider sending a signed webhook",
            description = "Generates an HMAC-SHA256 signature and POSTs to the webhook endpoint. " +
                          "Pass {\"tamper\": true} to simulate a tampered payload (signature will fail)."
    )
    public ResponseEntity<Map<String, Object>> sendTestWebhook(
            @RequestBody(required = false) Map<String, Object> options) {

        boolean tamper = options != null && Boolean.TRUE.equals(options.get("tamper"));
        boolean replayAttack = options != null && Boolean.TRUE.equals(options.get("replay_attack"));

        // Build a realistic event payload (like Stripe or GitHub would send)
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", "evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        event.put("type", options != null ? options.getOrDefault("type", "payment.completed").toString() : "payment.completed");
        event.put("created", Instant.now().getEpochSecond());
        event.put("data", Map.of(
                "amount", 4999,
                "currency", "usd",
                "customer", "cus_demo_123",
                "status", "succeeded"
        ));

        String body;
        try {
            body = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String timestamp = replayAttack
                ? String.valueOf(Instant.now().getEpochSecond() - 600) // 10 min ago → replay rejected
                : String.valueOf(Instant.now().getEpochSecond());

        // Provider computes: HMAC-SHA256(secret, timestamp + "." + body)
        String signedPayload = timestamp + "." + body;
        String signature = computeHmac(WEBHOOK_SECRET, signedPayload);

        // If tamper=true: sign first, then modify the body → signature won't match
        String bodyToSend = tamper ? body.replace("4999", "1") : body;

        // POST to our own webhook endpoint (simulating the provider)
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/api/third-party/webhook"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Webhook-Signature", signature)
                .header("X-Webhook-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(bodyToSend))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> webhookResponse = objectMapper.readValue(response.body(), new TypeReference<>() {});

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("provider_side", Map.of(
                    "action", "Signed the payload with HMAC-SHA256",
                    "signed_payload_format", "<timestamp>.<body>",
                    "timestamp", timestamp,
                    "signature", signature,
                    "tampered", tamper,
                    "replay_attack_simulated", replayAttack,
                    "body_sent", bodyToSend
            ));
            result.put("receiver_side", Map.of(
                    "http_status", response.statusCode(),
                    "response", webhookResponse
            ));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/events")
    @Operation(summary = "View received webhook events log")
    public ResponseEntity<Map<String, Object>> events() {
        return ResponseEntity.ok(Map.of(
                "count", eventLog.size(),
                "events", eventLog
        ));
    }

    // ══════════════════════════════════════════════════════════════
    // PATTERN 2: OUTBOUND API CALLS
    // ══════════════════════════════════════════════════════════════

    /**
     * Simulates calling a third-party provider API with proper error handling.
     *
     * When consuming provider APIs you must handle:
     *
     *   429 Too Many Requests  → Read Retry-After, back off, do NOT hammer the API
     *   401 Unauthorized       → Credential issue — alert ops, do not retry blindly
     *   503 Service Unavailable → Provider is down — circuit break, use cached data
     *   Partial failures       → Provider-specific error codes inside a 200 response
     *   Schema drift           → Provider changes their response shape — defensive parsing
     *
     * NEVER expose provider error details to your end users:
     *   Bad:  "Stripe error: No such customer: 'cus_XYZ'" (leaks integration details)
     *   Good: "Payment processing temporarily unavailable. Please try again."
     */
    @GetMapping("/outbound")
    @Operation(
            summary = "Simulated outbound provider API call",
            description = "Demonstrates different provider error scenarios and correct handling. " +
                          "Scenarios: success, rate-limited, auth-failed, server-error, schema-drift"
    )
    public ResponseEntity<Map<String, Object>> outboundCall(
            @RequestParam(defaultValue = "success") String scenario) {

        // Simulate credential loading from environment (never hardcode in prod)
        String apiKey = System.getenv("PROVIDER_API_KEY");
        if (apiKey == null) apiKey = "sk_demo_key_loaded_from_env"; // fallback for demo

        return switch (scenario) {

            // ── Happy path ─────────────────────────────────────
            case "success" -> {
                // Defensive parsing: use getOrDefault / Optional for every field
                // Provider schema may add/remove fields across versions
                Map<String, Object> providerResponse = Map.of(
                        "id", "pi_demo_123",
                        "amount", 4999,
                        "currency", "usd",
                        "status", "succeeded",
                        "created", Instant.now().getEpochSecond()
                );
                yield ResponseEntity.ok(Map.of(
                        "result", "success",
                        "data", providerResponse,
                        "credential_used", "API key from environment variable (never hardcoded)",
                        "lesson", "Always parse defensively — providers add fields in minor versions " +
                                  "and remove deprecated fields in major versions"
                ));
            }

            // ── Rate limited ───────────────────────────────────
            case "rate-limited" -> {
                // Provider returned 429 — respect Retry-After, use exponential backoff
                // Do NOT immediately retry — that makes the rate limit worse
                yield ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                        "result", "PROVIDER_RATE_LIMITED",
                        "provider_status", 429,
                        "retry_after_seconds", 30,
                        "handling", Map.of(
                                "do", "Read Retry-After header, wait, then retry with exponential backoff",
                                "dont", "Retry immediately in a tight loop — causes cascading rate limit",
                                "production", "Queue the request for retry, return 202 Accepted to your caller",
                                "code_pattern", "if (response.status == 429) { wait(response.headers['Retry-After']); retry(); }"
                        ),
                        "user_facing_message", "Service temporarily busy. Your request has been queued."
                ));
            }

            // ── Auth failure ───────────────────────────────────
            case "auth-failed" -> {
                // Provider returned 401 — credential problem, alert ops immediately
                // Do NOT retry — the same credential will fail again
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "result", "PROVIDER_AUTH_FAILED",
                        "provider_status", 401,
                        "handling", Map.of(
                                "do", "Alert on-call immediately — this needs human intervention",
                                "dont", "Retry or expose provider's error message to users",
                                "causes", List.of(
                                        "API key expired or revoked",
                                        "Wrong key for this environment (using prod key in staging)",
                                        "Key lacks required permission scope"
                                ),
                                "rotation_strategy", "Keep two valid keys. Rotate by activating new key, " +
                                                     "migrating traffic, then revoking old key."
                        ),
                        "user_facing_message", "Payment service unavailable. Our team has been alerted."
                ));
            }

            // ── Provider server error ──────────────────────────
            case "server-error" -> {
                // Provider returned 5xx — their problem, but your problem to handle
                // Use circuit breaker: after N failures, stop calling, serve cached/default
                yield ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                        "result", "PROVIDER_SERVER_ERROR",
                        "provider_status", 503,
                        "handling", Map.of(
                                "do", "Circuit break after repeated failures, serve cached data or graceful degradation",
                                "dont", "Propagate provider's 503 directly to your users or retry indefinitely",
                                "circuit_breaker", "After 5 failures in 10 calls, open circuit for 30s. " +
                                                   "Half-open to test recovery.",
                                "degraded_mode", "Return last known good data with a staleness indicator"
                        ),
                        "user_facing_message", "Some features are temporarily limited. We're working on it."
                ));
            }

            // ── Schema drift ───────────────────────────────────
            case "schema-drift" -> {
                // Provider changed their response shape in a new API version
                // Defensive parsing prevents NullPointerException on unexpected structure
                Map<String, Object> newProviderShape = Map.of(
                        "payment_id", "pi_demo_456",          // was "id"
                        "amount_minor_units", 4999,            // was "amount"
                        "currency_code", "USD",                // was "currency"
                        "payment_status", "completed",         // was "status"
                        "new_field_you_didnt_know_about", true // added in their v2
                );
                yield ResponseEntity.ok(Map.of(
                        "result", "SCHEMA_DRIFT_EXAMPLE",
                        "raw_provider_response", newProviderShape,
                        "your_domain_model", Map.of(
                                "id", newProviderShape.getOrDefault("payment_id",
                                      newProviderShape.getOrDefault("id", "unknown")),
                                "amount", newProviderShape.getOrDefault("amount_minor_units",
                                          newProviderShape.getOrDefault("amount", 0)),
                                "currency", newProviderShape.getOrDefault("currency_code",
                                            newProviderShape.getOrDefault("currency", "unknown")),
                                "status", newProviderShape.getOrDefault("payment_status",
                                          newProviderShape.getOrDefault("status", "unknown"))
                        ),
                        "lesson", "Map provider fields to your own domain model at the boundary. " +
                                  "Your internal code never references provider field names directly. " +
                                  "When they rename a field, you only change the mapper."
                ));
            }

            default -> ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown scenario",
                    "valid_scenarios", List.of("success", "rate-limited", "auth-failed", "server-error", "schema-drift")
            ));
        };
    }

    // ── HMAC-SHA256 helper ──────────────────────────────────────

    private String computeHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC", e);
        }
    }
}
