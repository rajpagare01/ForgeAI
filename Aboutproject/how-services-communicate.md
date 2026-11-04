# How Services Communicate in ForgeAI

ForgeAI operates as a **Distributed, Multi-Tenant Microservices Architecture** designed for high throughput, fault isolation, and enterprise security.

Rather than relying on a single monolithic communication protocol, ForgeAI utilizes a **Hybrid Multi-Modal Communication Model**:
1. **Synchronous East-West (Service-to-Service) REST via Native `HttpClient`** (for real-time queries requiring immediate response).
2. **Asynchronous Distributed Event Mesh via RabbitMQ AMQP 0-9-1 & Transactional Outbox** (for eventual consistency, background processing, and decoupling).
3. **North-South Edge Routing via Spring Cloud API Gateway** (for external client ingress, JWT verification, and header attestation).

---

## 1. High-Level Communication Architecture

```
                                  +---------------------------------------+
                                  |     Next.js 16 Web Dashboard (:3000)  |
                                  +---------------------------------------+
                                                      |
                                           HTTPS / REST (JWT Bearer)
                                                      v
                                  +---------------------------------------+
                                  |  Spring Cloud API Gateway (:8080)     |
                                  +---------------------------------------+
                                    /         |             |          \
        +--------------------------+          |             |           +-------------------------+
        | Attested HTTP (Stripped)            |             |           | Attested HTTP (Stripped)
        v                                     v             v           v
+------------------+                 +------------------+  +------------------+  +------------------+
| Identity Service |                 |  Source Control  |  |  Project Service |  | Testing Service  |
|      (:8081)     |                 |      (:8082)     |  |      (:8085)     |  |      (:8083)     |
+------------------+                 +------------------+  +------------------+  +------------------+
        |                                     |                     ^                     |
        | 1. Outbox Event                     | 1. Outbox Event     | RabbitMQ Consumers  | 1. Outbox Event
        v                                     v                     |                     v
  [outbox_events]                       [outbox_events]             |               [outbox_events]
        |                                     |                     |                     |
  Outbox Relay                          Outbox Relay                |               Outbox Relay
        \                                     /                     |                     /
         \                                   /                      |                    /
          v                                 v                       |                   v
     +------------------------------------------------------------------------------------+
     |                         RabbitMQ Topic Exchange (AMQP 0-9-1)                       |
     |                              forgeai.events.exchange                               |
     +------------------------------------------------------------------------------------+
                   |                                                      |
        Topic: forgeai.identity.#                             Topic: forgeai.sourcecontrol.#
                   v                                                      v
     +---------------------------+                          +-------------------------------+
     | forgeai.project.identity. |                          | forgeai.project.sourcecontrol.|
     |           queue           |                          |             queue             |
     +---------------------------+                          +-------------------------------+
                   \                                                      /
                    +------------------------+---------------------------+
                                             |
                                             v
                               +----------------------------+
                               |    RabbitMqEventConsumer   |
                               |    (Auto Kanban / Tasks)   |
                               +----------------------------+
```

---

## 2. Synchronous East-West Communication (REST over HTTP)

When a microservice needs immediate data from another service (e.g. `project-service` querying linked commits from `source-control-service` to render a sprint task details drawer):

### A. Transport & Service Discovery
* **Docker Internal DNS:** All services reside inside the Docker virtual bridge network `forgeai-network`.
* Requests are addressed directly to service hostnames:
  * `http://identity-service:8081`
  * `http://source-control-service:8082`
  * `http://testing-service:8083`
  * `http://ai-service:8084`
  * `http://project-service:8085`

### B. Client Technology: Native `java.net.http.HttpClient`
Instead of heavy dependencies like Netflix Eureka, Ribbon, or Spring Cloud OpenFeign, ForgeAI uses **Java 21's native `HttpClient`**:
* Non-blocking and lightweight.
* Configured with connection timeouts (`4s - 5s`).
* Standardized request builders injecting tenant context and tracing headers.

### C. Zero-Trust Internal Attestation Marker
To guarantee that tenant context cannot be spoofed across internal boundaries:
1. **Edge Gateway Sanitize:** `GatewayTenantFilter.java` strips all incoming `X-User-Id`, `X-Workspace-Id`, `X-Org-Id`, and `X-ForgeAI-Internal` headers sent from the outside world.
2. **Attestation Injection:** When the Gateway or an authenticated microservice makes an internal request, it injects:
   ```http
   X-ForgeAI-Internal: attested
   X-User-Id: <uuid>
   X-Workspace-Id: <uuid>
   X-Org-Id: <uuid>
   X-Correlation-Id: <uuid>
   traceparent: 00-<trace_id>-<span_id>-01
   ```
3. **Fail-Closed Verification:** The target service's `TenantContextFilter.java` inspects `X-ForgeAI-Internal`. If missing or invalid, the request is rejected with `403 Forbidden` (`enforceInternalMarker: true`).

