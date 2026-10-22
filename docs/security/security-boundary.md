# ForgeAI Security Boundary & Trust Architecture

**Specification Version:** 1.1.0  
**Effective Date:** October 2026  
**Status:** ACTIVE / ENFORCED  

---

## 1. Executive Summary & Four-Tier Security Taxonomy

ForgeAI employs an Edge-Enforced, Multi-Tenant Security Boundary architecture designed to protect distributed microservices from unauthenticated access, tenant context spoofing, unauthorized operational mutations, and cross-tenant data exfiltration.

To prevent overstating guarantees or confusing boundary layers, all security controls in ForgeAI are classified into four distinct architectural tiers:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        1. CRYPTOGRAPHICALLY VERIFIED                   │
│   • Asymmetric RS256 JWT signature verification via Identity JWKS      │
│   • JWT token expiration (`exp`) and not-before (`nbf`) claims        │
│   • GitHub App Webhook HMAC-SHA256 signature (`X-Hub-Signature-256`)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                        2. GATEWAY-ATTESTED                             │
│   • Inbound client identity header stripping (X-User-Id, X-Org-Id, ...)│
│   • Gateway tenant membership resolution via Identity & Redis cache    │
│   • Normalized, edge-verified downstream header injection              │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                        3. NETWORK-TRUSTED (Option B Model)             │
│   • Zero host port publishing for domain services in production        │
│   • Private bridge networks (`forgeai-internal` / `forgeai-network`)   │
│   • `X-ForgeAI-Internal: attested` trusted-network marker              │
│   • Downstream `TenantContextFilter` neutralizes unattested headers    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│                        4. DATABASE-ENFORCED                            │
│   • Structured `organization_id NOT NULL` column on `outbox_events`   │
│   • Quarantined outbox events isolation table                          │
│   • Tenant column constraints, foreign keys, and PostgreSQL RLS        │
└────────────────────────────────────────────────────────────────────────┘
```

The edge of the system is guarded by the **API Gateway** (Spring Cloud Gateway WebFlux). Downstream domain services (`identity-service`, `project-service`, `source-control-service`, `testing-service`, and `ai-service`) operate in an isolated internal network (`forgeai-internal`) and do not accept untrusted identity headers from the internet.

```
                  Internet / Untrusted Clients
                               │
               ┌───────────────┴───────────────┐
               │                               │
        Port 80/443 (HTTP/S)             Port 8080 (REST/API)
               │                               │
       ┌───────▼────────┐             ┌────────▼────────┐
       │ Next.js Web UI │             │   API Gateway   │
       │   (Frontend)   │             │ (Spring Cloud)  │
       └────────────────┘             └────────┬────────┘
                                               │
               ═══════════ TRUST BOUNDARY ═════╧═══════════
               Strip: X-User-Id, X-Org-Id, X-Organization-Id, X-User-Roles, X-Permissions
               Enforce: JWT signature (RS256), expiration, session revocation
               Inject: Normalized X-User-Id, X-Org-Id, X-Permissions
               Stamp: X-ForgeAI-Internal: attested (trusted-network marker)
                                               │
               ┌───────────────────────────────┼──────────────────────────────┐
               │ Isolated Internal Network     │                              │
        ┌──────▼──────┐                 ┌──────▼──────┐                ┌──────▼──────┐
        │  Identity   │                 │   Project   │                │   Source    │
        │   Service   │                 │   Service   │                │   Control   │
        └──────┬──────┘                 └──────┬──────┘                └──────┬──────┘
               │                               │                              │
               └───────────────────────┬───────┴──────────────────────────────┘
                                       │
                               ┌───────▼───────┐
                               │  Testing & AI │
                               │   Services    │
                               └───────────────┘
