# Rate Limiting & Quota Management Architecture in ForgeAI

## 1. Executive Summary

ForgeAI handles high-frequency traffic across multiple vulnerable boundaries: public internet clients, third-party GitHub webhooks, multi-tenant background syncs, and cost-sensitive external LLM APIs (Google Gemini & Groq).

To prevent denial of service (DoS), API budget exhaustion, and cascading downstream outages, ForgeAI implements a **4-Tier Defense-in-Depth Rate Limiting & Throttling Architecture**:

```
+---------------------------------------------------------------------------------------------------------+
|                                    FORGEAI 4-TIER RATE LIMITING MESH                                    |
+---------------------------------------------------------------------------------------------------------+
|                                                                                                         |
|  [ Web Clients / Users ]                           [ GitHub Webhooks ]                                  |
|            |                                                |                                           |
|            +-----------------------+------------------------+                                           |
|                                    v                                                                    |
|  =====================================================================================================  |
|  TIER 1: Ingress Gateway Rate Limiting (Spring Cloud Gateway + Redis Token Bucket)                      |
|  -----------------------------------------------------------------------------------------------------  |
|  * userKeyResolver    : Token bucket per X-User-Id / JWT sub (Replenish: 5 req/s, Burst: 20 tokens)    |
|  * webhookKeyResolver : High-capacity bucket per Hook-ID / IP (Replenish: 100 req/s, Burst: 100 tokens)|
|  * Rejection          : Immediate HTTP 429 Too Many Requests                                            |
|  =====================================================================================================  |
|                                    |                                                                    |
|                                    v (Internal Docker Network)                                          |
|            +-----------------------+------------------------+                                           |
|            |                                                |                                           |
|            v                                                v                                           |
|  +---------------------------------------+    +---------------------------------------+                 |
|  | TIER 2: AI Token & RPM Metering       |    | TIER 3: GitHub Budget & Pre-Flight     |                 |
|  | (ai-service: RedisAiRateLimiter)      |    | (source-control: RateLimitTracker)    |                 |
|  +---------------------------------------+    +---------------------------------------+                 |
|  * Sliding-window ZSET (60 RPM/org)      |    * Dynamic header tracking from GitHub   |                 |
|  * Daily token meter (100k tokens/day)   |      (X-RateLimit-Remaining / Limit / Reset) |                 |
|  * In-memory failover if Redis is down   |    * Pre-flight throttle when capacity <10%|                 |
|  * HTTP 429 with Retry-After header      |    * Micrometer metrics & HealthIndicator  |                 |
|  * Multi-LLM provider failover & retry   |    * Exponential backoff on GitHub 429     |                 |
|  +---------------------------------------+    +---------------------------------------+                 |
|                                                                                                         |
|  =====================================================================================================  |
|  TIER 4: Frontend Graceful Degradation (api-client.ts)                                                  |
|  -----------------------------------------------------------------------------------------------------  |
|  * Intercepts HTTP 429 status codes                                                                     |
|  * Sanitizes raw error responses to user-friendly notifications                                         |
|  * Preserves UI form state and shows non-blocking toast notifications                                   |
|  =====================================================================================================  |
|                                                                                                         |
+---------------------------------------------------------------------------------------------------------+
```

---

## 2. Tier 1: North-South Ingress Rate Limiting (Edge API Gateway)

### A. Location & Technology
* **File:** `infrastructure/api-gateway/src/main/java/com/forgeai/gateway/ratelimit/RateLimiterConfig.java`
* **Configuration:** `infrastructure/api-gateway/src/main/resources/application.yml`
* **Engine:** Spring Cloud Gateway `RequestRateLimiter` filter powered by Redis Token Bucket (`RedisRateLimiter`).

### B. Dual Key-Resolution Strategy

To prevent webhook bursts from consuming human user quotas (and vice versa), the Gateway partitions traffic into two independent key resolvers:

