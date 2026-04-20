package com.example.apidemo.controller;

import com.example.apidemo.dto.PagedResponse;
import com.example.apidemo.exception.ApiException;
import com.example.apidemo.model.Product;
import com.example.apidemo.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * CONCEPT: Pagination
 *
 * Returning ALL records from a database query is an anti-pattern that:
 *   - Blows up memory (imagine 10M products)
 *   - Slows response times
 *   - Can DoS your own database
 *
 * REST pagination patterns:
 *   1. Offset-based (used here): ?page=0&size=10  → easiest, but O(offset) query cost
 *   2. Cursor-based: ?cursor=<last_seen_id>       → efficient for large datasets
 *   3. Keyset pagination: ORDER BY id WHERE id > last → stable under inserts
 *
 * SPRING DATA: Pageable is Spring's pagination abstraction.
 *   - page: 0-based page number
 *   - size: items per page
 *   - sort: field,direction (e.g., price,asc or name,desc)
 *
 * Try:
 *   GET /api/products?page=0&size=3&sort=price,asc
 *   GET /api/products?page=0&size=5&category=Electronics
 *   GET /api/products?search=phone&minPrice=100&maxPrice=1000
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "09. Pagination", description = "Pagination, sorting, and filtering demo")
public class PaginationController {

    private final ProductRepository productRepository;

