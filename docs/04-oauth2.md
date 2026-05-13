# OAuth2 & OpenID Connect

OAuth2 solves: **"How does App B access your data on App A without your password?"**

Example: "Login with Google" — Google is the Authorization Server, your app is the Client.

---

## Why OAuth2?

### Benefits

| Benefit | Explanation |
|---------|-------------|
| **No password sharing** | Credentials never leave the authorization server. Third-party apps receive short-lived tokens, not your password. |
| **Scoped access** | Tokens carry explicit permissions (`read`, `write`, `admin`). An app cannot exceed what the user granted — even if compromised. |
| **Short-lived tokens** | Access tokens expire in minutes or hours. A leaked token has a narrow window of usefulness. |
| **Revocable** | Tokens can be invalidated server-side instantly — no password reset required. |
| **Delegated access without impersonation** | A token proves a user *authorized* an action; it does not grant full account access. |
| **Separation of concerns** | Authentication ("who are you?") and authorization ("what can you do?") are handled by dedicated services like Auth0, Okta, or Keycloak — not your app. |
| **Industry standard** | Every major platform (Google, GitHub, AWS, Stripe) speaks OAuth2. Learn it once, integrate anywhere. |

### Drawbacks

| Drawback | Explanation |
|----------|-------------|
| **Complexity** | Authorization Code flow has 6+ steps across browser, client, and server. Basic Auth is one round-trip. |
| **Requires an Authorization Server** | You need a dedicated service or embedded server (e.g. Spring Authorization Server). Adds infrastructure cost and a new failure point. |
| **Extra latency** | Client Credentials flow adds a token request before every fresh session. Auth Code flow involves multiple browser redirects. |
| **Token management overhead** | Clients must store, refresh, and rotate tokens. A stale token causes a 401 mid-session. |
| **Harder to debug** | Opaque tokens reveal nothing. JWTs require JWKS lookup and signature verification to inspect. |
| **Overkill for simple cases** | Internal, single-tenant APIs with a handful of trusted clients are often better served by API keys or mTLS. |

### OAuth2 vs Other Auth Methods

| Method | Best for | Password exposed? | Revocable? | Scoped? | Complexity |
|--------|----------|-------------------|-----------|---------|------------|
| Basic Auth | Internal tools, server-to-server over TLS | Every request | No (change password) | No | Minimal |
| API Key | Developer-facing APIs, simple clients | Every request | Yes (key rotation) | Rarely | Low |
| JWT (custom) | Stateless microservices, mobile apps | Never | Hard (requires blocklist) | Yes (claims) | Medium |
| **OAuth2** | **Third-party delegation, public-facing APIs** | **Never** | **Yes (server-side)** | **Yes (scopes)** | **High** |
| mTLS | Zero-trust service mesh, partner APIs | Never | Yes (cert revocation) | No | High |

**Rule of thumb:** reach for OAuth2 when a user is granting a third-party app access to their data, or when you need fine-grained scope control across multiple resource servers. For internal M2M with no user involved, API keys or mTLS are often simpler and equally secure.

---

## Roles in OAuth2

| Role | Description | In this demo |
|------|-------------|--------------|
| **Resource Owner** | The user who owns the data | You (logging in) |
| **Client** | The app requesting access | `web-client` or `machine-client` |
| **Authorization Server (AS)** | Issues tokens after user consent | This app at `localhost:8080` |
| **Resource Server (RS)** | Hosts the protected data | Also this app at `/api/oauth/**` |

In production, the AS is usually a separate service (Keycloak, Auth0, Okta, AWS Cognito).

---

## Grant Types (Flows)

### 1. Client Credentials (Machine-to-Machine)

No user involved. A backend service authenticates itself.

```
Client → POST /oauth2/token (client_id + secret) → AS → access_token
Client → GET /api/oauth/data (Bearer token) → RS → data
```

