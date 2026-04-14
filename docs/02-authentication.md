# Authentication

Authentication answers: **"Who are you?"**

This demo implements four common authentication mechanisms. Each has different tradeoffs.

---

## 1. HTTP Basic Authentication

**How it works:**
```
Client → Authorization: Basic base64("username:password") → Server
```

The server decodes the Base64 string and validates the credentials on every request.

**Demo:**
```bash
# Login as user
curl -u user:password http://localhost:8080/api/basic/protected

# Admin-only endpoint (403 with user role)
curl -u user:password http://localhost:8080/api/basic/admin

# Admin can access it
curl -u admin:password http://localhost:8080/api/basic/admin
```

**Pros:**
- Simple to implement and test
- Built into every HTTP client and browser
- Good for server-to-server with mutual trust

**Cons:**
- Credentials sent on every request (always use HTTPS)
- No expiry — only "revoke" by changing the password
- Credentials visible in logs if not careful

---

## 2. JWT (JSON Web Tokens)

**Structure:** `HEADER.PAYLOAD.SIGNATURE` (three Base64URL parts, dot-separated)

```json
// Header
{"alg": "HS256", "typ": "JWT"}

// Payload (READABLE by anyone — not encrypted!)
{
  "sub": "user",
  "roles": ["ROLE_USER"],
  "iat": 1700000000,
  "exp": 1700000900,
  "token_type": "access"
}

// Signature: HMAC-SHA256(base64(header) + "." + base64(payload), secret)
```

**Demo flow:**
```bash
# 1. Login → get tokens
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}'

# Returns:
# {
#   "accessToken": "eyJ...",
#   "refreshToken": "eyJ...",
#   "tokenType": "Bearer",
#   "expiresIn": 900
# }

# 2. Use access token
curl http://localhost:8080/api/jwt/protected \
  -H "Authorization: Bearer <accessToken>"

# 3. Decode payload (educational — don't expose in prod!)
curl -X POST http://localhost:8080/api/auth/decode \
  -H "Content-Type: application/json" \
  -d '{"token":"<your_token>"}'

# 4. Refresh when expired
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken>"}'
```

**Key concepts:**
- **Access token**: short-lived (15 min), used to call APIs
- **Refresh token**: long-lived (24 hr), used only to get new access tokens
- JWT payloads are **Base64 encoded, NOT encrypted** — never put sensitive data in them
- Tokens cannot be revoked before expiry without a **token blocklist** (Redis/DB)

**Pros:**
- Stateless — server needs no session store
- Self-contained — validate with just the signature key
- Portable across services and languages

**Cons:**
- Cannot revoke a token before it expires
- If the signing key leaks, all tokens are compromised
- Slightly larger than session cookies

---

## 3. API Keys

**How it works:**
```
Client → X-API-Key: demo-api-key-user-12345 → Server → DB lookup → valid?
```

Unlike JWT, the server must look up the key in a database on every request.

**Demo:**
```bash
# User-level API key
curl http://localhost:8080/api/apikey/data \
  -H "X-API-Key: demo-api-key-user-12345"

# Admin API key
curl http://localhost:8080/api/apikey/data \
  -H "X-API-Key: demo-api-key-admin-12345"

# Expired key (will fail)
curl http://localhost:8080/api/apikey/data \
  -H "X-API-Key: demo-api-key-expired-12345"
```

**Production security notes:**
1. Store only a **SHA-256 hash** of the key (like password hashing)
2. Show the plain key **once** on creation, never again
3. Support key **rotation** (issue new key, deprecate old)
4. Rate-limit per key independently

**API Key vs JWT comparison:**

| Feature          | API Key         | JWT                       |
|------------------|-----------------|---------------------------|
| Revocable        | Instantly       | Only at expiry (or blocklist) |
| DB lookup needed | Yes             | No (just signature check) |
| Self-contained   | No              | Yes                       |
| Expiry           | Explicit column | Embedded in token         |
| Human-readable   | No              | Payload is decodable      |

---

## 4. OAuth2 (see docs/04-oauth2.md)

OAuth2 tokens (opaque or JWT) are issued by an Authorization Server and validated by Resource Servers. Covered in the OAuth2 doc.

---

## Common Authentication Mistakes

### 1. Storing plain-text passwords
```java
// BAD
user.setPassword(request.getPassword());

// GOOD
user.setPassword(passwordEncoder.encode(request.getPassword()));
```

### 2. Using MD5 or SHA1 for passwords
These are fast hashes designed for data integrity, not password storage.
Use **BCrypt**, **Argon2**, or **scrypt** — intentionally slow to resist brute force.

### 3. Returning the same error for "user not found" vs "wrong password"
Both should return `401 Unauthorized` with the same generic message.
Different messages help attackers enumerate valid usernames.

```java
// BAD
if (user == null) return "User not found";
if (!passwordMatch) return "Wrong password";

// GOOD
return "Invalid credentials"; // Same message for both
```

### 4. JWT in localStorage
localStorage is accessible to JavaScript and vulnerable to XSS.
Use **httpOnly cookies** for sensitive tokens, or implement a BFF (Backend for Frontend) pattern.

### 5. Not validating token type
An access token and a refresh token look identical (both JWTs).
Always check the `token_type` claim to prevent refresh tokens being used as access tokens.
