# Pagination

Returning all records from a database in a single response is a common and dangerous anti-pattern.

---

## Why Paginate?

- **Memory**: 1M product records ≈ 1GB+ in memory
- **Network**: Sending 1GB per response is unusably slow
- **Database**: Full table scans are expensive
- **User experience**: No one reads 1M results

---

## Demo

```bash
# Basic pagination (page 0, 5 items)
curl "http://localhost:8080/api/products?page=0&size=5"

# Page 1 (second page)
curl "http://localhost:8080/api/products?page=1&size=5"

# Sort by price ascending
curl "http://localhost:8080/api/products?page=0&size=5&sort=price,asc"

# Filter by category
curl "http://localhost:8080/api/products?category=Electronics&page=0&size=10"

# Search + price range
curl "http://localhost:8080/api/products?search=phone&minPrice=100&maxPrice=1000"

# Anti-pattern: no pagination
curl "http://localhost:8080/api/products/all-no-pagination"
```

---

## Response Structure

Always include metadata:

```json
{
  "content": [...],
  "currentPage": 0,
  "pageSize": 5,
  "totalElements": 20,
  "totalPages": 4,
  "hasNext": true,
  "hasPrevious": false
}
```

Clients need `totalPages` and `totalElements` to render pagination controls.

---

## Offset vs Cursor Pagination

### Offset Pagination (used in this demo)

```sql
SELECT * FROM products ORDER BY id LIMIT 10 OFFSET 100;
```

**How Spring Data Pageable works:**
- `page=10, size=10` → `OFFSET 100 LIMIT 10`
- Spring generates this automatically

**Pros:** Simple, random page access, easy to implement  
**Cons:** Performance degrades as offset grows (DB must skip all preceding rows)

### Cursor Pagination

```sql
-- First page
SELECT * FROM products WHERE id > 0 ORDER BY id LIMIT 10;
-- Returns last id = 47

-- Next page (cursor = 47)
SELECT * FROM products WHERE id > 47 ORDER BY id LIMIT 10;
```

**Pros:** O(1) cost at any position; stable under concurrent inserts  
**Cons:** No random page access; cursor must be included in every request

**Use cursor pagination for:** Infinite scroll, activity feeds, large datasets (>100K rows with deep pagination).

---

## Capping Page Size

Always cap the `size` parameter to prevent abuse:

```java
int cappedSize = Math.min(requestedSize, 100);  // Never allow more than 100 per page
```

Without this, `?size=1000000` is effectively no pagination.

---

## Sorting

Spring Data's `Sort.by()` supports multiple fields:

```
GET /api/products?sort=category,asc&sort=price,desc

→ ORDER BY category ASC, price DESC
```

**SECURITY:** Validate sort fields against an allowlist to prevent SQL injection through sort parameters:
```java
Set<String> ALLOWED_SORT_FIELDS = Set.of("name", "price", "category", "id");
if (!ALLOWED_SORT_FIELDS.contains(sortField)) {
    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SORT", "Invalid sort field: " + sortField);
}
```
