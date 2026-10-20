# Production Observability Architecture

This document describes the observability, tracing, metrics, and structured logging architecture implemented across ForgeAI services.

---

## 1. End-to-End Request Correlation

Every ingress request is assigned or preserves two primary identifiers:
1. **W3C Trace Context (`traceparent`)**: A 4-part W3C header formatted as `00-${trace_id}-${span_id}-${flags}`.
2. **Correlation ID (`X-Correlation-Id`)**: An alphanumeric correlation identifier generated at the Spring Cloud API Gateway when absent from client requests.

### Propagation Flow
```
Browser / Client (Next.js)
    │ (X-Correlation-Id, traceparent)
    ▼
Spring Cloud API Gateway (TraceCorrelationFilter)
    │ Generates X-Correlation-Id if absent; generates new traceparent if invalid
    │ Injects into Reactor ServerWebExchange downstream headers
    ▼
Downstream Services (Identity, Source Control, Project, Testing, AI)
    │ TraceContextFilter / TraceContextMiddleware populates ThreadLocal SLF4J MDC / ContextVars
    │ MDC keys: traceId, spanId, correlationId, organizationId
    │ HTTP Clients forward headers to subsequent internal microservice calls
    ▼
Background Workers & Outbox Publisher Relay
    │ Preserves trace_id and correlation_id in outbox_events table
    │ Binds traceId and correlationId to MDC during asynchronous event dispatch
```

---

## 2. Structured Operational Logging

Operational events are formatted as single-line JSON log entries via `StructuredLogger` (Java) and `StructuredJsonFormatter` (Python FastAPI).

### Standard JSON Schema
```json
{
  "timestamp": "2026-10-20T12:00:00.123Z",
  "service": "source-control-service",
  "environment": "production",
  "level": "INFO",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "correlationId": "8f3a912c9e7b4e21a8d5069a1b248e35",
  "organizationId": "cc28c8a3-2224-4bad-9ab2-ea36ede52577",
  "eventType": "github.webhook.accepted",
  "operation": "webhook_ingest",
  "outcome": "SUCCESS",
  "latency": 42,
  "errorClass": null,
  "message": "Webhook push event accepted for repository 42"
}
```

### Standardized Event Taxonomy
- `github.webhook.received`, `github.webhook.accepted`, `github.webhook.rejected`, `github.webhook.duplicate`
- `github.installation.bound`, `github.repository.synced`, `github.repository.sync_failed`
- `outbox.event.published`, `outbox.event.retry`, `outbox.event.dead_letter`
- `traceability.sync.completed`, `traceability.sync.degraded`, `traceability.gap.detected`
- `ai.analysis.completed`, `ai.analysis.failed`
- `testing.workflow.completed`, `testing.workflow.failed`, `testing.workflow.rejected_stale`
- `security.authentication.login_success`, `security.authentication.login_failure`, `security.authorization.denied`

### Zero-Secret Redaction Guarantee
All structured loggers apply deterministic regex masking:
- `Bearer\s+[\w\-._]+` → `Bearer [REDACTED]`
- `token[:=]\s*[\w\-._]+` → `token=[REDACTED]`
- `key[:=]\s*[\w\-._]+` → `key=[REDACTED]`
- `password[:=]\s*[^\s,]+` → `password=[REDACTED]`

---

## 3. Application Telemetry & Metrics (Micrometer / Prometheus)

### Gateway
- `http.server.requests`: Request rate, method, and URI path distribution.
- `http.server.errors`: HTTP 5xx responses.
- `http.server.latency`: Latency distribution.
- `spring.cloud.gateway.requests`: Rate limit rejections (HTTP 429).

