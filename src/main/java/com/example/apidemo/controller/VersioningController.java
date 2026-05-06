package com.example.apidemo.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * CONCEPT: API Versioning
 *
 * When you change your API (rename fields, change formats, remove endpoints),
 * you need versioning to avoid breaking existing clients.
 *
 * FOUR COMMON STRATEGIES:
 *
 *   1. URI Path versioning:      /api/v1/items vs /api/v2/items
 *      ✓ Visible, cacheable, simple
 *      ✗ "Dirty" URLs; version in wrong layer (URI should identify resource, not version)
 *
 *   2. Query parameter:          /api/items?version=1
 *      ✓ Backward-compatible (optional param)
 *      ✗ Easy to forget; not RESTful
 *
 *   3. Request header:           X-API-Version: 2
 *      ✓ Keeps URLs clean
 *      ✗ Not visible in browser URL; caching complications
 *
 *   4. Accept header (content negotiation):
 *      Accept: application/vnd.company.v2+json
 *      ✓ Most RESTful (content type IS the version)
 *      ✗ Complex to implement and test
 *
 * RECOMMENDATION: URI versioning is most practical for most APIs.
 * Accept-header versioning is most RESTfully correct.
 */
@RestController
@Tag(name = "10. API Versioning", description = "API versioning strategies demo")
public class VersioningController {

    // ── STRATEGY 1: URI PATH VERSIONING ────────────────────────────

    @GetMapping("/api/v1/items")
    @Operation(summary = "V1 items (URI versioning)", description = "Version embedded in the URL path")
    public ResponseEntity<Map<String, Object>> v1Items() {
        return ResponseEntity.ok()
                .header("Deprecation", "true")
                .header("Sunset", "Sat, 01 Jul 2026 00:00:00 GMT")
                .header("Link", "</api/v2/items>; rel=\"successor-version\"")
                .header("X-API-Version", "v1")
                .body(Map.of(
                        "version", "1",
                        "strategy", "URI Path: /api/v1/items",
                        "items", java.util.List.of(
                                Map.of("id", 1, "name", "Widget A", "price", 9.99),
                                Map.of("id", 2, "name", "Widget B", "price", 19.99)
                        ),
                        "note", "V1: price is a float. V2 changed it to an object with currency.",
                        "warning", "This endpoint is deprecated and will be retired on 2026-07-01. Migrate to /api/v2/items."
                ));
    }

    @GetMapping("/api/v2/items")
    @Operation(summary = "V2 items (URI versioning)", description = "Breaking change from V1: price is now an object")
    public ResponseEntity<Map<String, Object>> v2Items() {
        return ResponseEntity.ok(Map.of(
                "version", "2",
                "strategy", "URI Path: /api/v2/items",
                "items", java.util.List.of(
                        Map.of("id", 1, "name", "Widget A",
                               "price", Map.of("amount", 9.99, "currency", "USD")),
                        Map.of("id", 2, "name", "Widget B",
                               "price", Map.of("amount", 19.99, "currency", "USD"))
                ),
                "breaking_change", "price changed from float to {amount, currency} object",
                "why_versioned", "V1 clients would break if price changed structure in-place"
        ));
    }

    // ── STRATEGY 1b: 410 GONE (POST-SUNSET EXAMPLE) ────────────────

