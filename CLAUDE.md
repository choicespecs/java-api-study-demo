# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run the application
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=AuthControllerTest

# Build without tests
mvn package -DskipTests

# Clean build
mvn clean package
```

## Architecture

This is a **single Spring Boot 3.3 application** (Java 17) that acts simultaneously as:
1. An **OAuth2 Authorization Server** (Spring Authorization Server)
2. An **OAuth2 Resource Server** (Spring Security)
3. A **REST API demo app** with 15 controllers covering distinct concepts
4. A **static file server** for the interactive frontend SPA

All data is stored in an **H2 in-memory database** seeded by `DataInitializer` on startup. The schema is recreated on each restart (`ddl-auto: create-drop`). There is no persistent state between restarts.

## Security Architecture — Multiple Filter Chains

The most critical architectural pattern: Spring Security allows multiple `SecurityFilterChain` beans. A request hits the **first chain whose `securityMatcher` matches**; subsequent chains are skipped entirely.

| @Order | Chain | Matches | Auth mechanism |
|--------|-------|---------|----------------|
| 1 | `AuthorizationServerConfig` | `/oauth2/**`, `/.well-known/**` | OAuth2 AS |
| 2 | `SecurityConfig.formLoginChain` | `/login`, `/logout` | Form login (for OAuth2 consent) |
| 3 | `SecurityConfig.basicAuthChain` | `/api/basic/**` | HTTP Basic |
| 4 | `SecurityConfig.apiKeyChain` | `/api/apikey/**` | `ApiKeyAuthFilter` |
| 5 | `SecurityConfig.jwtChain` | `/api/jwt/**`, `/api/rbac/**` | `JwtAuthFilter` |
| 6 | `SecurityConfig.oauthResourceChain` | `/api/oauth/**` | OAuth2 Resource Server |
| 10 | `SecurityConfig.defaultChain` | everything else | Public / permitAll |

**Key constraint:** `JwtAuthFilter` and `ApiKeyAuthFilter` are **not `@Component`** — they are instantiated as `@Bean` in `SecurityConfig` and manually added only to specific chains. Marking them `@Component` would register them in the global filter chain and apply them to every request.

**Partner API (`/api/partner/**`) and Third-Party API (`/api/third-party/**`)** use in-controller authentication (resolving `X-Partner-Key` / verifying HMAC signatures), not a dedicated Spring Security chain. They are permitted in `defaultChain`.

## Key Services

- **`JwtService`** — signs/verifies tokens using JJWT + HMAC-SHA256; secret from `app.jwt.secret` in `application.yml`
- **`ExternalApiService`** — Resilience4j demo service; methods annotated with `@CircuitBreaker`, `@TimeLimiter`, `@Retry`; `@TimeLimiter` requires `CompletableFuture` return types
- **`RateLimitConfig`** — creates Bucket4j buckets; per-IP buckets stored in `ConcurrentHashMap`; `RateLimitController` calls `tryConsumeAndReturnRemaining()` and sets `X-Rate-Limit-*` headers

## Demo Credentials (seeded by DataInitializer)

- **Users:** `admin/password` (ADMIN+USER), `user/password` (USER), `viewer/password` (VIEWER)
- **API Keys:** `demo-api-key-admin-12345` (ADMIN), `demo-api-key-user-12345` (USER)
- **OAuth2 clients:** `machine-client/machine-secret` (client_credentials), `web-client/web-secret` (authorization_code)
- **Partner keys:** `partner-alpha-key-12345` (Alpha Corp, PREMIUM), `partner-beta-key-12345` (Beta Inc, STANDARD), `partner-gamma-key-12345` (Gamma LLC, FREE)

Partner keys are defined as constants in `PartnerApiController` — they are not seeded by `DataInitializer`.

## Frontend (Interactive UI)

The app serves a vanilla JS SPA from `src/main/resources/static/`:

- **`index.html`** — app shell: `<aside id="sidebar">` + `<main id="main"><div id="content"></div></main>`
- **`css/app.css`** — design system with CSS custom properties; components include `.card`, `.jwt-parts`, `.token-meter`, `.cb-diagram`, `.product-grid`, JSON syntax highlighting classes
- **`js/app.js`** — single-file SPA (~1,800 lines):
  - Global `State` object (jwt, username, cbState, rateBuckets, pagination state)
  - `apiFetch(url, options)` — auto-injects `Authorization: Bearer` if `State.jwt` is set; always adds `Content-Type: application/json`; returns `{status, ok, body, headers}`; pass `{ noJwt: true }` to skip JWT injection (required for partner API and third-party handlers)
  - Hash-based router (`window.onhashchange` → `navigate()`) with 14 page renderer functions in the `pages` map
  - Single click listener on `#main` dispatching to `handlers[data-action]` (event delegation)
  - `handlers` object — all interactive button actions keyed by `data-action` attribute value

**Static resource security:** `/`, `/index.html`, `/css/**`, `/js/**` must remain in `requestMatchers(...).permitAll()` in `defaultChain` (`SecurityConfig`). Removing them causes 403 on the frontend assets.

## Adding New Demo Concepts

1. Create a controller in `controller/` — use educational comments + `@Tag(name="N. Name")` + `@Operation` for Swagger ordering
2. If the concept needs a dedicated security chain, add it in `SecurityConfig` with the next `@Order` and a `securityMatcher`; otherwise add the path pattern to `defaultChain` permitAll
3. Add **all** new API paths to `requestMatchers(...).permitAll()` in `defaultChain` (or to the appropriate chain)
4. In `js/app.js`: add a page renderer function, register it in the `pages` map, add a sidebar `<a>` in `index.html`, add any button handlers to the `handlers` object, and add a home dashboard card
5. Create `docs/NN-concept-name.md` with the next sequential number
6. Update `README.md` concepts table and `CLAUDE.md` docs list

## Resilience4j Notes

- `@TimeLimiter` requires `CompletableFuture<T>` return type on **both** the annotated method and its fallback
- Fallback method signature: same parameters as the annotated method **plus** a trailing `Throwable` parameter
- Circuit breaker state visible at `GET /actuator/circuitbreakers`
- Config lives in `application.yml` under `resilience4j.circuitbreaker.instances.externalApi`

## HMAC-SHA256 Pattern (used in two controllers)

Both `ThirdPartyApiController` and `PartnerApiController` use HMAC-SHA256 for webhook signature verification/generation. Key implementation details:

- Signed payload format: `"sha256=" + HexFormat.of().formatHex(mac.doFinal(...))`
- Signed payload string: `timestamp + "." + rawBody` (timestamp binds signature to this delivery, defeating replay attacks)
- Comparison: **always** `MessageDigest.isEqual(a.getBytes(), b.getBytes())` — never `String.equals()` (timing attack)
- `Mac` is not thread-safe — create a new instance per call via `Mac.getInstance("HmacSHA256")`
- Direction: `ThirdPartyApiController` receives + verifies signatures; `PartnerApiController` generates + sends them

## Documentation

Each concept has a corresponding doc in `docs/`:

| File | Covers |
|------|--------|
| `docs/02-authentication.md` | Basic Auth, JWT, API Keys |
| `docs/03-authorization-rbac.md` | RBAC, @PreAuthorize, 401 vs 403 |
| `docs/04-oauth2.md` | OAuth2 flows, scopes, PKCE, OIDC |
| `docs/05-rate-limiting.md` | Token bucket algorithm, strategies |
| `docs/06-timeouts-resilience.md` | Circuit breaker, retry, timeout patterns |
| `docs/07-pagination.md` | Offset vs cursor pagination, Spring Data Pageable |
| `docs/08-hanging-apis.md` | Thread exhaustion, CompletableFuture.orTimeout(), HTTP client timeouts |
| `docs/09-third-party-apis.md` | Webhook HMAC verification, outbound error handling, credential management |
| `docs/10-partner-integration.md` | B2B partner API: partner keys, tenant isolation, tiered limits, deprecation, outbound webhooks |
| `docs/11-common-problems.md` | CORS, N+1 queries, idempotency, and other frequent mistakes |

**Self-documenting controllers (no separate doc):** `VersioningController.java` (inline comments cover all four strategies) and `ErrorDemoController.java` + `GlobalExceptionHandler.java` (heavily commented, RFC 7807).