    /**
     * Paginated product list with optional filtering and sorting.
     */
    @GetMapping
    @Operation(
            summary = "List products (paginated)",
            description = "Supports pagination (?page=0&size=5), sorting (?sort=price,asc), " +
                          "filtering (?category=Electronics&minPrice=100&maxPrice=1000), " +
                          "and search (?search=phone)."
    )
    public ResponseEntity<PagedResponse<Product>> listProducts(
            @Parameter(description = "0-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Items per page (max 100)") @RequestParam(defaultValue = "10") int size,
            @Parameter(description = "Sort field and direction, e.g. price,asc") @RequestParam(defaultValue = "id,asc") String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String search) {

        // SECURITY: Cap page size to prevent abuse
        int cappedSize = Math.min(size, 100);

        // Parse sort parameter "field,direction"
        Pageable pageable = buildPageable(page, cappedSize, sort);

        Page<Product> productPage;

        if (category != null || minPrice != null || maxPrice != null || search != null) {
            productPage = productRepository.findWithFilters(category, minPrice, maxPrice, search, pageable);
        } else {
            productPage = productRepository.findAll(pageable);
        }

        return ResponseEntity.ok(PagedResponse.of(productPage));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get single product by ID")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        return productRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Anti-pattern demo: fetching ALL records without pagination.
     * DO NOT do this in production with large datasets.
     */
    @GetMapping("/all-no-pagination")
    @Operation(
            summary = "Anti-pattern: no pagination (educational)",
            description = "Returns ALL products with no pagination. Safe here with 20 demo records, " +
                          "but catastrophic at scale. Never do this in production."
    )
    public ResponseEntity<Map<String, Object>> allNoPagination() {
        List<Product> all = productRepository.findAll(); // Fetches everything!
        return ResponseEntity.ok(Map.of(
                "warning", "This fetches ALL records from the database — never do this in production!",
                "record_count", all.size(),
                "at_scale", "With 1M records: ~1GB of data, slow DB query, OOM error",
                "fix", "Use pageable: GET /api/products?page=0&size=10",
                "data", all
        ));
    }

    @GetMapping("/pagination-concepts")
    @Operation(summary = "Pagination concepts explained")
    public ResponseEntity<Map<String, Object>> concepts() {
        return ResponseEntity.ok(Map.of(
                "offset_pagination", Map.of(
                        "how", "OFFSET 100 LIMIT 10 — skip 100 rows, return 10",
                        "pros", "Simple to implement, supports random page access",
                        "cons", "O(offset) cost — page 1000 of 10-item pages requires scanning 10,000 rows",
                        "use_when", "Small-to-medium datasets with low page counts"
                ),
                "cursor_pagination", Map.of(
                        "how", "WHERE id > :last_cursor ORDER BY id LIMIT 10",
                        "pros", "O(1) cost regardless of position; stable under concurrent inserts",
                        "cons", "No random page access (must paginate sequentially)",
                        "use_when", "Large datasets, infinite scroll, activity feeds"
                ),
                "spring_data_pageable", Map.of(
                        "page_param", "?page=0  (0-based)",
                        "size_param", "?size=10  (items per page; cap this!)",
                        "sort_param", "?sort=field,direction  e.g. price,asc or name,desc",
                        "multiple_sorts", "?sort=category,asc&sort=price,desc"
                ),
                "response_envelope", Map.of(
                        "include", List.of("content", "currentPage", "totalPages",
                                           "totalElements", "hasNext", "hasPrevious"),
                        "why", "Clients need metadata to render pagination controls"
                )
        ));
    }

    // ── Write Operations ────────────────────────────────────────────────────────

    /**
     * POST — Create a new resource.
     *
     * DESIGN DECISIONS:
     *
     * 1. Validate first, write second. @Valid runs before any DB interaction.
     *    A 422 means the client's data is bad — the server never touched the DB.
     *    The fieldErrors map in the response tells the client exactly what to fix.
     *
     * 2. Explicit conflict check before insert. We query by name and throw a clean
     *    ApiException rather than letting the DB unique constraint fire. A raw
     *    DataIntegrityViolationException would require parsing and may expose
     *    internal table/column names in its message.
     *
     * 3. Return 201 Created + Location header. The Location header (/api/products/{id})
     *    tells clients exactly where the new resource lives — no guessing required.
     *    Return the full resource in the body so clients don't need a follow-up GET.
     *
     * 4. POST is NOT idempotent. If the client retries after a timeout, a second
     *    identical POST may succeed and create a duplicate. The correct mitigation is
     *    an Idempotency-Key header: the server stores (key → result) so that retrying
     *    the same key returns the cached result without creating a duplicate.
     *    This demo omits that header for brevity; see docs/12-write-operations.md.
     *
     * Client strategy per error code:
     *   422 Validation  → Fix the request. Do NOT retry the same body.
     *   409 Conflict    → Already exists. GET it, or PUT to update it.
     *   500             → Retry with exponential backoff, but be aware the
     *                     first request may have succeeded (response was lost).
     *                     Without an Idempotency-Key, check for the resource
     *                     first before retrying a POST.
     */
    @PostMapping
    @Operation(
            summary = "Create product (POST design + error handling)",
            description = "Demonstrates 201 Created, 409 Conflict (duplicate name), and 422 Validation failures. " +
                          "Returns the created resource + Location header. POST is not idempotent — " +
                          "retrying after a timeout may create duplicates without an Idempotency-Key."
    )
    public ResponseEntity<Product> createProduct(@Valid @RequestBody CreateProductRequest req) {
        // Explicit check → clean 409 message, no internal DB details leaked
        if (productRepository.findByName(req.getName()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT",
                    "A product named '" + req.getName() + "' already exists. " +
                    "Use GET /api/products?search=" + req.getName() + " to find it, " +
                    "or PUT /api/products/{id} to update it.");
        }
        Product saved = productRepository.save(Product.builder()
                .name(req.getName())
                .price(req.getPrice())
                .category(req.getCategory())
                .description(req.getDescription())
                .stock(req.getStock() != null ? req.getStock() : 0)
                .build());
        return ResponseEntity
                .created(URI.create("/api/products/" + saved.getId()))
                .body(saved);
    }

    /**
     * PUT — Full replacement (idempotent).
     *
     * PUT replaces the ENTIRE resource. Every field must be provided; omitted fields
     * are treated as the new value (null or default), unlike PATCH which leaves them alone.
     *
     * DESIGN DECISIONS:
     *
     * 1. Return 200 with the full updated resource body. The client has the authoritative
     *    current state immediately — no follow-up GET needed to "confirm the update".
     *
     * 2. Return 404 if the resource does not exist. Do NOT silently create it — that
     *    is "upsert" semantics and must be a deliberate API design choice documented
     *    for clients. Mixing create and replace in one endpoint adds complexity.
     *
     * 3. Idempotency: repeating the same PUT produces the same result. This makes it
     *    safe to retry after a network timeout without risk of duplicates.
     *
     * 4. Conflict check: allow a product to keep its own name, but block stealing
     *    another product's name. The filter(other -> !other.getId().equals(id)) is the key.
     *
     * Client strategy per error code:
     *   404 Not Found   → Resource was deleted. Decide: re-create via POST? Show error?
     *   409 Conflict    → Name taken by another product. Choose a different name.
     *   422 Validation  → Fix the request body.
     *   Network timeout → Safe to retry (PUT is idempotent).
     */
    @PutMapping("/{id}")
    @Operation(
            summary = "Full update — PUT (idempotent, replace entire resource)",
            description = "PUT replaces all fields. Safe to retry after timeout (idempotent). " +
                          "Returns the updated resource — no follow-up GET needed. " +
                          "Returns 404 if the product doesn't exist (no silent upsert)."
    )
    public ResponseEntity<Product> updateProduct(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "Product with ID " + id + " does not exist. " +
                        "Use POST /api/products to create a new product."));

