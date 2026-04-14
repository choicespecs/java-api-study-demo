# REST API Study Demo

A Spring Boot application that teaches and demonstrates core REST API concepts through working, explorable code.

## Quick Start

```bash
mvn spring-boot:run
```

Then open **http://localhost:8080** — the interactive UI lets you explore every concept hands-on without leaving the browser.

| Interface | URL | Purpose |
|-----------|-----|---------|
| **Interactive UI** | http://localhost:8080 | Visual demos, click-to-run API calls |
| **Swagger UI** | http://localhost:8080/swagger-ui.html | Full API docs, raw endpoint testing |
| **H2 Console** | http://localhost:8080/h2-console | Inspect the in-memory database |
| **Actuator** | http://localhost:8080/actuator/circuitbreakers | Live circuit breaker state |

H2 connection: JDBC URL `jdbc:h2:mem:apidemodb`, user `sa`, password `password`.

## Prerequisites

- Java 17+
- Maven 3.6+

---

## Demo Credentials

### Users (Basic Auth and JWT)

| Username | Password | Roles |
|----------|----------|-------|
| `admin`  | `password` | ROLE_ADMIN, ROLE_USER |
| `user`   | `password` | ROLE_USER |
| `viewer` | `password` | ROLE_VIEWER |

### API Keys (`X-API-Key` header)

| Key | Role |
|-----|------|
| `demo-api-key-admin-12345` | ROLE_ADMIN |
| `demo-api-key-user-12345` | ROLE_USER |
| `demo-api-key-expired-12345` | ROLE_USER (expired — will fail) |

### OAuth2 Clients (`POST /oauth2/token`)

| Client ID | Secret | Grant Type |
|-----------|--------|------------|
| `machine-client` | `machine-secret` | client_credentials |
| `web-client` | `web-secret` | authorization_code |

### Partner Keys (`X-Partner-Key` header)

| Key | Partner | Tier | Scopes |
|-----|---------|------|--------|
| `partner-alpha-key-12345` | Alpha Corp | PREMIUM | catalog:read/write, orders:read/write, analytics:read |
| `partner-beta-key-12345` | Beta Inc | STANDARD | catalog:read, orders:read/write |
| `partner-gamma-key-12345` | Gamma LLC | FREE | catalog:read |

---

## Concepts Covered

| # | Concept | Endpoints | UI Page | Documentation |
|---|---------|-----------|---------|---------------|
| 1 | HTTP Basic Auth | `/api/basic/**` | Basic Auth | [docs/02-authentication.md](docs/02-authentication.md) |
| 2 | JWT Authentication | `/api/auth/**`, `/api/jwt/**` | JWT Tokens | [docs/02-authentication.md](docs/02-authentication.md) |
| 3 | API Key Auth | `/api/apikey/**` | API Keys | [docs/02-authentication.md](docs/02-authentication.md) |
| 4 | OAuth2 + OIDC | `/api/oauth/**`, `/oauth2/**` | OAuth2 | [docs/04-oauth2.md](docs/04-oauth2.md) |
| 5 | RBAC + @PreAuthorize | `/api/rbac/**` | RBAC | [docs/03-authorization-rbac.md](docs/03-authorization-rbac.md) |
| 6 | Rate Limiting | `/api/rate/**` | Rate Limiting | [docs/05-rate-limiting.md](docs/05-rate-limiting.md) |
| 7 | Timeouts & Circuit Breaker | `/api/timeout/**` | Circuit Breaker | [docs/06-timeouts-resilience.md](docs/06-timeouts-resilience.md) |
| 8 | Hanging APIs | `/api/hanging/**` | Hanging APIs | [docs/08-hanging-apis.md](docs/08-hanging-apis.md) |
| 9 | Consuming Third-Party APIs | `/api/third-party/**` | Consuming APIs | [docs/09-third-party-apis.md](docs/09-third-party-apis.md) |
| 10 | B2B Partner Integration | `/api/partner/**` | Partner Integration | [docs/10-partner-integration.md](docs/10-partner-integration.md) |
| 11 | Pagination & Filtering | `/api/products/**` | Pagination | [docs/07-pagination.md](docs/07-pagination.md) |
| 12 | API Versioning | `/api/v1/**`, `/api/v2/**`, `/api/items/by-*` | Versioning | `VersioningController.java` |
| 13 | Error Handling | `/api/errors/**` | Error Handling | `GlobalExceptionHandler.java` |
| 14 | Common Problems | All endpoints | — | [docs/11-common-problems.md](docs/11-common-problems.md) |

---

## Interactive Frontend

The app includes a built-in SPA at `http://localhost:8080` — no separate build step, served directly by Spring Boot from `src/main/resources/static/`.

Each sidebar page provides a concept explanation, live API calls with request/response display, and visual components (JWT decoder, token bucket meter, circuit breaker state diagram, product grid).

The UI is entirely vanilla JS (no framework, no build tool). The full source is `src/main/resources/static/js/app.js`, intentionally readable as a learning resource.

---

## Quick Test Commands

