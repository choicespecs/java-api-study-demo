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

## Access Control Models Beyond RBAC

RBAC is the most common starting point, but five other models handle cases where roles alone are not expressive enough.

### Model Comparison

| Model | Access granted by | Best fit | Real-world examples | Complexity |
|-------|-------------------|----------|---------------------|------------|
| **RBAC** | User's assigned role | Most apps, clear job functions | Admin panel, CMS, SaaS tiers | Low |
| **ABAC** | Attributes of user, resource & environment | Fine-grained rules, multi-factor access | Healthcare records, financial compliance | Medium |
| **DAC** | Resource owner's discretion | User-generated content with sharing | Google Drive, Dropbox, S3 | Low–Medium |
| **MAC** | System-assigned classification labels | Government, military, high-security | SELinux, classified document systems | High |
| **ReBAC** | Graph relationship to the resource | Social/collaborative data with hierarchies | Google Docs, GitHub teams, Notion | High |
| **ACL** | Explicit per-resource permission list | OS-level or per-object control | Linux filesystem, AWS S3 bucket policies | Medium |
| **PBAC** | Centralized policy engine evaluation | Multi-service, auditable enterprise policy | OPA (Open Policy Agent), AWS Cedar | High |

---

### ABAC — Attribute-Based Access Control

ABAC asks: **"Given everything we know about this user, this resource, and this environment — should access be granted?"**

Instead of a flat role, the policy engine evaluates *attributes*: user department, resource owner, data classification, time of day, IP address.

**Benefits:**
- **Extremely fine-grained** — "only doctors in cardiology can read cardiology records during business hours"
- **No role explosion** — RBAC grows a new role every time a new combination of permissions is needed; ABAC encodes that in policy attributes
- **Ownership checks built-in** — `resource.ownerId == user.id` is a single attribute check
- **Context-aware** — time, IP, and device trust level can gate access dynamically

**Drawbacks:**
- **Policy complexity** — attribute combinations multiply fast; a policy with 5 attributes has hundreds of possible states
- **Hard to audit** — "who can access resource X?" requires evaluating every user's attributes, not a simple role lookup
- **Attribute management overhead** — user and resource attributes must be kept up-to-date or access decisions are stale

**Spring Security implementation:**

```java
// Ownership check via SpEL expression
@PreAuthorize("hasRole('USER') and #userId == authentication.name")
public ResponseEntity<?> myData(@PathVariable String userId) { ... }

// Admin or owner
@PreAuthorize("hasRole('ADMIN') or #userId == authentication.name")
public ResponseEntity<?> adminOrOwner(@PathVariable String userId) { ... }

// Time-based (use a custom bean)
@PreAuthorize("hasRole('USER') and @accessPolicy.isBusinessHours()")
public ResponseEntity<?> businessHoursOnly() { ... }
```

---

### DAC — Discretionary Access Control

DAC asks: **"Did the resource owner explicitly grant you access?"**

The owner of each resource decides who else can read, write, or share it. The system enforces what the owner declares — it does not impose rules from above.

**Benefits:**
- **User autonomy** — owners share resources without needing an admin to update roles
- **Familiar mental model** — "share with…" dialogs in Google Drive and Dropbox are DAC
- **Scales with content** — per-resource permissions don't require new roles as content grows

**Drawbacks:**
- **Accidental over-sharing** — users often default to "anyone with the link"; data leaks are common
- **No central audit trail** — IT cannot easily answer "who has access to all files containing PII?"
- **Ownership transfer is messy** — when an employee leaves, orphaned resources may have no owner

**Spring Security implementation (Spring ACL module):**

```java
// Grant read permission on a document to a user
MutableAcl acl = aclService.createAcl(objectIdentity);
acl.insertAce(acl.getEntries().size(), BasePermission.READ, new PrincipalSid("bob"), true);
aclService.updateAcl(acl);

// Check in a service method
@PreAuthorize("hasPermission(#docId, 'com.example.Document', 'read')")
public Document getDocument(Long docId) { ... }
```

---

### MAC — Mandatory Access Control

MAC asks: **"Does the subject's clearance level meet or exceed the resource's classification?"**

Access decisions are made by the *system* based on classification labels — no user, including the resource owner, can override them. The **Bell-LaPadula model** formalizes this with two rules: *no read up* (a Secret user cannot read Top Secret) and *no write down* (a Top Secret user cannot write to Confidential, preventing data leakage downward).

**Benefits:**
- **Strong guarantees** — classified data cannot leak to lower clearance levels by any user action
- **Centrally enforced** — the system sets labels; individuals cannot bypass policy, even accidentally
- **Prevents insider threats** — a malicious employee cannot downgrade and exfiltrate data

**Drawbacks:**
- **Extremely rigid** — legitimate collaboration across classification levels requires explicit policy changes
- **High administrative overhead** — every resource and user must be assigned and maintained with correct labels
- **Poor fit for most commercial apps** — justified only when regulatory or national security requirements demand it

**Spring Security implementation:**

```java
// Classification levels as granted authorities
@PreAuthorize("hasAuthority('CLEARANCE_TOP_SECRET')")
public ResponseEntity<?> topSecretEndpoint() { ... }

// Custom voter that checks clearance >= classification
public class ClearanceVoter implements AccessDecisionVoter<Object> {
    public int vote(Authentication auth, Object object, Collection<ConfigAttribute> attrs) {
        int userLevel = getClearanceLevel(auth);
        int requiredLevel = getRequiredLevel(attrs);
        return userLevel >= requiredLevel ? ACCESS_GRANTED : ACCESS_DENIED;
    }
}
```

---

### ReBAC — Relationship-Based Access Control