```java
@Configuration
public class RateLimiterConfig {

    /**
     * Resolves authenticated user identity via X-User-Id, JWT sub, or remote IP address.
     * Used for rate-limiting authenticated User APIs (e.g. 5 req/s replenish, 20 burst).
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userIdHeader = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                return Mono.just("user:" + userIdHeader.trim());
            }

            return ReactiveSecurityContextHolder.getContext()
                    .map(ctx -> ctx.getAuthentication().getPrincipal())
                    .flatMap(principal -> {
                        if (principal instanceof Jwt jwt) {
                            String sub = jwt.getSubject();
                            if (sub != null && !sub.isBlank()) {
                                return Mono.just("user:" + sub);
                            }
                        }
                        return Mono.empty();
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                        String ip = (remote != null && remote.getAddress() != null)
                                ? remote.getAddress().getHostAddress() : "anonymous";
                        return Mono.just("ip:" + ip);
                    }));
        };
    }

    /**
     * Dedicated partition KeyResolver for GitHub Webhook Ingress (/api/v1/webhooks/**).
     * Extracts repository ID or GitHub delivery header or remote IP to allow bursts up to 100 req/sec.
     */
    @Bean
    public KeyResolver webhookKeyResolver() {
        return exchange -> {
            String deliveryId = exchange.getRequest().getHeaders().getFirst("X-GitHub-Delivery");
            String repoHeader = exchange.getRequest().getHeaders().getFirst("X-GitHub-Hook-ID");
            
            if (repoHeader != null && !repoHeader.isBlank()) {
                return Mono.just("webhook:repo:" + repoHeader.trim());
            }
            if (deliveryId != null && !deliveryId.isBlank()) {
                InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                String ip = (remote != null && remote.getAddress() != null)
                        ? remote.getAddress().getHostAddress() : "github-ingress";
                return Mono.just("webhook:ingress:" + ip);
            }

            return Mono.just("webhook:ip:github-ingress");
        };
    }
}
```

### C. Route-Level Token Bucket Quotas

In `application.yml`, each route declares fine-tuned token bucket rates:

| Route Path | Key Resolver | Replenish Rate | Burst Capacity | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| `/api/v1/webhooks/**` | `webhookKeyResolver` | **100 req/sec** | **100 tokens** | Absorbs high-volume CI/CD push bursts from GitHub without dropped deliveries. |
| `/api/v1/auth/**`, `/api/v1/users/**` | `userKeyResolver` | **5 req/sec** | **20 tokens** | Mitigates credential stuffing and brute-force token generation. |
| `/api/v1/projects/**`, `/api/v1/tasks/**` | `userKeyResolver` | **5 req/sec** | **20 tokens** | Standard interactive UI navigation and task edits. |
| `/api/v1/operations/**` | `userKeyResolver` | **10 req/sec** | **50 tokens** | Allows frequent dashboard telemetry polling (telemetry, outbox status). |
| `/api/v1/ai/**` | `userKeyResolver` | **5 req/sec** | **20 tokens** | Prevents client hammering of compute-heavy AI endpoints at the perimeter. |

When a bucket is depleted, Redis immediately signals Spring Cloud Gateway to abort the request with **`HTTP 429 Too Many Requests`**, never reaching downstream microservices.

---

## 3. Tier 2: AI LLM Token & Request Metering (`ai-service`)

### A. Location & Technology
* **Files:**
  * `services/ai-service/app/infrastructure/resilience/redis_rate_limiter.py`
  * `services/ai-service/app/presentation/dependencies.py`
* **Engine:** Redis-backed sliding-window log + atomic daily token counters with in-memory failover.

### B. Two-Dimensional Quota Metering
LLMs have two distinct exhaustion metrics: **Requests per Minute (RPM)** and **Tokens per Minute / Day (TPM)**. ForgeAI enforces both:

1. **Sliding-Window Request Rate (RPM):**
   - Uses Redis Sorted Sets (`ZSET`) at `llm:ratelimit:{org_id}`.
   - Executes atomic pipeline:
     ```python
     pipe.zremrangebyscore(rate_key, 0, now - 60.0) # Purge entries older than 60s
     pipe.zcard(rate_key)                            # Count remaining requests in window
     pipe.zadd(rate_key, {f"{now}": now})            # Record current timestamp
     pipe.expire(rate_key, 120)                      # Key TTL
     ```
   - If `current_requests >= max_requests_per_minute` (default 60), it calculates remaining window seconds: `retry_after = 60 - int(now % 60)` and rejects.

