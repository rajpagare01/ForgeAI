# RabbitMQ Event Mesh & Distributed Transactional Outbox Architecture

## 1. Executive Summary

ForgeAI employs an asynchronous, event-driven microservices architecture powered by **RabbitMQ 3.13 (AMQP 0-9-1)**, an **AMQP Topic Exchange with Dead-Letter Queues (DLQ)**, and the **Transactional Outbox Pattern**.

This document outlines the distributed messaging topology, dual-dispatch outbox mechanism, routing rules, consumer workflows, and automated Kanban progression across **Personal** and **Organization** workspaces.

---

## 2. High-Level Architecture Diagram

```
+---------------------------------------------------------------------------------------------------+
|                                      Transactional Outbox Pattern                                 |
|                                                                                                   |
|   +--------------------+       1. Local DB Tx      +---------------------+                        |
|   |   Business Domain  | ------------------------> |    Outbox Table     |                        |
|   |   Service Entity   |                           |  (RDBMS / Postgres) |                        |
|   +--------------------+                           +---------------------+                        |
|                                                               |                                   |
|                                                    2. Polled by Relay                             |
|                                                               v                                   |
|                                                    +---------------------+                        |
|                                                    | OutboxPublisherRelay|                        |
|                                                    +---------------------+                        |
|                                                               |                                   |
|                                             3. EventPublisher.publish(...)                        |
|                                                               v                                   |
|                                                    +---------------------+                        |
|                                                    |LocalSpringEventPub- |                        |
|                                                    | lisher (Dual Dis-   |                        |
|                                                    |        patch)       |                        |
|                                                    +---------------------+                        |
+---------------------------------------------------------------|-----------------------------------+
                                                                |
                                    +---------------------------+---------------------------+
                                    |                                                       |
                            (In-Process JVM)                                           (AMQP 0-9-1)
                                    v                                                       v
                         +--------------------+                                   +-------------------+
                         | Spring Local Event |                                   | RabbitTemplate    |
                         | Publisher Bus      |                                   +-------------------+
                         +--------------------+                                             |
                                                                                            v
+-------------------------------------------------------------------------------------------|-------+
|                                    RabbitMQ Event Mesh                                    |       |
|                                                                                           v       |
|                                                    +--------------------------------------------+ |
|                                                    |   Exchange: forgeai.events.exchange        | |
|                                                    |            (Topic Exchange)                | |
|                                                    +--------------------------------------------+ |
|                                                       /            |             \                |
|               routing key:                           /             |              \               |
|               "forgeai.identity.*"                  /              |               \              |
|                                                    v               |                v             |
|                                       +-----------------------+    |    +-----------------------+ |
|                                       | forgeai.project.      |    |    | forgeai.project.      | |
|                                       | identity.queue        |    |    | sourcecontrol.queue   | |
|                                       +-----------------------+    |    +-----------------------+ |
|                                                   |                |                |             |
|                                                   |                | (On Failure)   |             |
|                                                   v                v                v             |
|                                         +-----------------------------------------------+         |
|                                         |           Dead Letter Exchange (DLX)          |         |
|                                         |              forgeai.events.dlx               |         |
|                                         +-----------------------------------------------+         |
|                                                                |                                  |
|                                                                v                                  |
|                                         +-----------------------------------------------+         |
|                                         |           Dead Letter Queue (DLQ)             |         |
|                                         |              forgeai.events.dlq               |         |
|                                         +-----------------------------------------------+         |
+---------------------------------------------------------------------------------------------------+
                                              |                               |
                                              v                               v
                              +---------------------------------------------------------------+
                              |                        project-service                        |
                              |              RabbitMQ Consumer & Task Auto-Linker             |
                              |                                                               |
                              |  - User Profile Sync                                          |
                              |  - Git Commit Task Linking & Status Advancement               |
                              |  - PR Auto-Linking & Code Review Column Movement              |
                              |  - Branch Association to Kanban Issues                        |
                              +---------------------------------------------------------------+
```

---

## 3. Core Components & Responsibilities

### 3.1 Transactional Outbox Dual Dispatch
Every microservice (`identity-service`, `source-control-service`, `project-service`, `testing-service`) writes domain events to its local PostgreSQL `outbox` table inside the same ACID database transaction as the business entity changes.

The scheduled `OutboxPublisherRelay` queries unpublished outbox rows and calls `EventPublisher.publish(...)`.
`LocalSpringEventPublisher` executes a **dual-dispatch** strategy:
1. **Local Spring Event Bus:** Dispatches the event via `ApplicationEventPublisher.publishEvent()` for zero-latency, in-process listeners (`@EventListener` / `@TransactionalEventListener`).
2. **RabbitMQ AMQP Topic Exchange:** Formats a canonical AMQP routing key and sends a serialized JSON payload to `forgeai.events.exchange` using `RabbitTemplate`.

If RabbitMQ is temporarily unreachable or disabled (e.g. during lightweight unit tests), the publisher safely catches the exception and logs a warning, ensuring local execution and unit test suites are never disrupted.

### 3.2 AMQP Topic Exchange Topology
- **Topic Exchange Name:** `forgeai.events.exchange`
- **Routing Key Convention:** `forgeai.<service>.<event_type_lowercase>`
  - Examples:
    - `forgeai.identity.user_created`
    - `forgeai.identity.user_updated`
    - `forgeai.sourcecontrol.commit_created`
    - `forgeai.sourcecontrol.pull_request_opened`
    - `forgeai.sourcecontrol.branch_created`
    - `forgeai.project.task_status_changed`
    - `forgeai.testing.test_suite_completed`

### 3.3 Dead-Lettering (DLX & DLQ)
All queues declare dead-letter arguments:
- `x-dead-letter-exchange`: `forgeai.events.dlx`
- `x-dead-letter-routing-key`: `forgeai.dlq`

If a message encounters an unhandled exception, is malformed, or exceeds rejection retries (`default-requeue-rejected: false`), RabbitMQ automatically routes the message to `forgeai.events.dlq`. This protects the system from poison-pill messages blocking queue processing.

---

## 4. Cross-Service Automated Workflows

### Automated Git & Task Kanban Synchronization
`project-service` declares two dedicated queues:
1. `forgeai.project.identity.queue` (bound to `forgeai.identity.*`): Synchronizes users across boundaries.
2. `forgeai.project.sourcecontrol.queue` (bound to `forgeai.sourcecontrol.*`): Intercepts Git activity.

When developers push code, open pull requests, or create branches:
1. **Commit Message Parsing:** `SourceControlEventConsumer` parses commit messages for task references (e.g., `FORGE-123`, `closes #45`, `fixes PROJ-99`).
2. **Multi-Tenant Context Resolution:** Resolves tasks in either **Organization Workspaces** or **Personal Workspaces** (`organizationId == null`).
3. **Automated Status Advancement:**
   - Commit detected -> Moves task from `TODO` to `IN_PROGRESS`.
   - Pull Request opened -> Moves task to `IN_REVIEW`.
   - Commit with resolution keyword (`fix`, `closes`) -> Progresses task to `DONE`.

---

## 5. Resilience & Operational Verification

- **Ports:**
  - `5672`: AMQP 0-9-1 broker port.
  - `15672`: RabbitMQ Management Web Dashboard (`http://localhost:15672`, credentials `forgeai` / `password`).
- **Health Checks:**
  - Docker Compose uses `rabbitmq-diagnostics -q ping`.
  - Spring Boot services disable eager health probe blocking (`management.health.rabbit.enabled: false`) to prevent transient RabbitMQ startup delays from failing application readiness probes.

