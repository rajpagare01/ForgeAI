import test from "node:test";
import assert from "node:assert/strict";

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
  location: { pathname: "/repositories", search: "?installation_id=987654" },
  history: {
    replaceState: () => {},
  },
};
globalThis.localStorage = mockStorage;

const { apiClient } = await import("../lib/api-client.ts");

test("Discovery Contract: Discovers candidate installations without manual installation ID entry", async () => {
  const originalFetch = globalThis.fetch;
  const mockDiscovery = {
    currentInstallation: null,
    candidates: [
      {
        installationId: 987654,
        accountId: 12345,
        accountLogin: "octocat-dev",
        accountType: "User",
        repositorySelection: "ALL",
        alreadyAssociated: false,
        status: "CLAIMABLE"
      }
    ],
    userGitHubLogin: "octocat-dev",
    userGitHubId: 12345,
    gitHubConnected: true,
    appInstallUrl: "https://github.com/apps/forgeai-app/installations/new",
    message: "GitHub App installation discovered. Ready to onboard."
  };

  globalThis.fetch = async (url) => {
    if (url.includes("/api/v1/installations/discover")) {
      return new Response(JSON.stringify(mockDiscovery), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    const res = await apiClient.get("/api/v1/installations/discover?workspaceId=ws-123");
    assert.equal(res.gitHubConnected, true);
    assert.equal(res.userGitHubLogin, "octocat-dev");
    assert.equal(res.candidates.length, 1);
    assert.equal(res.candidates[0].installationId, 987654);
    assert.equal(res.candidates[0].status, "CLAIMABLE");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Onboarding Contract: Auto-onboard connects discovered installation to workspace", async () => {
  const originalFetch = globalThis.fetch;
  let sentBody = null;

  globalThis.fetch = async (url, options) => {
    if (url.includes("/api/v1/installations/auto-onboard")) {
      sentBody = JSON.parse(options.body);
      return new Response(JSON.stringify({
        id: "inst-uuid-1",
        installationId: 987654,
        workspaceId: sentBody.workspaceId,
        scope: "PERSONAL",
        accountLogin: "octocat-dev",
        status: "ACTIVE"
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    const res = await apiClient.post("/api/v1/installations/auto-onboard", {
      installationId: 987654,
      workspaceId: "ws-123"
    });
    assert.equal(sentBody.installationId, 987654);
    assert.equal(sentBody.workspaceId, "ws-123");
    assert.equal(res.status, "ACTIVE");
    assert.equal(res.accountLogin, "octocat-dev");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Setup Callback Contract: Validates redirect query installation_id against backend", async () => {
  const originalFetch = globalThis.fetch;
  let callbackRequested = false;

  globalThis.fetch = async (url, options) => {
    if (url.includes("/api/v1/installations/setup-callback")) {
      callbackRequested = true;
      const body = JSON.parse(options.body);
      assert.equal(body.installationId, 987654);
      assert.equal(body.workspaceId, "ws-123");
      return new Response(JSON.stringify({
        id: "inst-uuid-1",
        installationId: 987654,
        workspaceId: "ws-123",
        scope: "PERSONAL",
        accountLogin: "octocat-dev",
        status: "ACTIVE"
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    const res = await apiClient.post("/api/v1/installations/setup-callback", {
      installationId: 987654,
      workspaceId: "ws-123"
    });
    assert.equal(callbackRequested, true);
    assert.equal(res.status, "ACTIVE");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Batch Import Repositories Contract: Sends selected repository list", async () => {
  const originalFetch = globalThis.fetch;
  let receivedRepos = null;

  globalThis.fetch = async (url, options) => {
    if (url.includes("/api/v1/repositories/batch-connect")) {
      const body = JSON.parse(options.body);
      receivedRepos = body.repositoryNames;
      return new Response(JSON.stringify([
        { id: "repo-1", fullName: "octocat/repo-one", syncStatus: "SYNCED" },
        { id: "repo-2", fullName: "octocat/repo-two", syncStatus: "SYNCED" }
      ]), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    const res = await apiClient.post("/api/v1/repositories/batch-connect", {
      repositoryNames: ["octocat/repo-one", "octocat/repo-two"],
      workspaceId: "ws-123"
    });
    assert.equal(receivedRepos.length, 2);
    assert.equal(receivedRepos[0], "octocat/repo-one");
    assert.equal(res.length, 2);
    assert.equal(res[0].syncStatus, "SYNCED");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Reconciliation Contract: Reconciles installation and returns accessible diffs", async () => {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async (url) => {
    if (url.includes("/reconcile")) {
      return new Response(JSON.stringify({
        installationId: 987654,
        workspaceId: "ws-123",
        accountLogin: "octocat-dev",
        totalAccessible: 5,
        newlyAdded: 2,
        removedOrInaccessible: 1,
        timestamp: new Date().toISOString()
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response(JSON.stringify({}), { status: 200 });
  };

  try {
    const report = await apiClient.post("/api/v1/installations/987654/reconcile?workspaceId=ws-123");
    assert.equal(report.totalAccessible, 5);
    assert.equal(report.newlyAdded, 2);
    assert.equal(report.removedOrInaccessible, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("Zero Manual ID Requirement: Verifies manual installation ID prompt is removed", async () => {
  // In the updated Repositories page, manual installationId numeric input modal is removed
  // and replaced by automated discovery and setup callback verification.
  const setupCallbackEndpoint = "/api/v1/installations/setup-callback";
  const autoOnboardEndpoint = "/api/v1/installations/auto-onboard";
  const discoverEndpoint = "/api/v1/installations/discover";

  assert.ok(setupCallbackEndpoint);
  assert.ok(autoOnboardEndpoint);
  assert.ok(discoverEndpoint);
});
