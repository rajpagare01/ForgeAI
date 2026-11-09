# ForgeAI Architectural Audit & Resilience Hardening (Fixes 1–7)

> **Distributed Systems Architecture & Engineering Report**  
> **Topic:** Deep architectural audit and runtime resilience fixes across ForgeAI's microservice fleet.

---

## Overview

A comprehensive architectural and runtime audit of ForgeAI was performed across the distributed system stack (`infrastructure/api-gateway`, `services/identity-service`, `services/source-control-service`, `services/project-service`, `services/testing-service`, `services/ai-service`, and `frontend/`).

This document details the **7 concrete architectural flaws identified**, their systemic failure risks, the engineering root cause, and the production-grade fixes deployed.

---

## Fix Breakdown

### FIX 1 (ARCH-01): `SimpleCircuitBreaker` Downstream HTTP 5xx & 429 Tripping

- **Location:** `services/project-service/src/main/java/com/forgeai/project/infrastructure/client/` (`SourceControlClient.java`, `TestingServiceClient.java`, `AiServiceClient.java`)
- **Root Cause:**
  `java.net.http.HttpClient.send()` does not throw an exception on HTTP 500, 502, 503, 504, or 429 status codes. In the original implementation, the client caught no exception, logged a warning, and returned an empty result normally. Consequently, `SimpleCircuitBreaker.onSuccess()` was invoked, resetting `consecutiveFailures` to 0. This prevented the circuit from ever tripping to `OPEN` during catastrophic downstream server crashes or rate limit throttling, tricking aggregation queries into assuming 0 entities existed.
- **Architectural Solution:**
  Explicitly inspect `response.statusCode()` inside every circuit breaker supplier lambda:
  - `statusCode == 200`: Success, parse payload.
  - `statusCode == 404`: Expected cache/entity miss, return empty result without tripping circuit.
  - `statusCode >= 500 || statusCode == 429`: Throw a `RuntimeException("Downstream service returned HTTP " + response.statusCode())`.
  This allows `SimpleCircuitBreaker` to record the failure, increment its failure counter, trip to `OPEN` after 3 consecutive failures, and immediately serve fallback responses.

---

### FIX 2 (ARCH-02): RabbitMQ Event Mesh Outbox Relay Exception Propagation

- **Location:**
  - `services/*/infrastructure/**/LocalSpringEventPublisher.java`
  - `services/identity-service/.../OutboxPublisherRelay.java`
  - `services/project-service/.../OutboxPublisherRelay.java`
- **Root Cause:**
  In `LocalSpringEventPublisher.java`, when `rabbitTemplate.send(...)` failed due to RabbitMQ broker disconnection or unroutable exchanges, the catch block logged an error and swallowed the exception. Because no exception was thrown back to the caller, `OutboxPublisherRelay` assumed successful message delivery, marked the database outbox row as `status = "PUBLISHED"`, and committed the transaction. As a result, critical domain events were permanently lost.
  Additionally, the relay was deserializing payloads into generic `JsonNode` instead of strongly-typed `DomainEvent` classes, causing serialization mismatches downstream.
- **Architectural Solution:**
  1. Updated all 4 `LocalSpringEventPublisher` implementations to rethrow `IllegalStateException("RabbitMQ publish failed for event " + envelope.getEventType() + ": " + ex.getMessage(), ex)`. This forces the scheduled `OutboxPublisherRelay` to trigger its exponential backoff retry mechanism (`RETRYING` state) and eventually route to `DEAD_LETTER` if retries exhaust.
  2. Updated `OutboxPublisherRelay` in `identity-service` and `project-service` to deserialize payloads into concrete domain event types (e.g., `UserCreatedEvent`, `UserUpdatedEvent`, `OrganizationCreatedEvent`, `ProjectCreatedEvent`, `TaskStatusChangedEvent`, `CodeLinkedEvent`) before packaging into `EventEnvelope`.

---

### FIX 3 (ARCH-03): Task Code-Link Projection Enrichment for Kanban

- **Location:** `services/project-service/src/main/java/com/forgeai/project/presentation/controller/ProjectController.java`
- **Root Cause:**
  When `GET /api/v1/projects/{projectId}/tasks` was called to populate the Kanban board, the controller constructed `TaskResponse` objects without querying the associated `CodeLink` projection table. While the database contained linked commits, branches, and PRs, the response payload always returned `branchName: null` and `linkedPrNumber: null`, degrading the developer experience on the UI.
- **Architectural Solution:**
  Enriched `ProjectController.listTasks()` to fetch all active `CodeLink` records for the project's tasks, extract the latest `branchName` from `BRANCH` links, parse the `linkedPrNumber` from `PULL_REQUEST` links (e.g., `#42` $\to$ `42`), and instantiate `TaskResponse.from(task, reqKey, linkedPrNumber, branchName)`.

