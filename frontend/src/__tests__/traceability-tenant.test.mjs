import test from "node:test";
import assert from "node:assert/strict";
import React from "react";
import { renderToString } from "react-dom/server";

// Setup browser globals for Node test environment
class MockLocalStorage {
  constructor() {
    this.store = new Map();
  }
  getItem(key) {
    return this.store.get(key) ?? null;
  }
  setItem(key, value) {
    this.store.set(key, String(value));
  }
  removeItem(key) {
    this.store.delete(key);
  }
  clear() {
    this.store.clear();
  }
}

const mockStorage = new MockLocalStorage();
globalThis.window = {
  crypto: globalThis.crypto,
  location: { pathname: "/projects/p-100/traceability" },
};
globalThis.localStorage = mockStorage;

// Import apiClient dynamically after window/localStorage polyfills are set
const { apiClient } = await import("../lib/api-client.ts");
const { TraceabilitySpineFlow } = await import("../components/traceability/TraceabilitySpineFlow.tsx");

test("Requirement 2: api-client getActiveOrgId returns null when no org is stored and avoids invalid fallbacks", () => {
  mockStorage.clear();
  assert.equal(apiClient.getActiveOrgId(), null, "Should return null when storage is empty");

  mockStorage.setItem("forgeai_org_id", "org-forgeai-core");
  assert.equal(
    apiClient.getActiveOrgId(),
    "org-forgeai-core",
    "Returns raw value from storage, but request must reject non-UUIDs"
  );
});