### JWT Flow
```bash
# Login and capture token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user","password":"password"}' | jq -r .accessToken)

# Call protected endpoint
curl http://localhost:8080/api/jwt/protected -H "Authorization: Bearer $TOKEN"

# Admin-only (403 with user token)
curl http://localhost:8080/api/rbac/admin -H "Authorization: Bearer $TOKEN"
```

### API Key
```bash
curl http://localhost:8080/api/apikey/data -H "X-API-Key: demo-api-key-user-12345"
```

### OAuth2 Client Credentials
```bash
ACCESS_TOKEN=$(curl -s -X POST http://localhost:8080/oauth2/token \
  -u machine-client:machine-secret \
  -d "grant_type=client_credentials&scope=read" | jq -r .access_token)

curl http://localhost:8080/api/oauth/data -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Rate Limiting (trigger 429)
```bash
for i in {1..7}; do
  curl -s http://localhost:8080/api/rate/strict | jq .message
done
```

### Circuit Breaker
```bash
# Open the circuit with failures
for i in {1..6}; do curl "http://localhost:8080/api/timeout/unreliable?fail=true"; done

# Check state
curl http://localhost:8080/actuator/circuitbreakers | jq
```

### Hanging APIs
```bash
# No timeout — hangs for 5 seconds
curl "http://localhost:8080/api/hanging/no-timeout?delay=5"

# With deadline — returns 504 after 2 seconds even if delay=10
curl "http://localhost:8080/api/hanging/with-deadline?delay=10&deadline=2"
```

### Webhook Verification
```bash
# Send a valid signed webhook (server signs and posts to itself)
curl -s -X POST http://localhost:8080/api/third-party/webhook/send-test \
  -H "Content-Type: application/json" -d '{}' | jq .receiver_side

# Send a tampered payload (signature will fail)
curl -s -X POST http://localhost:8080/api/third-party/webhook/send-test \
  -H "Content-Type: application/json" -d '{"tamper":true}' | jq .receiver_side
```

### Partner Integration (B2B)
```bash
# PREMIUM partner — all scopes
curl http://localhost:8080/api/partner/auth-info \
  -H "X-Partner-Key: partner-alpha-key-12345" | jq .tier

# Tenant isolation — same endpoint, different data per key
curl http://localhost:8080/api/partner/catalog \
  -H "X-Partner-Key: partner-alpha-key-12345" | jq .count   # 3 items
curl http://localhost:8080/api/partner/catalog \
  -H "X-Partner-Key: partner-gamma-key-12345" | jq .count   # 1 item

# Scope enforcement — FREE tier cannot access analytics
curl http://localhost:8080/api/partner/audit \
  -H "X-Partner-Key: partner-gamma-key-12345" | jq .error   # INSUFFICIENT_SCOPE

# Deprecation headers on v1
curl -I http://localhost:8080/api/partner/v1/items \
  -H "X-Partner-Key: partner-alpha-key-12345" | grep -i "deprecation\|sunset"
```

### Pagination
```bash
curl "http://localhost:8080/api/products?page=0&size=5&sort=price,asc&category=Electronics"
```

---

## Project Structure

```
src/main/java/com/example/apidemo/
├── config/
│   ├── AuthorizationServerConfig.java  # OAuth2 AS (clients, RSA keys)
│   ├── SecurityConfig.java             # 6 security filter chains
│   ├── RateLimitConfig.java            # Bucket4j token buckets
│   └── OpenApiConfig.java              # Swagger/OpenAPI config
├── controller/                         # One controller per concept (15 total)
│   ├── AuthController.java             # JWT login + refresh
│   ├── BasicAuthController.java        # HTTP Basic Auth
│   ├── JwtController.java              # JWT-protected endpoints
│   ├── ApiKeyController.java           # API key auth
│   ├── OAuthController.java            # OAuth2 resource endpoints
│   ├── RbacController.java             # @PreAuthorize role checks
│   ├── RateLimitController.java        # Token bucket rate limiting
│   ├── TimeoutController.java          # Circuit breaker + Resilience4j
│   ├── HangingApiController.java       # Hanging calls + deadline patterns
│   ├── ThirdPartyApiController.java    # Consuming external APIs
│   ├── PartnerApiController.java       # B2B partner integration
│   ├── PaginationController.java       # Pageable + filtering
│   ├── VersioningController.java       # URI/header/param/Accept versioning
│   ├── ErrorDemoController.java        # RFC 7807 error scenarios
│   └── PublicController.java           # Open endpoints
├── security/
│   ├── jwt/JwtAuthFilter.java          # Extracts + validates JWT
│   └── apikey/ApiKeyAuthFilter.java    # Validates API key via DB
├── service/
│   ├── JwtService.java                 # JWT sign/verify (JJWT)
│   ├── AuthService.java                # Login + refresh logic
│   └── ExternalApiService.java         # Resilience4j demos
├── exception/GlobalExceptionHandler.java  # RFC 7807 error handling
└── data/DataInitializer.java           # Seeds demo users, keys, products
```

## Running Tests

```bash
mvn test
```

```bash
# Single test class
mvn test -Dtest=AuthControllerTest
```