### D. In-Process Circuit Breakers (`SimpleCircuitBreaker`)
To prevent cascading failures across the distributed cluster:
* Every client adapter (`SourceControlClient`, `TestingServiceClient`, `AiServiceClient`) implements an active circuit breaker.
* **Failure Threshold:** 3 consecutive HTTP timeouts or 5xx responses open the circuit.
* **Fast-Fail:** Open circuit calls immediately return graceful fallback data (status `UNAVAILABLE`) without blocking threads.
* **Half-Open Probe:** After 30 seconds, a single test probe is allowed through to check downstream health.

---

## 3. Asynchronous Event-Driven Communication (RabbitMQ & Transactional Outbox)

For operations that do not require an immediate blocking response—such as git repository commits, pull request synchronizations, user registrations, and test suite completions—ForgeAI uses **RabbitMQ 3.13 AMQP Topic Exchange** with the **Transactional Outbox Pattern**.

### A. The Transactional Outbox Pattern
Directly publishing to a message broker during a database transaction creates a dual-write problem (broker could fail after DB commits, or DB could rollback after broker publish).

ForgeAI solves this:
1. **Atomic Local Write:** The business entity and a corresponding `OutboxEvent` are committed in the same PostgreSQL transaction.
2. **Outbox Polling Relay:** `OutboxPublisherRelay` queries unconsumed outbox records on a periodic schedule.
3. **Dual Dispatch:** `LocalSpringEventPublisher` publishes the event simultaneously to:
   - **Local In-Process Bus:** Spring `ApplicationEventPublisher` (for local listeners).
   - **RabbitMQ AMQP Broker:** `RabbitTemplate` $\rightarrow$ `forgeai.events.exchange`.

### B. AMQP Exchange & Queue Topology

| Component | Type | Identifier / Name | Description |
| :--- | :--- | :--- | :--- |
| **Main Exchange** | `topic` | `forgeai.events.exchange` | Central durable topic exchange for all business domain events. |
| **Dead-Letter Exchange (DLX)** | `topic` | `forgeai.events.dlx` | Receives rejected or unprocessable events. |
| **Dead-Letter Queue (DLQ)** | `queue` | `forgeai.events.dlq` | Bound to DLX with pattern `#` for dead-letter analysis. |
| **Project Identity Queue** | `queue` | `forgeai.project.identity.queue` | Bound to `forgeai.identity.#` (user provisioning/profile sync). |
| **Project SourceControl Queue** | `queue` | `forgeai.project.sourcecontrol.queue` | Bound to `forgeai.sourcecontrol.#` (commits, branches, PRs). |

### C. Automated Kanban & Task Linking Flow
When code is pushed to a repository:
1. `source-control-service` syncs the commit from GitHub.
2. It publishes `CommitCreatedEvent` to `forgeai.events.exchange` with routing key `forgeai.sourcecontrol.commit_created`.
3. `RabbitMqEventConsumer` in `project-service` receives the message.
4. It extracts task identifiers (e.g. `FORGE-42`) from the commit message.
5. It resolves the task across Personal or Organization workspace scopes.
6. It automatically links the commit and moves the task status:
   - `BRANCH_CREATED` $\rightarrow$ `IN_PROGRESS`
   - `COMMIT_CREATED` $\rightarrow$ `IN_PROGRESS`
   - `PULL_REQUEST_OPENED` $\rightarrow$ `IN_REVIEW`

---

## 4. North-South Ingress Communication (API Gateway)

1. **Client to Gateway:** Next.js client sends standard HTTP REST requests to `http://localhost:8080/api/v1/...` with `Authorization: Bearer <JWT>`.
2. **Reactive Gateway Filtering:** Spring Cloud Gateway uses non-blocking Project Reactor / Netty to:
   - Validate JWT signatures against RSA public keys or Redis session caches.
   - Enforce rate limiting via Redis token bucket.
   - Inject tenant scope (`X-Workspace-Scope: PERSONAL | ORGANIZATION`).
   - Route path `/api/v1/projects/**` to `http://project-service:8085`.
   - Route path `/api/v1/repositories/**` to `http://source-control-service:8082`.
   - Route path `/api/v1/auth/**` to `http://identity-service:8081`.

---

## 5. Summary Matrix: Communication Channels in ForgeAI

| Channel | Protocol | Tech Stack | Use Case | Guarantees / Patterns |
| :--- | :--- | :--- | :--- | :--- |
| **External Ingress** | HTTPS / REST | Spring Cloud Gateway (Netty) | Browser & GitHub Webhooks | Rate limiting, JWT verification, header stripping |
| **East-West Sync** | HTTP/1.1 REST | Java 21 `HttpClient` | Querying real-time dependency data | `X-ForgeAI-Internal` attestation, Circuit Breakers |
| **East-West Async** | AMQP 0-9-1 | RabbitMQ 3.13 + Spring AMQP | Cross-service state updates, CI events | Transactional Outbox, Topic Exchange, DLQ, At-least-once delivery |
| **Distributed Tracing** | HTTP Headers | W3C `traceparent` + SLF4J MDC | Observability across logs | End-to-end request correlation |