        // Allow keeping the same name; block taking another product's name
        productRepository.findByName(req.getName())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT",
                        "Another product already uses the name '" + req.getName() +
                        "' (ID: " + other.getId() + "). Choose a different name."); });

        product.setName(req.getName());
        product.setPrice(req.getPrice());
        product.setCategory(req.getCategory());
        product.setDescription(req.getDescription());
        product.setStock(req.getStock() != null ? req.getStock() : 0);
        return ResponseEntity.ok(productRepository.save(product));
    }

    /**
     * PATCH — Partial update (update only provided fields).
     *
     * DESIGN DECISIONS:
     *
     * 1. Only fields present in the request body are applied. Omitted fields retain
     *    their current value. This is the key difference from PUT.
     *    Implementation: null-check each field before applying it.
     *
     * 2. Return 200 with the full updated resource — client sees the final state
     *    without a round-trip GET.
     *
     * 3. Idempotency caveat: "set name to X" is idempotent, but "increment stock by 5"
     *    is NOT. For non-idempotent patches, clients must either:
     *    a) Use an Idempotency-Key so the server deduplicates retries, or
     *    b) GET the resource first to check current state before retrying.
     *    This endpoint only supports "set to" operations, so it is idempotent.
     *
     * Client strategy:
     *   "Did my PATCH apply?" → Read the 200 response body — it shows current state.
     *   Network timeout on a set-type patch → safe to retry (idempotent).
     *   Network timeout on an increment-type patch → GET first, verify, then retry only if needed.
     */
    @PatchMapping("/{id}")
    @Operation(
            summary = "Partial update — PATCH (only provided fields change)",
            description = "Omit any field to leave it unchanged. Returns the full updated resource. " +
                          "'Set' operations are idempotent (safe to retry). " +
                          "'Increment' operations are not — GET first to verify before retrying."
    )
    public ResponseEntity<Product> patchProduct(@PathVariable Long id,
                                                 @RequestBody PatchProductRequest req) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "Product with ID " + id + " does not exist."));

        if (req.getName() != null) {
            productRepository.findByName(req.getName())
                    .filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> { throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_PRODUCT",
                            "Another product already uses the name '" + req.getName() + "'."); });
            product.setName(req.getName());
        }
        if (req.getPrice() != null) product.setPrice(req.getPrice());
        if (req.getCategory() != null) product.setCategory(req.getCategory());
        if (req.getDescription() != null) product.setDescription(req.getDescription());
        if (req.getStock() != null) product.setStock(req.getStock());

        return ResponseEntity.ok(productRepository.save(product));
    }

    /**
     * DELETE — Remove a resource.
     *
     * DESIGN DECISIONS:
     *
     * 1. Return 204 No Content on success. No body — the resource is gone.
     *    Do NOT return 200 with a "deleted successfully" message; 204 is the
     *    standard and avoids parsing an unnecessary body.
     *
     * 2. Return 404 if the resource doesn't exist. Some APIs make DELETE fully
     *    idempotent by returning 204 even if the resource was already gone
     *    ("delete-if-exists"). This is a valid design choice, but returning 404
     *    provides more accurate feedback — especially for auditing.
     *
     * 3. Idempotency (with the 204-always approach): repeating a successful DELETE
     *    would keep returning 204, making retries safe. This app returns 404 on
     *    the second call — know which convention your API uses.
     *
     * Client strategy:
     *   204 No Content → Done. Don't GET to confirm — the resource is gone.
     *   404 Not Found  → Already gone (by you or another client). Usually treat as success.
     *   Network timeout → Retry. If you get 404, the delete already happened.
     */
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Delete product — DELETE (204 No Content)",
            description = "Returns 204 on success (no body). Returns 404 if the product doesn't exist. " +
                          "Network timeouts are safe to retry — a 404 on retry means the delete already succeeded."
    )
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        if (!productRepository.existsById(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                    "Product with ID " + id + " does not exist.");
        }
        productRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ── POST strategies: upsert and merge ───────────────────────────────────

    /**
     * POST + UPSERT — Create if missing, fully replace if exists.
     *
     * The client uses the natural key (name) instead of a server-assigned ID.
     * This is common in sync, import, and configuration scenarios where the
     * client owns the identity of the record.
     *
     * Behaviour:
     *   - Resource not found → create it → 201 Created + Location header
     *   - Resource found     → replace all fields → 200 OK
     *
     * Advantages:
     *   - Idempotent: repeating the same upsert always produces the same final state.
     *     Safe to retry on timeout without an Idempotency-Key.
     *   - Simpler client code: no need to GET first to check existence.
     *   - Good for bulk import / reconciliation pipelines where state must converge.
     *
     * Disadvantages:
     *   - Silently overwrites existing data. A stale client sending old values replaces
     *     newer values without any conflict signal. There is no 409 to catch bugs.
     *   - Harder to audit: the server cannot distinguish "first create" from "re-sync"
     *     unless it inspects the 200 vs 201 in the response or logs the delta.
     *   - Natural-key collisions: two clients may upsert different records with the
     *     same name, silently overwriting each other's data.
     *   - Breaks the principle that POST = "create only". Mixing semantics can confuse
     *     API consumers who expect 409 on a duplicate. Document this clearly.
     *
     * When to use:
     *   - Sync / reconciliation jobs where the final state is all that matters.
     *   - Config management: "ensure this config exists with these values".
     *   - Importing external data where the external system owns the identity.
     *   - Bulk operations where checking then inserting would be too expensive.
     *
     * When NOT to use:
     *   - When you need an audit trail distinguishing first-create from updates.
     *   - When concurrent clients may legitimately own different versions.
     *   - When overwriting without consent is dangerous (financial records, medical data).
     */
    @PostMapping("/upsert")
    @Operation(
            summary = "POST + Upsert (create or full replace by name)",
            description = "Uses 'name' as the natural key. Creates (201) if the product doesn't exist; " +
                          "fully replaces it (200) if it does. Idempotent — safe to retry on timeout. " +
                          "Tradeoff: silently overwrites existing data, no 409 conflict signal."
    )
    public ResponseEntity<Product> upsertProduct(@Valid @RequestBody CreateProductRequest req) {
        boolean isNew = productRepository.findByName(req.getName()).isEmpty();

        Product product = productRepository.findByName(req.getName())
                .orElse(new Product());
        product.setName(req.getName());
        product.setPrice(req.getPrice());
        product.setCategory(req.getCategory());
        product.setDescription(req.getDescription());
        product.setStock(req.getStock() != null ? req.getStock() : 0);

        Product saved = productRepository.save(product);

        if (isNew) {
            return ResponseEntity
                    .created(URI.create("/api/products/" + saved.getId()))
                    .body(saved);
        }
        return ResponseEntity.ok(saved);
    }

    /**
     * POST + MERGE — Create if missing, apply only provided fields if exists.
     *
     * Like upsert but uses patch semantics on the existing record: only the fields
     * present in the body are applied. Omitted fields retain their current value.
     * The name field is the natural lookup key.
     *
     * Behaviour:
     *   - Resource not found → create it (price and category required) → 201 Created
     *   - Resource found     → apply only non-null fields → 200 OK
     *
     * Advantages:
     *   - Idempotent for set-type fields: replaying the same merge produces the same
     *     final state. Safe to retry.
     *   - Preserves fields the client didn't mention: a client responsible for price
     *     can send only price updates without accidentally nulling out stock or category.
     *   - Composable: multiple services can each own a subset of fields and merge
     *     independently without stepping on each other (if fields don't overlap).
     *   - Reduces payload size: only send what changed.
     *
     * Disadvantages:
     *   - Intentional null is ambiguous: there is no way to explicitly set a field to
     *     null via merge (omitting it means "leave as-is", not "clear it"). Use PUT if
     *     you need to null out a field deliberately.
     *   - Field ownership conflicts: if two services both merge the same field with
     *     different values, last-writer-wins with no conflict detection.
     *   - More complex server logic than a simple insert or replace.
     *   - Required fields only on create, not on update — this dual-mode validation is
     *     subtle and easy to get wrong.
     *   - Harder to reason about final state: you must load the existing record and
     *     apply a partial overlay, which is harder to test and debug than a full replace.
     *
     * When to use:
     *   - Event-driven pipelines: each event carries a partial update ("price changed to X").
     *   - Multiple services co-own different fields on the same resource.
     *   - Incremental sync: send only the fields that changed since last sync.
     *   - Partial data availability: client knows some fields but not all.
     *
     * When NOT to use:
     *   - When you need to be able to clear/null a field via the API.
     *   - When the full current state of the resource is always known by the client
     *     (just use PUT instead — simpler, easier to reason about).
     *   - When field ownership between services is unclear (use explicit PUT per owner).
     */
    @PostMapping("/merge")
    @Operation(
            summary = "POST + Merge (create or partial update by name)",
            description = "Uses 'name' as the natural key. Creates (201) if the product doesn't exist; " +
                          "applies only the provided fields (200) if it does. " +
                          "Tradeoff: can't explicitly null a field; last-writer-wins on overlapping fields."
    )
    public ResponseEntity<Product> mergeProduct(@RequestBody PatchProductRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NAME_REQUIRED",
                    "'name' is required for merge — it is the lookup key.");
        }

        return productRepository.findByName(req.getName())
                .map(existing -> {
                    // Merge: only apply fields that were provided
                    if (req.getPrice() != null)       existing.setPrice(req.getPrice());
                    if (req.getCategory() != null)    existing.setCategory(req.getCategory());
                    if (req.getDescription() != null) existing.setDescription(req.getDescription());
                    if (req.getStock() != null)       existing.setStock(req.getStock());
                    return ResponseEntity.ok(productRepository.save(existing));
                })
                .orElseGet(() -> {
                    // Create: price and category are required to build a valid new product
                    if (req.getPrice() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "PRICE_REQUIRED", "Price is required when creating via merge (product does not exist yet).");
                    if (req.getCategory() == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "CATEGORY_REQUIRED", "Category is required when creating via merge (product does not exist yet).");
                    Product created = productRepository.save(Product.builder()
                            .name(req.getName())
                            .price(req.getPrice())
                            .category(req.getCategory())
                            .description(req.getDescription())
                            .stock(req.getStock() != null ? req.getStock() : 0)
                            .build());
                    return ResponseEntity
                            .created(URI.create("/api/products/" + created.getId()))
                            .body(created);
                });
    }

    // ── DTOs for write operations ────────────────────────────────────────────

    @Data
    static class CreateProductRequest {
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        private String name;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        private BigDecimal price;

        @NotBlank(message = "Category is required")
        private String category;

        private String description;

        @Min(value = 0, message = "Stock cannot be negative")
        private Integer stock;
    }

    @Data
    static class UpdateProductRequest {
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        private String name;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        private BigDecimal price;

        @NotBlank(message = "Category is required")
        private String category;

        private String description;

        @Min(value = 0, message = "Stock cannot be negative")
        private Integer stock;
    }

    @Data
    static class PatchProductRequest {
        private String name;
        private BigDecimal price;
        private String category;
        private String description;
        private Integer stock;
    }

    private Pageable buildPageable(int page, int size, String sort) {
        if (sort == null || sort.isBlank()) {
            return PageRequest.of(page, size);
        }
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        Sort.Direction direction = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc")
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, field));
    }
}
