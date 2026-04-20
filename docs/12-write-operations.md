# Write Operations & Error Design

POST, PUT, PATCH, DELETE — when they fail, why they fail, and what clients should do.

---

## The core question every API designer must answer

> "If my write call fails — or I never get a response — what should I do next?"

The answer depends on *which method* failed and *which error code* came back. Getting this wrong causes data corruption (duplicate inserts), stale state (missed updates), or silent data loss (retrying when you shouldn't).

---

## Method semantics

| Method | Purpose | Idempotent? | Success code | Response body |
|--------|---------|-------------|--------------|---------------|
| POST   | Create new resource | **No** | 201 Created | Return the created resource |
| PUT    | Full replace (all fields) | **Yes** | 200 OK | Return the updated resource |
| PATCH  | Partial update (only sent fields) | Usually yes* | 200 OK | Return the updated resource |
| DELETE | Remove resource | Yes | 204 No Content | No body |

\* PATCH "set name to X" is idempotent. PATCH "increment stock by 5" is **not** — retrying adds 5 again.

---

## POST — Create (not idempotent)

### What the server does

1. Validate the request body (`@Valid`). If invalid → **422** before any DB write.
2. Check for conflicts (duplicate name, unique violation). If found → **409**.
3. Write to the database.
4. Return **201 Created** with `Location: /api/products/{id}` and the full created resource in the body.

### Why 201 + Location?

The `Location` header tells the client exactly where the new resource lives without them having to guess or reconstruct the URL. Returning the full resource in the body means the client has all the data it needs — **no follow-up GET required**.

### What clients should do per error

| Status | Meaning | Client action |
|--------|---------|---------------|
| 422 Validation | Bad input | Show `fieldErrors` to user. Fix and resubmit. **Do not retry the same body.** |
| 409 Conflict | Already exists | GET the existing resource? PUT to update it? Show "already exists" to user. |
| 400 Bad Request | Malformed request | Fix JSON structure or Content-Type. Do not retry. |
| 500 Server Error | Transient failure | Retry with exponential backoff. **Risk: the insert may have already succeeded but the response was lost.** Check for the resource before retrying, or use an Idempotency-Key. |
| Network timeout | Outcome unknown | Do not blindly retry. GET first to see if the resource was created. If not, retry with the same Idempotency-Key. |

### The duplicate-on-retry problem

```
Client                         Server
  │── POST /products ──────────▶│ (insert succeeds)
  │                             │ (response lost in network)
  │◀─ [timeout] ────────────────│
  │
  │── POST /products ──────────▶│ (inserts again = DUPLICATE)
```

Without protection, a network timeout causes the client to retry and create a duplicate. There are two mitigations:

**Option A — GET before retry:**
```
timeout → GET /products?search=WidgetPro → found → skip retry
                                          → not found → retry POST
```
Simpler, but adds a round trip and has a small race condition window.

**Option B — Idempotency-Key header (recommended):**
```
POST /api/products
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

{ "name": "Widget Pro", "price": 29.99 }
```
Server stores `(key → response)`. On retry with the same key, it returns the cached response without a DB write. The client generates the UUID *before* the first attempt and reuses it on every retry for the same operation.

---

## PUT — Full replace (idempotent)

### What the server does

1. Look up the resource. If missing → **404**.
2. Validate the request body. If invalid → **422**.
3. Check name conflicts against *other* resources. If found → **409**.
4. Replace all fields with the request body.
5. Return **200** with the full updated resource.

### Key design decisions

**Return 404, not silent upsert.** If the resource doesn't exist, return 404. Do not silently create it — that is "upsert" semantics, which is a valid but distinct design choice that must be deliberate and documented. Mixing create-if-missing into PUT confuses clients about what they actually own.

**Return the full resource on 200.** Clients should never need a follow-up GET to "confirm the update". The response body *is* the current state.

**Allow keeping the same name.** The conflict check must exclude the resource being updated itself. Without this, a PUT to `/products/5` with the existing name "Widget A" would incorrectly 409 against itself.

### What clients should do per error

| Status | Meaning | Client action |
|--------|---------|---------------|
| 404 Not Found | Resource deleted | Decide: re-create via POST? Show "no longer exists" error? |
| 409 Conflict | Name conflict or optimistic lock mismatch | GET the fresh resource, re-apply changes, retry. |
| 422 Validation | Bad input | Fix and resubmit. |
| Network timeout | Outcome unknown | **Retry freely.** PUT is idempotent — sending the same body again produces the same result. |

---

## PATCH — Partial update

### What the server does

Only fields present in the request body are updated. Fields not mentioned retain their current value.

Implementation: null-check every field before applying:
```java
if (req.getName() != null) product.setName(req.getName());
if (req.getPrice() != null) product.setPrice(req.getPrice());
// etc.
```

### PUT vs PATCH: which to use?

| Scenario | Use |
|----------|-----|
| Replacing an entire resource (profile, config object) | PUT |
| Updating one or two fields without touching others | PATCH |
| The client doesn't have the full current state | PATCH |
| You need guaranteed idempotency on increment operations | PUT (send the final value, not a delta) |

### Idempotency caveat

```
PATCH { "stock": 50 }       ← idempotent: "set stock to 50"
PATCH { "stockDelta": +10 } ← NOT idempotent: "add 10 to current stock"
```

For increment-style patches, use an Idempotency-Key or GET the current value first to verify whether the increment was already applied before retrying.

---

## DELETE — Remove (204 No Content)

### What the server does

1. Check the resource exists. If missing → **404**.
2. Delete it.
3. Return **204 No Content** with no body.

### 204 vs 404 on re-delete

Two valid conventions:

**Strict (this app):** Return 404 if already gone. More accurate feedback; useful for auditing.

**Lenient (idempotent delete):** Return 204 even if already gone ("delete-if-exists"). Makes retries unconditionally safe; no need to check the status code.

Document which convention your API uses. Clients need to know whether to treat a 404-on-delete as success or failure.

### What clients should do per error

| Status | Meaning | Client action |
|--------|---------|---------------|
| 204 No Content | Deleted | Done. **Do not GET to confirm** — the resource is gone. |
| 404 Not Found | Already gone | Usually treat as success (idempotent intent fulfilled). |
| Network timeout | Outcome unknown | Retry. If you get 404, the delete already happened. |

---

## "Do I need a GET after PUT/PATCH to confirm success?"

**No.** If your API returns the updated resource on 200, the response body is authoritative.

| Pattern | Round trips | Verdict |
|---------|-------------|---------|
| PUT → 200 (full body) | 1 | Best |
| PUT → 204 (no body) → GET | 2 | Avoid |
| PUT → 200 (empty `{}`) → GET | 2 | Wrong |

**Exception:** If the server applies computed side effects (auto-generated fields, server-side timestamps, version counters, derived fields), always return the post-write state so clients see exactly what was stored, not what they sent.

---

## Retry strategy summary

```
Error type          Safe to retry?   How to retry
──────────────────────────────────────────────────────────────
422 Validation      No               Fix the request first
409 Conflict        No (same body)   GET current state, resubmit
400 Bad Request     No               Fix the request first
429 Rate Limited    Yes              Wait for Retry-After header
503 Unavailable     Yes              Exponential backoff
500 Server Error    Maybe            Backoff; check for side effects first

Method timeout:
  GET               Yes              Idempotent, no side effects
  PUT               Yes              Idempotent, same result
  PATCH (set)       Yes              Idempotent, same result
  PATCH (delta)     No (same body)   GET first, verify, conditional retry
  POST              No (same body)   Use Idempotency-Key or GET first
  DELETE            Yes              404 on retry = already deleted
```

---

---

## POST strategies: Insert vs Upsert vs Merge

POST can mean three different things depending on how you design the endpoint. Choosing the wrong one causes data loss, hidden bugs, or excess round-trips.

### Strategy comparison

| Strategy | If resource exists | If resource missing | Idempotent? | 409 possible? | Best for |
|----------|-------------------|---------------------|-------------|---------------|---------|
| **POST + Insert** | 409 Conflict | 201 Created | No | Yes | Strict create; audit trail; user forms |
| **POST + Upsert** | 200 OK (full replace) | 201 Created | Yes | No | Sync jobs; config; bulk import |
| **POST + Merge** | 200 OK (patch in place) | 201 Created | Yes (set ops) | No | Event pipelines; partial ownership; incremental sync |

---

### POST + Insert (strict create)

The default REST convention. POST creates a new resource and fails with 409 if one with the same identity already exists.

**How it works:**
1. Check for existence by natural key (name) or let the DB unique constraint fire.
2. If found → 409 Conflict with a clear message telling the client what to do next.
3. If not found → insert → 201 Created + Location header + body with the new resource.

**Advantages:**
- Explicit — the client always knows whether a create or an update happened.
- Bugs surface. If two clients try to create the same record, one gets 409. Without this, duplicate records accumulate silently.
- Clean audit trail: a 201 means a first-ever create event. Useful for event sourcing and compliance.
- Client tracks server-assigned IDs and uses them for all future calls (PUT, DELETE). Identity is unambiguous.

**Disadvantages:**
- Not idempotent — requires an Idempotency-Key for safe retries.
- If the client doesn't know whether the resource exists, it must GET first (extra round-trip), or handle 409 by switching to a PUT.
- In bulk import scenarios, checking each record individually before inserting is expensive.

**Use when:**
- User submits a creation form and should see an error if the name is already taken.
- Financial records, orders, invoices — accidental overwrites must be impossible.
- You need a clear "first created at" timestamp for auditing.

---

### POST + Upsert (create or full replace)

The client sends the full desired state. The server creates the resource if it doesn't exist, or fully replaces it if it does. The client doesn't need to know which happened — the 200 vs 201 response code tells them after the fact.

**How it works:**
1. Look up the resource by natural key.
2. If not found → create → 201 Created.
3. If found → replace all fields with the request body → 200 OK.

**Advantages:**
- Idempotent: repeating the same upsert always converges to the same state. Safe to retry on timeout without Idempotency-Key.
- Simple client code: no GET-then-decide logic, no handling 409.
- Natural for sync: "make the server's state match this snapshot."
- Batch-friendly: send 1000 records and the server reconciles each one.

**Disadvantages:**
- **Silently overwrites.** A client with stale data will replace newer data without any error signal. This is the biggest risk.
- No 409 means duplicate-name bugs in client code go undetected.
- Audit trail blurred: you can't distinguish "first create" from "100th re-sync" from the response alone. You'd need to log the pre-update state server-side.
- Race condition: two concurrent upserts with different values → last writer wins unconditionally.

**Use when:**
- Reconciliation/sync jobs: "apply this external snapshot to our database."
- Configuration management: "ensure this feature flag has these settings."
- Importing from an external system that owns the record identity.
- The client always has complete, authoritative knowledge of the full desired state.

---

### POST + Merge (create or partial update)

The client sends only the fields it wants to change. The server creates the resource if it doesn't exist, or applies only the provided fields to the existing record (patch semantics). Omitted fields are untouched.

**How it works:**
1. Look up the resource by natural key.
2. If not found → create (price and category required for a valid new product) → 201 Created.
3. If found → apply only non-null fields from the request → 200 OK.

**Advantages:**
- Services can own different fields: a pricing service merges only price; an inventory service merges only stock. They don't step on each other (as long as they don't touch the same fields).
- Small payloads: only send what changed.
- Event-driven fit: each event ("price updated to X") is a merge that applies exactly one kind of change.
- Idempotent for set-type operations: replaying the same event produces the same state.