```

---

## 2. Route Classification Matrix

Every endpoint across ForgeAI is classified into one of four security zones:

| Classification | Accessibility | Authentication Requirement | Tenant Context Requirement |
| :--- | :--- | :--- | :--- |
| **PUBLIC** | External via Gateway | None | None |
| **AUTHENTICATED** | External via Gateway | Valid JWT Bearer Token | Optional / User-Scoped or Tenant-Scoped |
| **OPERATOR / ADMIN** | External via Gateway | Valid JWT with Admin/Operator Role | Mandatory Tenant Context |
| **INTERNAL ONLY** | Internal Network Only | Gateway-attested headers / Internal VPC | Validated Internal Headers |

---

### 2.1 Public Endpoints

Public routes require no authentication and are exposed to internet clients for initial authentication, discovery, and automated webhooks:

| Service | Method | Route Pattern | Description | Security Controls |
| :--- | :--- | :--- | :--- | :--- |
| **API Gateway** | `OPTIONS` | `/**` | CORS Preflight Requests | CORS configuration filter |
| **API Gateway** | `GET` | `/actuator/health`, `/actuator/info` | Edge Gateway liveness/readiness | Actuator web security |
| **Identity Service** | `GET` | `/.well-known/jwks.json` | Public RSA JWKS key set for JWT verification | Read-only public keys (`n`, `e`, `kid`) |
| **Identity Service** | `POST` | `/api/v1/auth/login` | User authentication & JWT issuance | Rate limited (20 req burst), password hash check |
| **Identity Service** | `POST` | `/api/v1/auth/register` | Tenant / user initial registration | Rate limited, email/username validation |
| **Identity Service** | `POST` | `/api/v1/auth/refresh` | Refresh access token via refresh token | Token rotation & session verification |
| **Source Control** | `POST` | `/api/v1/webhooks/github` | GitHub App Webhook Ingress | **HMAC-SHA256 Signature Verification** (`X-Hub-Signature-256`), Idempotency deduplication |
| **AI Service** | `GET` | `/api/v1/ai/health`, `/api/v1/ai/healthz` | AI service runtime health check | Read-only status report |

---

### 2.2 Authenticated Endpoints (Tenant-Scoped & User-Scoped)

All non-public routes under `/api/v1/**` require a cryptographically valid, non-expired, non-revoked JWT in the `Authorization: Bearer <token>` header.

#### User-Scoped Authenticated Endpoints
These routes identify the user by `sub` claim and allow cross-organization navigation or profile management:
- `GET /api/v1/organizations` — List organizations the authenticated user belongs to.
- `POST /api/v1/organizations` — Create a new organization (user becomes `OWNER`).
- `GET /api/v1/users/me` — Retrieve current authenticated user profile.
- `POST /api/v1/auth/logout` — Revoke current session.
- `POST /api/v1/auth/logout-all` — Revoke all sessions for current user.

#### Tenant-Scoped Authenticated Endpoints
These routes require both a valid user JWT and an attested tenant context (`X-Org-Id`):
- `/api/v1/projects/**` (Project Service) — Projects, requirements, tasks, traceability matrix.
- `/api/v1/source-control/**` (Source Control Service) — Repositories, PRs, commits, branches.
- `/api/v1/repositories/**` (Source Control Service) — Connected repository catalog.
- `/api/v1/pull-requests/**` (Source Control Service) — PR ingestion and telemetry.
- `/api/v1/installations/**` (Source Control Service) — GitHub App installation claims.
- `/api/v1/testing/**` (Testing Service) — Test runs, suites, cases, execution triggers.
- `/api/v1/test-runs/**` (Testing Service) — Test run telemetry and results.
- `/api/v1/ai/**` (AI Service) — Code analysis, test generation, test gap explanation.

---

### 2.3 Operator & Organization-Admin Endpoints

Operational APIs under `/api/v1/operations/**` provide diagnostics, resilience controls, and failure recovery. They require elevated privileges:

| Service | Method | Route Pattern | Privilege Required | Tenant Scope | Description |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Source Control** | `GET` | `/api/v1/operations/outbox/stats` | Authenticated Tenant | **Strictly Tenant-Scoped** | Outbox queue depths (pending, retrying, dead-letter) for caller's organization. Global fallback is disabled. |
| **Source Control** | `GET` | `/api/v1/operations/github/stats` | Authenticated User | Platform | GitHub App installation count and rate limit quota |
| **Source Control** | `GET` | `/api/v1/operations/outbox/dead-letter` | **Authenticated Tenant** | **Strictly Tenant-Scoped** | List dead-letter outbox events for caller's organization. |
| **Source Control** | `POST` | `/api/v1/operations/outbox/events/{id}/replay` | **Operator / Admin Role** (`ROLE_ADMIN`, `ROLE_OPERATOR`, `outbox:replay`, `org:admin`) | **Strictly Tenant-Scoped** | Replay dead-letter outbox event. Validates `entity.organization_id == caller.organization_id` using the structured column (never payload string matching). Denies null-tenant events with HTTP 403 and records an `OUTBOX_REPLAY_DENIED` audit event. |
| **Project Service** | `GET` | `/api/v1/operations/circuit-breakers` | Authenticated User / Operator | Platform | State of circuit breakers (`CLOSED`, `OPEN`, `HALF_OPEN`) |
| **Project Service** | `GET` | `/api/v1/operations/traceability/stats` | Authenticated User | Platform | Synchronization metrics and gap counts |
| **Project / Source**| `GET` | `/api/v1/operations/audit-events` | **Operator / Admin Role** | **Strictly Tenant-Scoped** | Immutable audit event log for the authenticated tenant. Unprivileged callers are rejected with HTTP 403. |

---

### 2.4 Internal-Only Service Endpoints

These endpoints are strictly for inter-service RPC over the internal Docker network and must never be routed to external clients:

- `http://source-control-service:8082/api/v1/internal/**`
- `http://testing-service:8083/api/v1/internal/**`
- Direct database connections (`postgres:5432`)
- Direct Redis commands (`redis:6379`)

---

## 3. Trusted Header Rules & Gateway Ingress Policy

### 3.1 Header Stripping Boundary
The API Gateway unconditionally strips the following client-supplied headers on **EVERY** inbound HTTP request before executing filters:

- `X-User-Id`
- `X-Org-Id`
- `X-Organization-Id`
- `X-User-Roles`
- `X-Permissions`

Clients attempting to inject or spoof these headers will have them eliminated at the gateway boundary.

### 3.2 Normalized Header Injection
Downstream services consume identity context that has been normalized and stamped by the API Gateway after authentication:

1. **`X-User-Id`**: Derived strictly from JWT `sub` (subject claim).
2. **`X-Org-Id` / `X-Organization-Id`**: Derived from:
   - JWT `org_id` claim (if present), verified against any client-requested `X-Org-Id`. If client supplied a conflicting `X-Org-Id`, the Gateway terminates the request with **HTTP 403 Forbidden**.
   - If JWT has no `org_id` claim, the Gateway queries `identity-service` (with Redis caching) to verify user membership in the requested organization. If the user is not a member, the Gateway terminates the request with **HTTP 403 Forbidden**.
3. **`X-User-Roles`**: Derived strictly from JWT `role` claim.
4. **`X-Permissions`**: Derived strictly from JWT `permissions` array claim.
5. **`X-ForgeAI-Internal`**: Stamped as `attested` by the API Gateway to signify edge processing.

### 3.3 Internal Trust Model (Option B — Explicit Trusted-Network Model)
ForgeAI explicitly models internal inter-service trust under **Option B (Trusted-Network Model)**:
- **No False Claims:** The marker `X-ForgeAI-Internal: attested` is a **trusted-network marker**, NOT cryptographic attestation. It does not contain an asymmetric digital signature.
- **Production Isolation:** In production deployments (`docker-compose.prod.yml`), internal services are unexposed to the internet. No host ports are published for backend domain services, database, or Redis.
- **Defense in Depth via `TenantContextFilter`:** Downstream services enforce `forgeai.security.enforce-internal-marker: ${ENFORCE_INTERNAL_MARKER:true}`. If a request arrives without `X-ForgeAI-Internal: attested`, the filter intercepts it and neutralizes any spoofed identity headers (`X-Org-Id`, `X-User-Id`, `X-User-Roles`, `X-Permissions`) using an `HttpServletRequestWrapper`, preventing unauthorized context escalation even if an internal host port was inadvertently reached.
- **Inter-Service Clients:** Authorized inter-service HTTP clients (`SourceControlClient`, `TestingServiceClient`, `AiServiceClient`) include `X-ForgeAI-Internal: attested` when initiating internal service-to-service requests.

---

## 4. Production Network Isolation Policy

### 4.1 Production Environment (`docker-compose.prod.yml`)
In production:
- **ONLY `api-gateway` (port 8080) and `frontend` (port 3000)** publish host ports.
- `postgres`, `redis`, `identity-service`, `project-service`, `source-control-service`, `testing-service`, and `ai-service` have **NO published host ports** (`ports:` block omitted).
- All inter-service communication occurs across the private Docker network `forgeai-internal`.
- External attackers cannot bypass the gateway by directly connecting to port 8081, 8082, 8083, 8084, 8085, 5432, or 6379.

### 4.2 Development Environment (`docker-compose.dev.yml`)
In local development:
- Direct service ports (5432, 6379, 8081, 8082, 8083, 8084, 8085) may be exposed to `localhost` solely for debugging, database inspection, and direct unit/integration test execution.
- Security boundary tests verify that `docker-compose.prod.yml` never exposes these internal ports.

---

## 5. Security Error Contract

To prevent information disclosure and enumeration attacks:

| HTTP Status | Meaning | Disclosure Policy |
| :--- | :--- | :--- |
| **`401 Unauthorized`** | Missing, malformed, expired, or revoked JWT | Never leak reason details or internal stack trace. Return `Unauthorized`. |
| **`403 Forbidden`** | Authenticated caller lacks permissions, attempted tenant spoofing, or cross-tenant access | Never reveal details of target resource or organization topology. Return generic `Forbidden`. |
| **`404 Not Found`** | Resource does not exist, OR belongs to another tenant | When an entity belongs to another tenant, APIs return `404 Not Found` or `403 Forbidden` to prevent ID enumeration. |
| **`503 Service Unavailable`** | Infrastructure dependency offline (e.g. Redis during privileged revocation check) | Clear error message indicating temporary service unavailability. |

Correlation IDs (`X-Correlation-Id`) and W3C Trace Context (`traceparent` / `traceId`) are always preserved across all error responses for observability and auditing.
