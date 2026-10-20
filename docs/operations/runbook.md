# ForgeAI Operations & Incident Response Runbook

This runbook guides operators through diagnosing, mitigating, and recovering from production incidents in ForgeAI.

---

## 1. Quick Incident Investigation Procedure

Every error reported by ForgeAI includes standard diagnostic headers:
- `X-Correlation-Id`
- `traceparent` (W3C standard format)

### Step 1: Trace the Request
Filter logs across all services using the `correlationId` or `traceId`:
```bash
# Example grep across Docker container logs
docker compose -f docker-compose.dev.yml logs | grep "8f3a912c9e7b4e21a8d5069a1b248e35"
```
Because `StructuredLogger` formats events as JSON, you can filter by `eventType`, `errorClass`, or `outcome`:
```bash
# Find all DEAD_LETTER transitions in the last hour
docker compose -f docker-compose.dev.yml logs | grep '"eventType":"outbox.event.dead_letter"'
```

---

## 2. Practical Failure Scenarios & Mitigations

### Scenario A: GitHub Webhook Delivery Failure
**Failure Chain**: GitHub → Gateway → Source Control Service → Outbox → Retry → Recovery

1. **Symptom**: Webhooks are reported as failing in the GitHub App delivery console, or commits are not triggering traceability updates.
2. **Diagnosis**:
   - Check `github_webhook_rejected_total` in Source Control metrics (`/actuator/prometheus` or Operations Dashboard).
   - Verify webhook secret matching: `InvalidWebhookSignatureException` produces HTTP 401 with `errorCode: WEBHOOK_UNAUTHORIZED`.
   - Check GitHub rate limit remaining quota: If `github_rate_limit_remaining` approaches 0, GitHub API calls will back off.
3. **Recovery**:
   - Outbox events automatically retry with exponential backoff: $2^{\text{attempt}}$ seconds (up to 300 seconds maximum).
   - If an outbox event reaches 5 attempts, it enters the `DEAD_LETTER` state.
   - Once the downstream service recovers, navigate to `/operations` in the dashboard and click **Safe Replay** for the event.

---

### Scenario B: Traceability Degradation (Circuit Breaker OPEN)
**Failure Chain**: Project Service → Testing Service Unavailable → Circuit OPEN → DEGRADED → Recovery

1. **Symptom**: Traceability graph shows evidence as `DEGRADED`, but project metadata and requirements remain accessible without throwing 500 errors.
2. **Diagnosis**:
   - Check `/api/v1/operations/circuit-breakers` (or the Operations Dashboard under *Circuit Breaker Resilience Status*).
   - If `testing-service` or `ai-service` circuit is `OPEN`, requests are fast-failing to protect downstream recovery.
   - Traceability continues operating in degraded mode, providing partial graph data and logging `traceability.sync.degraded`.
3. **Recovery**:
   - Restart or restore the unhealthy dependency container (e.g. `docker compose restart testing-service`).
   - The circuit breaker reset timeout is 30 seconds. After 30 seconds, the breaker enters `HALF_OPEN` state.
   - The next request acts as a trial probe; upon success, the circuit resets to `CLOSED`.

---

### Scenario C: AI Provider Outage & Failover
**Failure Chain**: Project Service / AI call → Primary Gemini Provider 5xx/Quota Exceeded → Fallback to Groq → Degraded Status

1. **Symptom**: AI Blast Radius analysis returns with metadata `provider: Groq` and `fallback_used: true`.
2. **Diagnosis**:
   - Check `/api/v1/ai/health`. Dependency status will show:
     ```json
     {
       "status": "DEGRADED",
       "dependencies": {
         "database": {"status": "UP"},
         "gemini": {"status": "UNREACHABLE"},
         "groq": {"status": "CONFIGURED"},
         "provider": {"status": "DEGRADED"}
       }
     }
     ```
   - Check `ai_fallback_total` and `ai_provider_failures` metrics.
3. **Recovery**:
   - Check Google Gemini API quota or renew key in environment variable `GEMINI_API_KEY`.
   - The failover orchestrator automatically attempts Gemini first on every subsequent request and only falls back to Groq when Gemini returns unavailable.

---

### Scenario D: Dead-Letter Poison-Pill Outbox Recovery
**Mechanism**: Idempotent transactional outbox with tenant-scoped replay authorization.

1. **Inspection**:
   - Access the internal dashboard at `/operations` or query:
     ```http
     GET /api/v1/operations/outbox/dead-letter?page=0&size=20
     ```
2. **Replay Execution**:
   - Replay is only permitted for tenant-owned events:
     ```http
     POST /api/v1/operations/outbox/events/{id}/replay
     X-Organization-Id: {tenant-uuid}
     ```
   - The replay operation:
     - Verifies event status is `DEAD_LETTER`.
     - Verifies `organizationId` matches the caller's active tenant context.
     - Resets `status` to `PENDING`, resets `attemptCount` to `0`, and schedules `nextRetryAt` to `now`.
     - Automatically creates an immutable audit record: `OUTBOX_REPLAY_REQUESTED`.

---

## 3. Production Alert Thresholds

| Alert Rule | Condition | Severity | Action |
| :--- | :--- | :--- | :--- |
| **`GatewayHigh5xxErrorRate`** | HTTP 5xx rate > 2% over 5m | Critical | Inspect gateway and downstream container logs |
| **`CircuitBreakerOpen`** | Any breaker state == 2 for > 2m | Warning | Inspect downstream dependency health endpoint |
| **`OutboxDeadLetterSpike`** | `outbox_dead_letter_total` > 0 | Warning | Inspect dead-letter queue at `/operations` |
| **`GitHubRateLimitCritical`** | `github_rate_limit_remaining` < 100 | Warning | Check API query loop or enable backoff |
| **`DatabaseConnectionPoolExhausted`** | DB pool waiting threads > 10 | Critical | Inspect long-running transactions |
| **`CrossTenantAccessAnomaly`** | `OUTBOX_REPLAY_DENIED` > 0 | Security | Investigate tenant session credentials |
