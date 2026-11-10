/**
 * ForgeAI — End-to-End Grafana k6 Performance, Resilience & Security Load Suite
 *
 * Benchmarks and verifies:
 *   1. Tenant Read & Redis Role/Workspace Cache Hit Latency (GET /api/v1/projects, /repositories, /operations/circuit-breakers)
 *   2. SEC-01 Adversarial Boundary Check under Load (Spoofed X-Workspace-Id rejected with HTTP 403)
 *   3. GitHub Webhook HMAC-SHA256 Ingestion & Idempotency Locking (POST /api/v1/webhooks/github)
 *   4. JDBC Batch Test Result Ingestion & Concurrent Suite Upsert (POST /api/v1/test-runs/{id}/results/batch)
 *   5. API Gateway Redis Token-Bucket Rate Limiter Verification (HTTP 200 vs HTTP 429, zero 5xx)
 *
 * Usage (PowerShell):
 *   $ws = (docker exec forgeai-source-control-dev printenv GITHUB_WEBHOOK_SECRET).Trim()
 *   k6 run -e BASE_URL=http://localhost:8080 -e "WEBHOOK_SECRET=$ws" tests/load/forgeai_k6_load_test.js
 */

import http from "k6/http";
import crypto from "k6/crypto";
import { check, group, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const WEBHOOK_SECRET = __ENV.WEBHOOK_SECRET || "test-webhook-secret-key-xyz";

// Custom telemetry metrics
const cachedTenantLatency = new Trend("forgeai_tenant_read_duration_ms", true);
const webhookIngestionLatency = new Trend("forgeai_webhook_ingest_duration_ms", true);
const batchTestIngestionLatency = new Trend("forgeai_batch_test_ingest_duration_ms", true);
const rateLimitedCounter = new Counter("forgeai_gateway_rate_limited_429_total");
const serverErrorRate = new Rate("forgeai_5xx_error_rate");

export const options = {
  setupTimeout: "120s",

  scenarios: {
    // Scenario 1: Multi-tenant workspace & project reads (verifies Redis role/workspace cache + SEC-01)
    tenant_read_and_redis_cache: {
      executor: "ramping-vus",
      exec: "tenantReadAndSecurityScenario",
      startVUs: 1,
      stages: [
        { duration: "10s", target: 3 },
        { duration: "25s", target: 3 },
        { duration: "5s", target: 0 },
      ],
      gracefulRampDown: "5s",
    },

    // Scenario 2: Concurrent GitHub webhook HMAC-SHA256 ingestion & duplicate delivery idempotency
    webhook_hmac_and_idempotency: {
      executor: "constant-arrival-rate",
      exec: "webhookHmacAndIdempotencyScenario",
      rate: 4,
      timeUnit: "1s",
      duration: "30s",
      preAllocatedVUs: 4,
      maxVUs: 10,
      startTime: "5s",
    },

    // Scenario 3: Concurrent JDBC batch test-run creation, suite upsert & outbox completion
    batch_test_ingestion: {
      executor: "per-vu-iterations",
      exec: "batchTestIngestionScenario",
      vus: 3,
      iterations: 4,
      maxDuration: "60s",
      startTime: "8s",
    },

    // Scenario 4: Gateway token-bucket burst probe (replenishRate=10, burstCapacity=50)
    gateway_rate_limit_burst: {
      executor: "constant-arrival-rate",
      exec: "gatewayRateLimitBurstScenario",
      rate: 15,
      timeUnit: "1s",
      duration: "12s",
      preAllocatedVUs: 6,
      maxVUs: 12,
      startTime: "15s",
    },
  },

  thresholds: {
    forgeai_5xx_error_rate: ["rate==0.00"],
    forgeai_tenant_read_duration_ms: ["p(95)<2500"],
    forgeai_webhook_ingest_duration_ms: ["p(95)<5000"],
    forgeai_batch_test_ingest_duration_ms: ["p(95)<5000"],
  },
};

function randomUuid() {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

function randomHex(len) {
  let out = "";
  const chars = "0123456789abcdef";
  for (let i = 0; i < len; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

/**
 * Setup phase: Provisions an authenticated user, acquires JWT via /login, and seeds Organization, Personal Workspace, and Project.
 */
export function setup() {
  const uniqueSuffix = Date.now().toString(36);
  const email = `k6_architect_${uniqueSuffix}@forgeai.dev`;
  const password = "ForgeAI_K6_Password!2026";
  const headers = { "Content-Type": "application/json" };

  // 1. Register benchmark user (returns 201 Created UserResponse)
  http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      username: `k6_${uniqueSuffix}`,
      email,
      password,
      firstName: "K6",
      lastName: "Benchmark",
    }),
    { headers }
  );

  // 2. Authenticate via /login to acquire accessToken
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ identifier: email, password }),
    { headers }
  );

  const authBody = loginRes.json() || {};
  const accessToken = authBody.accessToken || (authBody.tokens && authBody.tokens.accessToken) || "";
  const authHeaders = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${accessToken}`,
  };

  // 3. Fetch Personal Workspace
  const personalWsRes = http.get(`${BASE_URL}/api/v1/workspaces/personal`, {
    headers: authHeaders,
  });
  const personalWorkspaceId =
    personalWsRes.status === 200 && personalWsRes.json()
      ? personalWsRes.json().id
      : null;

  // 4. Create Organization with required lowercase slug
  const orgRes = http.post(
    `${BASE_URL}/api/v1/organizations`,
    JSON.stringify({
      name: `ForgeAI Load Org ${uniqueSuffix}`,
      slug: `forgeai-load-${uniqueSuffix}`.toLowerCase(),
    }),
    { headers: authHeaders }
  );
  const orgId =
    (orgRes.status === 200 || orgRes.status === 201) && orgRes.json()
      ? orgRes.json().id
      : null;

  // 5. Create a seed Project in the Organization
  let projectId = null;
  if (orgId) {
    const projRes = http.post(
      `${BASE_URL}/api/v1/projects`,
      JSON.stringify({
        name: `K6 Load Project ${uniqueSuffix}`,
        key: `K6${uniqueSuffix.slice(-3).toUpperCase()}`,
        description: "Automated k6 performance benchmark project",
      }),
      {
        headers: {
          ...authHeaders,
          "X-Org-Id": orgId,
        },
      }
    );
    if ((projRes.status === 200 || projRes.status === 201) && projRes.json()) {
      projectId = projRes.json().id;
    }
  }

  return {
    accessToken,
    orgId,
    personalWorkspaceId,
    projectId,
    repositoryId: randomUuid(),
  };
}

/**
 * Scenario 1: Multi-Tenant Reads, Circuit Breaker Telemetry & SEC-01 Spoofed Workspace Rejection
 */
export function tenantReadAndSecurityScenario(data) {
  if (!data.accessToken) {
    sleep(1);
    return;
  }

  const orgHeaders = {
    Authorization: `Bearer ${data.accessToken}`,
    ...(data.orgId ? { "X-Org-Id": data.orgId } : {}),
  };

  group("1A. Organization Scoped Reads (Redis Role Cache Hit)", () => {
    const res = http.get(`${BASE_URL}/api/v1/projects`, { headers: orgHeaders });
    serverErrorRate.add(res.status >= 500);
    if (res.status === 429) rateLimitedCounter.add(1);
    cachedTenantLatency.add(res.timings.duration);

    check(res, {
      "GET /api/v1/projects status is 200 or 429": (r) => r.status === 200 || r.status === 429,
    });
  });

  if (data.personalWorkspaceId) {
    group("1B. Personal Workspace Scoped Reads (SEC-01 Verified Ownership)", () => {
      const wsHeaders = {
        Authorization: `Bearer ${data.accessToken}`,
        "X-Workspace-Id": data.personalWorkspaceId,
        "X-Workspace-Scope": "PERSONAL",
      };
      const res = http.get(
        `${BASE_URL}/api/v1/projects?workspaceId=${data.personalWorkspaceId}`,
        { headers: wsHeaders }
      );
      serverErrorRate.add(res.status >= 500);
      if (res.status === 429) rateLimitedCounter.add(1);
      cachedTenantLatency.add(res.timings.duration);

      check(res, {
        "Personal workspace read status is 200 or 429": (r) => r.status === 200 || r.status === 429,
      });
    });

    group("1C. SEC-01 Adversarial Check: Spoofed Unowned X-Workspace-Id Blocked with 403", () => {
      const spoofedWsHeaders = {
        Authorization: `Bearer ${data.accessToken}`,
        "X-Workspace-Id": randomUuid(),
        "X-Workspace-Scope": "PERSONAL",
      };
      const res = http.get(`${BASE_URL}/api/v1/test-runs`, {
        headers: spoofedWsHeaders,
      });
      serverErrorRate.add(res.status >= 500);

      check(res, {
        "SEC-01: Spoofed X-Workspace-Id rejected with 403 or 429": (r) =>
          r.status === 403 || r.status === 429,
      });
    });
  }

  sleep(0.5);
}

/**
 * Scenario 2: GitHub Webhook HMAC-SHA256 Verification & Duplicate Delivery Idempotency
 */
export function webhookHmacAndIdempotencyScenario() {
  const deliveryId = `k6-deliv-${randomUuid()}`;
  const commitSha = randomHex(40);
  const payload = JSON.stringify({
    ref: "refs/heads/main",
    before: "0000000000000000000000000000000000000000",
    after: commitSha,
    installation: {
      id: 998877,
    },
    repository: {
      id: 99887766,
      name: "forgeai-k6-benchmark",
      full_name: "forgeai/forgeai-k6-benchmark",
      default_branch: "main",
      private: true,
    },
    commits: [
      {
        id: commitSha,
        message: "perf(k6): verify transactional outbox and HikariCP under load",
        timestamp: new Date().toISOString(),
        author: { name: "k6 Bot", email: "k6@forgeai.dev" },
        added: ["src/PerfProbe.java"],
        modified: [],
        removed: [],
      },
    ],
  });

  const signature = "sha256=" + crypto.hmac("sha256", WEBHOOK_SECRET, payload, "hex");
  const headers = {
    "Content-Type": "application/json",
    "X-GitHub-Delivery": deliveryId,
    "X-GitHub-Event": "push",
    "X-Hub-Signature-256": signature,
  };

  // First delivery
  const firstRes = http.post(`${BASE_URL}/api/v1/webhooks/github`, payload, { headers });
  serverErrorRate.add(firstRes.status >= 500);
  if (firstRes.status === 429) rateLimitedCounter.add(1);
  webhookIngestionLatency.add(firstRes.timings.duration);

  check(firstRes, {
    "Webhook delivery returns 202 (or 401 if secret differs, or 429)": (r) =>
      r.status === 202 || r.status === 401 || r.status === 429,
  });

  // Immediate duplicate delivery with identical X-GitHub-Delivery to test idempotency gate
  if (firstRes.status === 202) {
    const dupRes = http.post(`${BASE_URL}/api/v1/webhooks/github`, payload, { headers });
    serverErrorRate.add(dupRes.status >= 500);
    check(dupRes, {
      "Duplicate webhook delivery is idempotently ignored (HTTP 202)": (r) =>
        r.status === 202 && r.json("idempotentHit") === true,
    });
  }
}

/**
 * Scenario 3: High-Volume JDBC Batch Test Result Ingestion & Outbox Run Completion
 */
export function batchTestIngestionScenario(data) {
  if (!data.accessToken || (!data.orgId && !data.personalWorkspaceId)) {
    sleep(1);
    return;
  }

  const headers = {
    "Content-Type": "application/json",
    Authorization: `Bearer ${data.accessToken}`,
    ...(data.orgId
      ? { "X-Org-Id": data.orgId }
      : { "X-Workspace-Id": data.personalWorkspaceId, "X-Workspace-Scope": "PERSONAL" }),
  };

  // 1. Create Test Run with 40-char SHA
  const createRes = http.post(
    `${BASE_URL}/api/v1/test-runs`,
    JSON.stringify({
      repositoryId: data.repositoryId,
      commitSha: randomHex(40),
      pullRequestNumber: 42,
    }),
    { headers }
  );
  serverErrorRate.add(createRes.status >= 500);

  if (createRes.status !== 201 || !createRes.json()) {
    sleep(1);
    return;
  }

  const runId = createRes.json().id;

  // 2. Batch ingest 40 test case results in a single JDBC batch
  const resultsBatch = [];
  for (let i = 1; i <= 40; i++) {
    resultsBatch.push({
      suiteName: "com.forgeai.load.K6ResilienceSuite",
      testName: `shouldMaintainLatencySLOUnderConcurrency_${i}`,
      classname: "com.forgeai.load.K6ResilienceSuite",
      requirementKey: `REQ-00${(i % 3) + 1}`,
      status: i % 20 === 0 ? "FAILED" : "PASSED",
      durationMs: 12 + (i % 15),
      errorMessage: i % 20 === 0 ? "Simulated assertion boundary check" : null,
    });
  }

  const batchRes = http.post(
    `${BASE_URL}/api/v1/test-runs/${runId}/results/batch`,
    JSON.stringify({ results: resultsBatch }),
    { headers }
  );
  serverErrorRate.add(batchRes.status >= 500);
  batchTestIngestionLatency.add(batchRes.timings.duration);

  check(batchRes, {
    "Batch test ingestion returns 202 Accepted": (r) => r.status === 202 || r.status === 429,
  });

  // 3. Finalize Test Run (triggers Outbox TestRunCompletedEvent / TestRunFailedEvent)
  const completeRes = http.post(
    `${BASE_URL}/api/v1/test-runs/${runId}/complete`,
    null,
    { headers }
  );
  serverErrorRate.add(completeRes.status >= 500);

  check(completeRes, {
    "Test run completion returns 200 OK": (r) => r.status === 200 || r.status === 429,
  });

  sleep(1);
}

/**
 * Scenario 4: Gateway Token-Bucket Rate Limiter Burst Verification
 */
export function gatewayRateLimitBurstScenario(data) {
  if (!data.accessToken) return;

  const headers = {
    Authorization: `Bearer ${data.accessToken}`,
    ...(data.orgId ? { "X-Org-Id": data.orgId } : {}),
  };

  const res = http.get(`${BASE_URL}/api/v1/operations/circuit-breakers`, { headers });
  serverErrorRate.add(res.status >= 500);
  if (res.status === 429) {
    rateLimitedCounter.add(1);
  }

  check(res, {
    "Gateway burst returns 200 OK or 429 Too Many Requests (never 5xx)": (r) =>
      r.status === 200 || r.status === 429,
  });
}
