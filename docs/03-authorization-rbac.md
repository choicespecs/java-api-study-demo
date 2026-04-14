# Authorization & RBAC

Authorization answers: **"What are you allowed to do?"**  
(Authentication must happen first — you must know WHO someone is before deciding WHAT they can do.)

---

## Role-Based Access Control (RBAC)

RBAC assigns permissions to **roles**, then assigns roles to users.

```
User → assigned → Roles → have → Permissions → grant access to → Resources
```

Roles in this demo:
```
ROLE_ADMIN  → can do everything
ROLE_USER   → can read and write own data
ROLE_VIEWER → can only read
```

### Two Ways to Enforce Roles in Spring Security

**1. URL-level rules (in SecurityConfig) — coarse-grained:**
```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/rbac/admin/**").hasRole("ADMIN")
    .requestMatchers("/api/rbac/user/**").hasAnyRole("USER", "ADMIN")
    .requestMatchers("/api/rbac/viewer/**").hasAnyRole("VIEWER", "USER", "ADMIN")
    .anyRequest().authenticated())
```

**2. Method-level rules (@PreAuthorize) — fine-grained:**
```java
@PreAuthorize("hasRole('ADMIN')")
public ResponseEntity<?> adminOnly() { ... }

// Combine role + ownership (ABAC pattern):
@PreAuthorize("hasRole('USER') and #userId == authentication.name")
public ResponseEntity<?> myData(@PathVariable String userId) { ... }
```

`@EnableMethodSecurity` in `SecurityConfig` enables the `@PreAuthorize` annotations.

### Demo

```bash
# Get a JWT first
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | jq -r .accessToken)

# VIEWER level — all roles can access
curl http://localhost:8080/api/rbac/viewer -H "Authorization: Bearer $TOKEN"

# USER level — viewer cannot access
VIEWER_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"viewer","password":"password"}' | jq -r .accessToken)
curl http://localhost:8080/api/rbac/user -H "Authorization: Bearer $VIEWER_TOKEN"
# → 403 Forbidden

# ADMIN level — only admin can access
curl http://localhost:8080/api/rbac/admin -H "Authorization: Bearer $TOKEN"
# → 403 Forbidden (user doesn't have ADMIN role)

ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password"}' | jq -r .accessToken)
curl http://localhost:8080/api/rbac/admin -H "Authorization: Bearer $ADMIN_TOKEN"
# → 200 OK

# @PreAuthorize ownership check
curl "http://localhost:8080/api/rbac/method-security/my-data/user" \
  -H "Authorization: Bearer $TOKEN"
# → 200 OK (user accessing their own data)

curl "http://localhost:8080/api/rbac/method-security/my-data/admin" \
  -H "Authorization: Bearer $TOKEN"
# → 403 Forbidden (user cannot access admin's data)
```

---

## 401 vs 403 — Common Confusion

| Status | Meaning | When to use |
|--------|---------|-------------|
| `401 Unauthorized` | Not authenticated — we don't know who you are | Missing/invalid credentials |
| `403 Forbidden` | Authenticated but not authorized — we know who you are, but you can't do this | Valid credentials, wrong role |

The 401 name is misleading — it really means "unauthenticated", not "unauthorized".

---

## Beyond RBAC: ABAC (Attribute-Based Access Control)

RBAC assigns permissions based on roles alone.  
ABAC considers additional attributes: resource owner, time, location, etc.

```java
// RBAC: any USER can access
@PreAuthorize("hasRole('USER')")

// ABAC: only the OWNER can access their own data
@PreAuthorize("hasRole('USER') and #userId == authentication.name")

// ABAC: admin or owner
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
```

---

## Spring Security's "ROLE_" Prefix Convention

Spring Security strips the `ROLE_` prefix when using `hasRole()`:
```java
hasRole("ADMIN")      // checks for "ROLE_ADMIN" authority
hasAuthority("ROLE_ADMIN")  // checks for exact string "ROLE_ADMIN"
```

Both work, but be consistent.

---

## Role Hierarchy

Instead of listing all roles in every `hasAnyRole()`, configure a hierarchy:

```java
@Bean
public RoleHierarchy roleHierarchy() {
    RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
    hierarchy.setHierarchy("""
        ROLE_ADMIN > ROLE_USER
        ROLE_USER > ROLE_VIEWER
        """);
    return hierarchy;
}
```

Then `hasRole("VIEWER")` automatically passes for USER and ADMIN.

---

## Common Authorization Mistakes

### 1. Only checking authorization in the UI
The server MUST enforce authorization on every API call.
Never rely on hiding a button as security.

### 2. Insecure Direct Object Reference (IDOR)
```
GET /api/orders/12345  ← if any authenticated user can access any order ID, that's IDOR
```
Always verify the requester owns or has access to the specific resource:
```java
if (!order.getOwnerId().equals(currentUser)) {
    throw new AccessDeniedException("Not your order");
}
```

### 3. Missing authorization on write operations
Sometimes READ endpoints are protected but CREATE/UPDATE/DELETE are forgotten.
Every endpoint needs authorization checks.