2. **Daily Token Consumption Meter (24-Hour Quota):**
   - Atomically tracks accumulated token usage at `llm:quota:{org_id}`.
   - If `daily_used + estimated_tokens > daily_token_limit` (default 100,000 tokens/day), it calculates seconds until midnight reset: `retry_after = 86400 - int(now % 86400)`.

```python
async def check_ai_quota(org_id: UUID = Depends(get_tenant_org_id)):
    allowed, retry_after = await ai_rate_limiter.check_and_consume_quota(
        org_id=org_id,
        estimated_tokens=500,
        max_requests_per_minute=60,
        daily_token_limit=100_000,
    )
    if not allowed:
        raise HTTPException(
            status_code=429,
            detail="AI organizational quota or rate limit exceeded",
            headers={"Retry-After": str(retry_after or 60)},
        )
```

### C. Fail-Safe In-Memory Fallback
If the Redis cluster is unavailable or unreachable, `RedisAiRateLimiter` catches the exception and falls back to local Python dictionaries (`_local_sliding_window` and `_local_daily_tokens`), guaranteeing high availability without crashing.

### D. Upstream LLM 429 Provider Failover
When calling external AI providers (Google Gemini or Groq):
* If Groq or Gemini returns `HTTP 429` (provider quota exceeded), `GroqProvider` / `GeminiProvider` logs the retryable status and performs exponential backoff.
* If retries are exhausted, the provider throws a `ProviderUnavailableException`, causing `AiService` to automatically switch providers (e.g. Gemini $\rightarrow$ Groq) without failing the client request.

---

## 4. Tier 3: GitHub API Budgeting & Pre-Flight Throttling (`source-control-service`)

### A. Location & Technology
* **Files:**
  * `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/ratelimit/GitHubRateLimitTracker.java`
  * `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/github/GitHubAppClient.java`
  * `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/github/GitHubRateLimitException.java`
  * `services/source-control-service/src/main/java/com/forgeai/sourcecontrol/infrastructure/actuator/GitHubHealthIndicator.java`

### B. Proactive Dynamic Header Tracking
GitHub provides 5,000 requests/hour per App installation. Instead of blindly making calls until a `403/429` error occurs, `GitHubAppClient` extracts rate limit headers from every single GitHub HTTP response:
* `X-RateLimit-Limit`
* `X-RateLimit-Remaining`
* `X-RateLimit-Reset` (epoch seconds)

`GitHubRateLimitTracker` stores this state in Redis under `github:ratelimit:{installationId}`:
```java
public void updateRateLimit(Long installationId, int limit, int remaining, long resetEpochSeconds) {
    localLimitMap.put(installationId, limit);
    localRemainingMap.put(installationId, remaining);
    localResetMap.put(installationId, Instant.ofEpochSecond(resetEpochSeconds));

    String key = REDIS_KEY_PREFIX + installationId;
    redisTemplate.opsForHash().put(key, "limit", String.valueOf(limit));
    redisTemplate.opsForHash().put(key, "remaining", String.valueOf(remaining));
    redisTemplate.opsForHash().put(key, "reset", String.valueOf(resetEpochSeconds));
    
    long ttlSeconds = Math.max(60, resetEpochSeconds - Instant.now().getEpochSecond());
    redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
}
```

### C. Pre-Flight Predictive Throttling (`acquireApiPermit`)
Before running low-priority background synchronizations (commit polling, historical PR indexing), `source-control-service` checks if sufficient budget remains:

```java
public boolean acquireApiPermit(Long installationId, boolean isHighPriority) {
    if (isHighPriority) {
        return true; // Webhooks and direct user interactions always proceed
    }

    double remainingRatio = (double) remaining / (double) Math.max(1, limit);
    if (remainingRatio < 0.10) { // Below 10% threshold (< 500 requests)
        log.warn("Throttling background API call for installation {}: capacity {}% (< 10% threshold)",
                installationId, String.format("%.2f", remainingRatio * 100));
        return false;
    }
    return true;
}
```
* **Why this matters:** Background batch jobs are throttled so that critical user operations (opening a PR, linking a repository) never hit a hard GitHub block.