    /**
     * Simulates a fully sunset endpoint — returns 410 Gone.
     *
     * 410 (not 404) signals "this resource existed but was intentionally removed."
     * 404 would suggest the URL was always wrong; 410 tells the client to stop retrying
     * and follow the migration link instead.
     *
     * Include a migration URL in the response body so clients know where to go.
     */
    @GetMapping("/api/v0/items")
    @Operation(
            summary = "V0 items — 410 Gone (post-sunset)",
            description = "Demonstrates what to return after a version has been fully retired. Always 410, not 404."
    )
    public ResponseEntity<Map<String, Object>> v0Items() {
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "type",           "/errors/gone",
                "title",          "Gone",
                "status",         410,
                "detail",         "API v0 was retired on 2025-01-01. Migrate to /api/v2/items.",
                "migrationGuide", "https://docs.example.com/migration/v0-to-v2",
                "sunset",         "2025-01-01T00:00:00Z",
                "lesson",         "Return 410 Gone (not 404) when an endpoint is intentionally retired. " +
                                  "404 implies the URL was always wrong. 410 says: this existed, it was removed, stop retrying, migrate here."
        ));
    }

    // ── STRATEGY 2: QUERY PARAMETER VERSIONING ─────────────────────

    @GetMapping("/api/items/by-param")
    @Operation(
            summary = "Query parameter versioning",
            description = "Try ?version=1 or ?version=2"
    )
    public ResponseEntity<Map<String, Object>> queryParamVersioning(
            @RequestParam(defaultValue = "1") String version) {

        if ("2".equals(version)) {
            return ResponseEntity.ok(Map.of(
                    "version", "2",
                    "strategy", "Query param: ?version=2",
                    "data", Map.of("id", 1, "fullName", "Widget Alpha", "priceUsd", 9.99)
            ));
        }

        return ResponseEntity.ok(Map.of(
                "version", "1",
                "strategy", "Query param: ?version=1",
                "data", Map.of("id", 1, "name", "Widget Alpha", "price", 9.99)
        ));
    }

    // ── STRATEGY 3: HEADER VERSIONING ──────────────────────────────

    @GetMapping("/api/items/by-header")
    @Operation(
            summary = "Request header versioning",
            description = "Send X-API-Version: 1 or X-API-Version: 2 header"
    )
    public ResponseEntity<Map<String, Object>> headerVersioning(
            @RequestHeader(value = "X-API-Version", defaultValue = "1") String version) {

        if ("2".equals(version)) {
            return ResponseEntity.ok(Map.of(
                    "version", "2",
                    "strategy", "Header: X-API-Version: 2",
                    "data", Map.of("identifier", 1, "itemName", "Widget Alpha")
            ));
        }

        return ResponseEntity.ok(Map.of(
                "version", "1",
                "strategy", "Header: X-API-Version: 1",
                "data", Map.of("id", 1, "name", "Widget Alpha")
        ));
    }

    // ── STRATEGY 4: ACCEPT HEADER (CONTENT NEGOTIATION) ────────────

    @GetMapping(value = "/api/items/by-accept",
                produces = {"application/vnd.demo.v1+json", "application/json"})
    @Operation(
            summary = "V1 Accept header versioning",
            description = "Send Accept: application/vnd.demo.v1+json"
    )
    public ResponseEntity<Map<String, Object>> acceptHeaderV1() {
        return ResponseEntity.ok(Map.of(
                "version", "1",
                "strategy", "Accept: application/vnd.demo.v1+json",
                "data", Map.of("id", 1, "name", "Widget Alpha", "price", 9.99)
        ));
    }

    @GetMapping(value = "/api/items/by-accept",
                produces = "application/vnd.demo.v2+json")
    @Operation(
            summary = "V2 Accept header versioning",
            description = "Send Accept: application/vnd.demo.v2+json"
    )
    public ResponseEntity<Map<String, Object>> acceptHeaderV2() {
        return ResponseEntity.ok(Map.of(
                "version", "2",
                "strategy", "Accept: application/vnd.demo.v2+json",
                "data", Map.of("id", 1, "fullName", "Widget Alpha",
                               "price", Map.of("amount", 9.99, "currency", "USD"))
        ));
    }

    // ── COMPARISON ─────────────────────────────────────────────────

    @GetMapping("/api/versioning/comparison")
    @Operation(summary = "Versioning strategy comparison")
    public ResponseEntity<Map<String, Object>> comparison() {
        return ResponseEntity.ok(Map.of(
                "strategies", Map.of(
                        "uri_path", Map.of(
                                "example", "/api/v1/items",
                                "pros", "Visible, easy to test, cache-friendly",
                                "cons", "Version in URI violates REST principles",
                                "used_by", "Twitter, Stripe, Twilio"
                        ),
                        "query_param", Map.of(
                                "example", "/api/items?version=1",
                                "pros", "Backward-compatible (default version works)",
                                "cons", "Easy to omit, complicates caching"
                        ),
                        "header", Map.of(
                                "example", "X-API-Version: 2",
                                "pros", "Clean URLs, version is metadata",
                                "cons", "Not visible in browser, cache-key complications",
                                "used_by", "GitHub (v3 header), Microsoft"
                        ),
                        "accept_header", Map.of(
                                "example", "Accept: application/vnd.company.v2+json",
                                "pros", "Most RESTful, proper content negotiation",
                                "cons", "Complex, hard to test in browser",
                                "used_by", "GitHub (media type versioning)"
                        )
                ),
                "recommendation", "URI versioning is most practical. Use Accept headers if REST purity matters.",
                "sunset_headers", Map.of(
                        "Deprecation", "Tue, 01 Jan 2025 00:00:00 GMT",
                        "Sunset", "Wed, 01 Jan 2026 00:00:00 GMT",
                        "Link", "<https://api.example.com/v2/items>; rel=\"successor-version\""
                )
        ));
    }
}