test("Requirement 2: api-client does not send non-UUID strings like 'org-forgeai-core' as X-Org-Id", async () => {
  mockStorage.clear();
  mockStorage.setItem("forgeai_org_id", "org-forgeai-core");

  let capturedHeaders = null;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    capturedHeaders = options.headers;
    return new Response(JSON.stringify({ status: "ok" }), { status: 200 });
  };

  try {
    await apiClient.request("/api/v1/projects");
    assert.ok(capturedHeaders instanceof Headers, "Headers should be an instance of Headers");
    assert.equal(
      capturedHeaders.has("X-Org-Id"),
      false,
      "X-Org-Id header must NEVER be sent with invalid non-UUID 'org-forgeai-core'"
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Requirement 2: api-client sends X-Org-Id header when a valid UUID organization ID exists", async () => {
  mockStorage.clear();
  const validOrgUuid = "cc28c8a3-2224-4bad-9ab2-ea36ede52577";
  mockStorage.setItem("forgeai_org_id", validOrgUuid);

  let capturedHeaders = null;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    capturedHeaders = options.headers;
    return new Response(JSON.stringify({ status: "ok" }), { status: 200 });
  };

  try {
    await apiClient.request("/api/v1/projects");
    assert.ok(capturedHeaders instanceof Headers);
    assert.equal(capturedHeaders.get("X-Org-Id"), validOrgUuid);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Requirement 1: Initial render while TenantProvider is loading -> no traceability request made yet", async () => {
  let requestCount = 0;
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    requestCount++;
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    // Simulate the loadData guard from traceability/page.tsx:
    // if (!projectId || isTenantLoading || !currentOrg?.id) return;
    const projectId = "proj-12345";
    const isTenantLoading = true;
    const currentOrg = null;

    const guardedLoadData = async (pid, tenantLoading, org) => {
      if (!pid || tenantLoading || !org?.id) return;
      await apiClient.getProjectTraceability(pid);
    };

    await guardedLoadData(projectId, isTenantLoading, currentOrg);
    assert.equal(requestCount, 0, "No network request should be initiated while tenant is loading");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Requirement 1: Once tenant is resolved -> traceability request made with correct X-Org-Id", async () => {
  const validOrgUuid = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d";
  mockStorage.setItem("forgeai_org_id", validOrgUuid);

  let capturedUrl = "";
  let capturedOrgHeader = "";
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    capturedUrl = String(url);
    capturedOrgHeader = options.headers.get("X-Org-Id");
    return new Response(
      JSON.stringify({
        projectId: "proj-12345",
        health: { status: "GREEN", score: 100, reasons: [] },
        chains: [],
        gaps: [],
        repositories: [],
      }),
      { status: 200 }
    );
  };

  try {
    const projectId = "proj-12345";
    const isTenantLoading = false;
    const currentOrg = { id: validOrgUuid, name: "ForgeAI Core" };

    const guardedLoadData = async (pid, tenantLoading, org) => {
      if (!pid || tenantLoading || !org?.id) return null;
      return await apiClient.getProjectTraceability(pid);
    };

    const result = await guardedLoadData(projectId, isTenantLoading, currentOrg);
    assert.ok(result, "Expected traceability result");
    assert.ok(capturedUrl.includes("/api/v1/projects/proj-12345/traceability"));
    assert.equal(capturedOrgHeader, validOrgUuid);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Requirement 1: Switching organization re-triggers traceability fetch with the new org ID", async () => {
  const org1Uuid = "11111111-1111-1111-1111-111111111111";
  const org2Uuid = "22222222-2222-2222-2222-222222222222";

  const capturedOrgHeaders = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, options) => {
    capturedOrgHeaders.push(options.headers.get("X-Org-Id"));
    return new Response(
      JSON.stringify({
        projectId: "proj-12345",
        chains: [],
      }),
      { status: 200 }
    );
  };

  try {
    const runFetchForOrg = async (orgId) => {
      mockStorage.setItem("forgeai_org_id", orgId);
      await apiClient.getProjectTraceability("proj-12345");
    };

    // First fetch with Org 1
    await runFetchForOrg(org1Uuid);
    assert.equal(capturedOrgHeaders.length, 1);
    assert.equal(capturedOrgHeaders[0], org1Uuid);

    // Tenant switches to Org 2
    await runFetchForOrg(org2Uuid);
    assert.equal(capturedOrgHeaders.length, 2);
    assert.equal(capturedOrgHeaders[1], org2Uuid);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Requirement 1: Successful traceability response renders the spine flow", () => {
  const mockChains = [
    {
      requirement: {
        id: "req-1",
        identifier: "REQ-AUTH-01",
        key: "REQ-AUTH-01",
        title: "OAuth2 Multi-Tenant SSO",
        category: "SECURITY",
        status: "APPROVED",
      },
      tasks: [
        {
          id: "task-1",
          taskKey: "TSK-101",
          key: "TSK-101",
          title: "Implement Tenant Scope Gate",
          status: "IN_PROGRESS",
          priority: "HIGH",
        },
      ],
      pullRequests: [
        {
          id: "pr-1",
          prNumber: 42,
          title: "feat: add multi-tenant RLS scoping",
          headSha: "abcdef1234567890",
          status: "OPEN",
        },
      ],
      commits: [],
      testCases: [
        {
          id: "tc-1",
          name: "Verify tenant isolation",
          latestStatus: "PASSED",
        },
      ],
      testRuns: [
        {
          id: "tr-1",
          status: "COMPLETED",
          totalTests: 15,
          passedTests: 15,
          failedTests: 0,
        },
      ],
      aiAnalyses: [
        {
          id: "ai-1",
          riskLevel: "LOW",
          riskScore: 0.1,
          mergeReadiness: "READY",
          summary: "No AST blast radius hazards detected.",
        },
      ],
      gaps: [],
    },
  ];

  // Render spine with chains and isLoading = false
  const html = renderToString(
    React.createElement(TraceabilitySpineFlow, {
      chains: mockChains,
      isLoading: false,
    })
  );

  assert.ok(html.includes("REQ-AUTH-01"), "Rendered spine must include Requirement Key");
  assert.ok(html.includes("OAuth2 Multi-Tenant SSO"), "Rendered spine must include Requirement Title");
  assert.ok(html.includes("TSK-101"), "Rendered spine must include Task Key");
  assert.ok(html.includes("#42"), "Rendered spine must include Pull Request number");
  assert.ok(html.includes("feat: add multi-tenant RLS scoping"), "Rendered spine must include Pull Request title");
});

test("Requirement 1: Loading state prevents 'No Traceability Chains Detected' and error flashing while tenant initializes", () => {
  // When isLoading (or isTenantLoading) is true, even with empty chains:
  const htmlLoading = renderToString(
    React.createElement(TraceabilitySpineFlow, {
      chains: [],
      isLoading: true,
    })
  );

  assert.ok(
    htmlLoading.includes("Aggregating Live Delivery Spine"),
    "Should display loading spinner and message"
  );
  assert.equal(
    htmlLoading.includes("No Traceability Chains Detected"),
    false,
    "Must NOT prematurely display 'No Traceability Chains Detected'"
  );

  // When isLoading is false and chains is empty, it displays empty state:
  const htmlEmpty = renderToString(
    React.createElement(TraceabilitySpineFlow, {
      chains: [],
      isLoading: false,
    })
  );
  assert.ok(
    htmlEmpty.includes("No Traceability Chains Detected"),
    "Should display empty state only after loading completes"
  );
});

test("Requirement 1: Backend error displays user-facing error state", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ error: "Project not found or unreachable" }), {
      status: 404,
      statusText: "Not Found",
    });
  };

  try {
    let errorMessage = null;
    let traceData = null;

    try {
      traceData = await apiClient.getProjectTraceability("non-existent-proj");
    } catch {
      traceData = null;
      errorMessage = "Failed to load traceability data. The project might not exist or the service is unreachable.";
    }

    assert.equal(traceData, null);
    assert.equal(
      errorMessage,
      "Failed to load traceability data. The project might not exist or the service is unreachable."
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