### D. Safe Backoff on GitHub HTTP 429
If GitHub returns a `429`:
1. `GitHubAppClient` checks the `Retry-After` header.
2. For safe idempotent `GET` calls, it sleeps up to 5 seconds and retries.
3. If still blocked, it throws `GitHubRateLimitException(message, waitSeconds, remaining, resetTimestamp)`.

### E. Health Indicator Integration
In `GitHubHealthIndicator.java`, if `rateLimitRemaining <= 0`, Spring Boot Actuator automatically marks GitHub health as `DEGRADED`:
```json
{
  "status": "DEGRADED",
  "details": {
    "rateLimitRemaining": 0,
    "message": "GitHub API rate limit exhausted"
  }
}
```

---

## 5. Tier 4: Client-Side Graceful Degradation (`frontend/`)

### A. Location & Technology
* **File:** `frontend/src/lib/api-client.ts`

### B. Human-Friendly Error Sanitization
When an API call returns `429`, the frontend intercepts it in `sanitizeUserFacingError()`:

```typescript
const HTTP_ERROR_MESSAGES: Record<number, string> = {
  401: "Your session has expired. Please sign in again to continue.",
  403: "You don't have permission to perform this action in the current workspace.",
  429: "Too many requests in a short period. Please wait a moment and try again.",
  500: "Something went wrong on our end. Please try again in a moment.",
  ...
};
```

* **Toast Feedback:** Displays a non-intrusive warning with the server-suggested retry duration instead of crashing the React tree or freezing the UI.
* **Form Preservation:** Preserves user inputs and prevents double-click spamming by disabling action buttons during active requests.

---

## 6. Summary Comparison Matrix

| Tier | Component | Rate Limiting Algorithm | Storage / State | Rejection Behavior | Threshold / Limits |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Tier 1 (Edge)** | Spring Cloud Gateway | Token Bucket (`RedisRateLimiter`) | Redis Key / Bucket | Returns `HTTP 429` immediately at perimeter | Webhook: 100 req/s<br/>User API: 5 req/s (20 burst) |
| **Tier 2 (AI)** | `ai-service` | Sliding-Window Log + 24h Counter | Redis `ZSET` + Atomic Key (In-Memory Fallback) | Raises `HTTP 429` with `Retry-After` header | 60 RPM / org<br/>100,000 tokens / day |
| **Tier 3 (GitHub)** | `source-control-service` | Dynamic Header Tracking + Budget Ratio | Redis Hash `github:ratelimit:*` (Local Map Fallback) | Pre-flight throttles background jobs; backs off on 429 | Pre-flight throttle when `< 10%` remaining quota |
| **Tier 4 (Client)** | Next.js Frontend | Interceptor & Error Sanitizer | Browser State | Friendly toast notification; preserves form state | Handles upstream `429` responses cleanly |

---

## 7. How to Explain This in an Engineering Interview

> **Staff Engineer Response:**
>
> *"In ForgeAI, rate limiting is implemented across **four distinct architectural tiers** rather than just a single gateway filter:
>
> 1. **Perimeter Ingress (Spring Cloud Gateway):** We use a Redis Token Bucket with dual key resolution (`userKeyResolver` and `webhookKeyResolver`). This protects our internal microservices from DoS attacks while giving GitHub webhooks a separate high-burst partition (100 req/s) so CI events are never dropped.
> 2. **Compute & Cost Protection (`ai-service`):** Because LLM calls are expensive, we implement a two-dimensional Redis limiter enforcing both a 60 RPM sliding-window log (via Redis `ZSET`) and a 100k daily token quota per organization, with automatic in-memory failover and provider fallback (Gemini $\rightarrow$ Groq).
> 3. **Third-Party Budgeting (`source-control-service`):** GitHub enforces a 5,000 req/hour limit. Our `GitHubRateLimitTracker` parses response headers dynamically into Redis. Before running background sync jobs, it checks capacity; if remaining budget drops below 10%, it preemptively pauses background sync to protect live user actions.
> 4. **Client Experience (`frontend`):** Our API client intercepts HTTP 429 status codes, extracts `Retry-After` headers, and presents sanitized, actionable notifications without crashing the UI."*