ReBAC asks: **"Does a path exist in the relationship graph from this user to this resource?"**

Access is determined by traversing an object relationship graph: `user → member-of → team → viewer-of → folder → parent-of → document`. Google's **Zanzibar** paper (2019) formalized this; **OpenFGA**, **Ory Keto**, and **SpiceDB** are open-source implementations.

**Benefits:**
- **Natural for hierarchies** — folder → document → comment inheritance is a first-class concept, not a hack on top of roles
- **Handles "shared with me"** — access via group membership, direct share, or inherited from a parent object all resolve the same way
- **Consistent check** — `check(user, relation, object)` answers any access question
- **Scales with data** — adding millions of objects adds tuples, not roles

**Drawbacks:**
- **Graph traversal cost** — deep hierarchies require multi-hop lookups; Zanzibar uses aggressive caching (Zookies) to compensate
- **Relationship tuple storage** — every (user, relation, object) triple must be stored; large sharing graphs require a dedicated store
- **Schema design is hard** — getting the authorization model right upfront is critical; renames break existing tuples

**OpenFGA / Zanzibar tuple model:**

```
# Authorization model (schema)
type user
type document
  relations
    define owner: [user]
    define viewer: [user, team#member] or owner
    define editor: [user] or owner

# Relationship tuples (stored facts)
user:alice  owner   document:report-q3
user:bob    viewer  document:report-q3
team:eng    viewer  document:shared-folder

# Check: can bob read report-q3?
check(user:bob, viewer, document:report-q3)  → allowed
```

```java
// Spring integration via OpenFGA SDK
OpenFgaClient fgaClient = new OpenFgaClient(config);

CheckRequest request = new CheckRequest()
    .tupleKey(new TupleKey()
        .user("user:" + username)
        ._object("document:" + docId)
        .relation("viewer"));

boolean allowed = fgaClient.check(request).getAllowed();
if (!allowed) throw new AccessDeniedException("No relationship path found");
```

---

### ACL — Access Control Lists

An ACL is a list of **(principal, permission)** pairs attached to each resource. When access is requested, the system looks up the resource's ACL and checks whether the requesting principal has the required permission listed.

**Benefits:**
- **Per-resource precision** — each object has its own independently configured permission set
- **Simple to reason about** — "who has access to this file?" is answered by reading its ACL directly
- **Well-understood** — OS, databases, cloud storage (S3 bucket policies) all implement ACLs; tooling is mature

**Drawbacks:**
- **Does not scale** — N resources × M users = enormous ACL tables; querying "what can user X access?" requires scanning every ACL
- **No inheritance** — permissions do not propagate from parent to child unless explicitly copied or re-implemented
- **Stale entries** — when a user is deleted, their ACL entries across all resources must be found and cleaned up

**Spring Security ACL implementation:**

```java
// Domain Object ACL — requires spring-security-acl dependency
// Tables: acl_class, acl_object_identity, acl_sid, acl_entry

@Autowired
private MutableAclService aclService;

public void grantPermission(Long resourceId, String username, Permission permission) {
    ObjectIdentity oi = new ObjectIdentityImpl(Document.class, resourceId);
    MutableAcl acl = (MutableAcl) aclService.readAclById(oi);
    acl.insertAce(acl.getEntries().size(), permission, new PrincipalSid(username), true);
    aclService.updateAcl(acl);
}

// Check via @PreAuthorize
@PreAuthorize("hasPermission(#resourceId, 'com.example.Document', 'write')")
public void updateDocument(Long resourceId, DocumentDto dto) { ... }
```

---

### PBAC — Policy-Based Access Control

PBAC externalizes access decisions to a **policy engine** that evaluates declarative rules at runtime. Your application asks `allowed = engine.evaluate(input)`. The engine (OPA, AWS Cedar, Casbin) consults a policy document — not hardcoded logic — and returns a decision. Policies can be updated, versioned, and tested independently of application code.

**Benefits:**
- **Decoupled from code** — policy changes ship without a code deploy; security teams update rules independently
- **Auditable and version-controlled** — policy files live in Git; every change is a diff, reviewable and traceable
- **Testable in isolation** — policy unit tests run without spinning up the application
- **Consistent across services** — a single OPA sidecar enforces the same policy across 20 microservices
- **Expressive** — OPA's Rego and Cedar can express RBAC, ABAC, and ownership checks within a single policy language

**Drawbacks:**
- **New language to learn** — Rego is non-obvious; Cedar has its own syntax; real onboarding cost
- **Latency** — every access check is an external call (or in-process library call); hot paths need caching
- **Operational overhead** — another service to deploy, monitor, and keep in sync with application data
- **Data synchronization** — the policy engine needs up-to-date user/resource data; stale input → wrong decisions

**OPA (Rego) policy example:**

```rego
package invoice.approval

default allow = false

# Managers can approve invoices for their own department up to $50,000
allow {
    input.user.role == "manager"
    input.user.department == input.invoice.department
    input.invoice.amount <= 50000
}

# Finance team can approve any invoice
allow {
    input.user.department == "finance"
}
```

**Spring Boot + OPA integration:**

```java
@Component
public class OpaAuthorizationManager {

    private final RestTemplate restTemplate;

    public boolean isAllowed(Authentication auth, Object resource) {
        Map<String, Object> input = Map.of(
            "user", Map.of("role", getRole(auth), "department", getDept(auth)),
            "resource", resource
        );
        OpaResponse response = restTemplate.postForObject(
            "http://opa:8181/v1/data/invoice/approval",
            Map.of("input", input),
            OpaResponse.class
        );
        return Boolean.TRUE.equals(response.getResult().get("allow"));
    }
}
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