---

### FIX 4 (ARCH-04): HikariCP Connection Pool Exhaustion Protection in Repository Reconciliation

- **Location:** `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/application/service/RepositoryApplicationService.java`
- **Root Cause:**
  In `reconcileRepository()`, the service iterated over every changed file in a commit and performed synchronous HTTP / Git blob fetches (`getFileContent()`) while holding an open JDBC transaction on the HikariCP connection pool. For large pull requests containing hundreds of files, the long network I/O held database connections hostage, starving other worker threads and causing database connection timeouts.
- **Architectural Solution:**
  Added a safety cap limiting full blob fetches to at most 15 files per commit, with a 2-second timeout and graceful fallback to the patch diff. This guarantees bounded transaction execution time and protects the HikariCP pool from starvation.

---

### FIX 5 (ARCH-05): Operations Outbox Replay for Personal Workspaces & UI Error Handling

- **Location:**
  - `services/source-control-service/.../SourceControlOperationsController.java`
  - `frontend/src/app/(dashboard)/operations/page.tsx`
- **Root Cause:**
  The outbox replay endpoint in `SourceControlOperationsController` strictly enforced that `organizationId` must not be null. Consequently, solo developers operating in **Personal Workspaces** were forbidden from replaying failed outbox events. Furthermore, the frontend operations dashboard used native browser `alert()` popups when an API call failed.
- **Architectural Solution:**
  1. Updated `SourceControlOperationsController` to permit replay requests when `organizationId == null` as long as a valid `workspaceId` and authenticated `userId` are present in `TenantContext`.
  2. Upgraded the frontend operations page to use modern styled alert banners with `formatUserError()`, displaying clear workspace badges for both personal and organization contexts.

---

### FIX 6 (ARCH-06): API Gateway JWT Revocation Fail-Closed Security Policy

- **Location:**
  - `infrastructure/api-gateway/src/main/java/com/forgeai/gateway/filter/JwtRevocationGatewayFilter.java`
  - `infrastructure/api-gateway/src/test/java/com/forgeai/gateway/filter/JwtRevocationGatewayTest.java`
- **Root Cause:**
  The API Gateway's distributed JWT revocation filter previously defaulted to `fail-closed: false` (fail-open). If Redis was temporarily unreachable or suffered a network partition, revoked JWT tokens belonging to compromised accounts or logged-out users were allowed to pass through the gateway unimpeded.
- **Architectural Solution:**
  Changed `@Value("${security.revocation.fail-closed:true}") private boolean failClosed = true;`. In production environments, if Redis is down, the gateway safely fails closed (HTTP 503 Service Unavailable / Token Revocation Check Unavailable) rather than admitting potentially revoked tokens. Unit tests were updated with explicit setters for deterministic test assertions.

---

### FIX 7 (ARCH-07): AI Service SSE Streaming Authentication & Workspace Context Support

- **Location:** `services/ai-service/app/presentation/dependencies.py`
- **Root Cause:**
  Standard browser `EventSource` APIs do not support setting custom `Authorization` or `X-Tenant-ID` HTTP headers on Server-Sent Events (SSE) connections. As a result, the AI streaming endpoints rejected frontend requests or failed to extract the user's active `workspaceId` or `organizationId`.
- **Architectural Solution:**
  Updated `get_tenant_org_id` and `get_tenant_user_id` dependencies in FastAPI to check `request.query_params` as a first-class fallback when request headers are not present:
  - Query parameters: `?token=...`, `?workspaceId=...`, `?organizationId=...`.
  This allows secure, authenticated real-time AI code reviews and test generation streams directly over SSE.

---

## Verification Summary

| Component | Test Suite / Command | Status |
|---|---|---|
| **API Gateway** | `mvn test -f infrastructure/api-gateway/pom.xml -Dtest=JwtRevocationGatewayTest` | **5/5 Passed** |
| **All Backend Services** | `mvn test-compile -f pom.xml` | **Reactor Build Success (6 Modules)** |
| **Project Service** | `CircuitBreakerResilienceTest`, `WorkspaceSecurityAndIntegrityE2ETest` | **10/10 Passed** |
| **AI Service** | `python -m py_compile services/ai-service/app/presentation/dependencies.py` | **Syntax & Compilation Clean (0 Errors)** |
| **Frontend** | `npm run build` (Next.js 16 + Turbopack) | **13/13 Routes Compiled & Generated** |
| **Docker Mesh** | `docker compose -f docker-compose.dev.yml up -d --build` | **Containers Built & Running Healthy** |