**Test:**
```bash
# Get token
curl -X POST http://localhost:8080/oauth2/token \
  -u machine-client:machine-secret \
  -d "grant_type=client_credentials&scope=read"

# Returns: {"access_token":"eyJ...","token_type":"Bearer","expires_in":3600}

# Use token
curl http://localhost:8080/api/oauth/data \
  -H "Authorization: Bearer <access_token>"
```

### 2. Authorization Code (User grants access to a web app)

The full OAuth2 flow. A user approves a client app's access to their data.

```
User → Browser → Client App
Client App → redirect → AS /oauth2/authorize
AS → login + consent screen
User → approves
AS → redirect → Client redirect_uri?code=AUTH_CODE
Client → POST /oauth2/token (code) → AS → access_token + refresh_token
Client → GET /api/oauth/data (Bearer token) → RS → data
```

**Test (browser):**

1. Open this URL in a browser:
```
http://localhost:8080/oauth2/authorize?client_id=web-client&response_type=code&redirect_uri=http://localhost:8080/api/oauth/callback&scope=openid+read
```

2. Login with `admin` / `password`

3. Approve the consent screen

4. You're redirected to `/api/oauth/callback?code=<AUTH_CODE>`

5. Exchange the code:
```bash
curl -X POST http://localhost:8080/oauth2/token \
  -u web-client:web-secret \
  -d "grant_type=authorization_code&code=<CODE>&redirect_uri=http://localhost:8080/api/oauth/callback"
```

---

## Scopes vs Roles

| Concept | Answers | Example |
|---------|---------|---------|
| **Role** | What the **user** can do | `ROLE_ADMIN` can delete records |
| **Scope** | What the **client app** can do | `read` scope allows reading; `write` allows writes |

A user with ADMIN role using a `read`-scoped token can only read — the scope is more restrictive.

Spring Security maps scopes to authorities as `SCOPE_read`, `SCOPE_write`:
```java
.requestMatchers("/api/oauth/write").hasAuthority("SCOPE_write")
```

---

## Token Signing: RSA vs HMAC

| | RSA (Asymmetric) | HMAC (Symmetric) |
|-|------------------|------------------|
| **Signs with** | Private key | Shared secret |
| **Verifies with** | Public key | Same shared secret |
| **Use when** | Multiple services verify tokens | Single service signs and verifies |
| **This demo** | OAuth2 tokens (AS uses RSA) | Custom JWT tokens (JJWT uses HMAC) |
| **Public key at** | `GET /oauth2/jwks` | N/A (shared secret) |

---

## PKCE (Proof Key for Code Exchange)

PKCE prevents authorization code interception attacks. Required for public clients (mobile apps, SPAs) where a client secret cannot be kept confidential.

```
Client generates: code_verifier (random string)
                  code_challenge = SHA256(code_verifier)

/oauth2/authorize?...&code_challenge=<hash>&code_challenge_method=S256
# AS stores the challenge

/oauth2/token  →  includes code_verifier
# AS verifies SHA256(code_verifier) == stored code_challenge
# An intercepted code is useless without the code_verifier
```

Enable PKCE in this demo by setting `requireProofKey(true)` in `AuthorizationServerConfig`.

---

## OpenID Connect (OIDC)

OIDC is an identity layer built on top of OAuth2. It adds:
- **ID Token**: a JWT containing user identity (sub, name, email, etc.)
- **UserInfo endpoint**: `GET /userinfo` — fetch more user attributes

Request with `scope=openid` to get an ID token alongside the access token.

**Discovery:** `GET /.well-known/openid-configuration` — machine-readable AS capabilities.

---

## Refresh Token Security

Refresh tokens are long-lived credentials. Protect them:

1. **Store server-side** (in DB or Redis) to enable revocation
2. **Rotate on each use** — issue a new refresh token with each access token refresh
3. **Detect token reuse** — if a rotated (revoked) refresh token is used, revoke the entire session (indicates theft)
4. **Bind to device/IP** (optional) — flag unusual access patterns