**Disadvantages:**
- **Intentional null is impossible.** Omitting a field means "leave it alone" — you cannot use merge to explicitly clear a field to null. For that you need a PUT or a dedicated "clear" endpoint.
- **Dual-mode validation is subtle.** Fields required on create (price, category) are optional on update. This logic is easy to get wrong and hard to document clearly to API consumers.
- Harder to reason about: the final state is the result of layering the incoming patch over the existing record. To debug, you need both states.
- Field ownership conflicts: if two services merge the same field with different values, last writer wins with no detection.

**Use when:**
- Multiple independent services each own a different subset of fields on a shared resource.
- Event-driven: each event is "apply this specific change" rather than "replace the whole record."
- Mobile or low-bandwidth clients that can only send deltas.
- Progressive data collection: fill in fields as they become available (onboarding flow).

---

### The stale-write problem

All three strategies share a vulnerability: a client operating on stale data can overwrite newer data written by another client.

```
Client A reads product v3 (price: $999)
Client B reads product v3 (price: $999)
Client B upserts: price → $1099 → server is now v4
Client A upserts: price → $799  → server is now v5
Result: Client B's update is silently lost
```

**Solution: optimistic locking via a version field:**

```
POST /api/products/upsert
{ "name": "iPhone 15", "price": 799.99, "version": 3 }

Server: current version is 4 → reject
→ 409 Conflict: "Resource was modified (expected version 3, current is 4)"
```

Client then GET the fresh record, re-applies changes, and retries with version 4.

Spring Data JPA supports this with `@Version` on an entity field — JPA handles the version check and increment automatically. A `OptimisticLockException` is thrown on mismatch, which you map to a 409.

---

## Optimistic locking (next level)

In concurrent systems, two clients may read the same resource, both modify it, and both try to save. The second save silently overwrites the first ("lost update").

**Solution:** Add a `version` field to the resource. The client includes the version it read in the PUT request:

```
PUT /api/products/5
If-Match: "version-7"

{ "name": "Widget Pro", "price": 34.99, "version": 7 }
```

Server checks: if the current version is still 7, apply the update and increment to 8. If it's already 8, someone else got there first — return **409 Conflict**. The client must GET the fresh resource, re-apply their changes, and retry.

Spring Data JPA supports this natively with `@Version` on an entity field.
