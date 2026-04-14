package com.example.apidemo.controller;

import com.example.apidemo.dto.PagedResponse;
import com.example.apidemo.model.Product;
import com.example.apidemo.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