### Source Control Service
- `github_api_requests_total`: Total GitHub API requests tagged by `endpoint`, `method`, `status`.
- `github_api_failures_total`: Total failed GitHub API requests (HTTP >= 400).
- `github_api_latency`: Timer tracking GitHub API call response durations.
- `github_webhook_received_total`: Total webhooks received tagged by `eventType`.
- `github_webhook_rejected_total`: Total webhooks rejected tagged by `reason`.
- `github_webhook_duplicate_total`: Total duplicate deliveries deduplicated via idempotency window.
- `github_sync_success_total`: Successful repository synchronization tasks.
- `github_sync_failure_total`: Failed repository synchronization tasks.
- `github_installations_active`: Current active GitHub App installations gauge.
- `github_installations_quarantined`: Current quarantined installations gauge.
- `github_installations_suspended`: Current suspended installations gauge.
- `github_rate_limit_remaining`: Current GitHub API rate limit remaining quota gauge.
- `github_rate_limit_reset`: Unix epoch timestamp when rate limit quota resets.

### Outbox Processing
- `outbox_pending_events`: Gauge of unpublished outbox events awaiting relay.
- `outbox_processing_total`: Counter of successfully dispatched outbox events.
- `outbox_retry_total`: Counter of events scheduled for retry after failure.
- `outbox_dead_letter_total`: Counter of poison-pill events transitioned to `DEAD_LETTER`.
- `outbox_processing_latency`: Timer tracking outbox processing durations in milliseconds.

### Traceability Engine
- `traceability_sync_total`: Total synchronization runs.
- `traceability_sync_failure_total`: Total sync errors.
- `traceability_degraded_total`: Total cycles where evidence was degraded due to downstream circuit opening.
- `traceability_projects_processed`: Total projects scanned.
- `traceability_gaps_detected`: Total governance traceability gaps identified.

### Testing Service
- `workflow_runs_received_total`: Total workflow run webhook deliveries received.
- `workflow_runs_completed_total`: Total test runs reconciled to completed state.
- `workflow_runs_failed_total`: Total test runs reconciled with failure conclusion.
- `workflow_runs_rejected_stale_total`: Total out-of-order stale runs rejected.

### AI Service
- `ai_analysis_total`: Total AI prompt completions.
- `ai_analysis_success_total`: Successful analyses.
- `ai_analysis_failure_total`: Failed analyses.
- `ai_provider_failures`: Counter of provider-level connection/HTTP failures.
- `ai_fallback_total`: Counter of failovers to secondary provider (Groq).
- `ai_provider_latency_p95_ms`: Gauge representing 95th percentile provider latency.

### Circuit Breakers
- `circuit_breaker_open_total`: Counter of transitions to OPEN state.
- `circuit_breaker_state`: Gauge of circuit state (0=CLOSED, 1=HALF_OPEN, 2=OPEN).
- `circuit_breaker_fast_fail_total`: Counter of fast-failed requests rejected while OPEN.

---

## 4. Health Semantics & Dependency Diagnostic Model

Health endpoints distinguish four states for external dependencies:
1. **`CONFIGURED`**: Necessary credentials and endpoints are present in the environment.
2. **`UNCONFIGURED`**: Optional or required credentials have not been configured.
3. **`UNREACHABLE`**: Configured dependency failed connection attempts (timeout, connection refused, 5xx).
4. **`DEGRADED`**: Dependency is partially operational (e.g. rate limit exceeded or fallback in use).

Overall service status:
- `UP`: Core database, Redis, and essential dependencies are healthy.
- `DEGRADED`: A downstream integration is in fallback or degraded state, but service functions with reduced capacity.
- `DOWN`: Core database or critical infrastructure is unavailable.

---

## 5. Recommended Initial SLO Targets (Engineering Guidance)

| Indicator | Target | Measurement |
| :--- | :--- | :--- |
| **Gateway Availability** | `>= 99.5%` | Non-5xx responses across all routes |
| **Edge Latency (p95)** | `< 500 ms` | Duration from gateway ingress to client response |
| **Webhook Ingestion Latency** | `< 200 ms` | Duration to verify signature, record deduplication, and enqueue |
| **GitHub API Error Rate** | `< 2.0%` | Failed requests against total GitHub API calls |
| **Traceability Sync Success** | `>= 99.0%` | Completed sync cycles without unhandled exceptions |
| **Outbox Relay Latency** | `< 1000 ms` | Time between entity creation and outbox publication |
| **AI Fallback Rate** | `< 5.0%` | Proportion of AI requests falling back to Groq |
