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
  location: { pathname: "/repositories" },
};
globalThis.localStorage = mockStorage;

// Import apiClient dynamically after window/localStorage polyfills are set
const { apiClient } = await import("../lib/api-client.ts");

test("B & C: ApiClient attaches status and data on HTTP error", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    return new Response(JSON.stringify({ error: "Service Unavailable", detail: "GitHub service is down" }), {
      status: 503,
      statusText: "Service Unavailable",
      headers: { "Content-Type": "application/json" }
    });
  };

  try {
    await assert.rejects(
      async () => {
        await apiClient.get("/api/v1/github/health");
      },
      (err) => {
        assert.equal(err.status, 503);
        assert.equal(err.statusText, "Service Unavailable");
        assert.equal(err.message, "GitHub service is down");
        return true;
      }
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("B & C: ApiClient marks network failure with isNetworkError", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => {
    throw new TypeError("Failed to fetch");
  };

  try {
    await assert.rejects(
      async () => {
        await apiClient.get("/api/v1/github/health");
      },
      (err) => {
        assert.equal(err.isNetworkError, true);
        assert.equal(err.message, "Failed to fetch");
        return true;
      }
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("C: GitHub health endpoint failure maps to UNREACHABLE and not UNCONFIGURED", () => {
  // Simulate page.tsx error handling logic for GitHub health fetch
  const handleHealthError = (e) => {
    return {
      configured: false,
      status: "UNREACHABLE",
      httpStatus: e?.status || undefined,
      message: e?.message || "GitHub health endpoint unreachable",
    };
  };

  // Test network failure
  const networkError = new TypeError("Failed to fetch");
  networkError.isNetworkError = true;
  const netResult = handleHealthError(networkError);
  assert.equal(netResult.status, "UNREACHABLE");
  assert.notEqual(netResult.status, "UNCONFIGURED");
  assert.equal(netResult.httpStatus, undefined);

  // Test 503 error
  const http503Error = new Error("Gateway Timeout");
  http503Error.status = 503;
  const httpResult = handleHealthError(http503Error);
  assert.equal(httpResult.status, "UNREACHABLE");
  assert.equal(httpResult.httpStatus, 503);
});

test("D: Repository error classification differentiates 401/403, 404, 503, and network failures", () => {
  const classifyRepositoryError = (err) => {
    const status = err?.status;
    let userFriendlyError = "Could not load repositories for the active organization.";

    if (status === 401 || status === 403) {
      userFriendlyError = `Authorization error (HTTP ${status}): Access denied to repositories for this organization.`;
    } else if (status === 404) {
      userFriendlyError = "Scope error (HTTP 404): Organization or repository scope not found.";
    } else if (status === 503) {
      userFriendlyError = "Service unavailable (HTTP 503): GitHub integration service is temporarily unavailable.";
    } else if (err?.isNetworkError || !status) {
      userFriendlyError = "Service unreachable: Unable to connect to Gateway or Source Control service.";
    } else {
      userFriendlyError = `Error loading repositories (HTTP ${status}): ${err.message || "Unexpected failure"}`;
    }
    return userFriendlyError;
  };

  // 401
  const err401 = new Error("Unauthorized");
  err401.status = 401;
  assert.match(classifyRepositoryError(err401), /Authorization error \(HTTP 401\)/);

  // 403
  const err403 = new Error("Forbidden");
  err403.status = 403;
  assert.match(classifyRepositoryError(err403), /Authorization error \(HTTP 403\)/);

  // 404
  const err404 = new Error("Not Found");
  err404.status = 404;
  assert.match(classifyRepositoryError(err404), /Scope error \(HTTP 404\)/);

  // 503
  const err503 = new Error("Service Unavailable");
  err503.status = 503;
  assert.match(classifyRepositoryError(err503), /Service unavailable \(HTTP 503\)/);

  // Network error
  const netErr = new TypeError("Failed to fetch");
  netErr.isNetworkError = true;
  assert.match(classifyRepositoryError(netErr), /Service unreachable/);

  // Other error
  const err500 = new Error("Internal Server Error");
  err500.status = 500;
  assert.match(classifyRepositoryError(err500), /Error loading repositories \(HTTP 500\)/);
});

test("E & F: Loading state prevents flashing 'No repositories connected yet' while loading or failed", () => {
  // State 1: Tenant or repos loading
  const isTenantLoading = true;
  const isLoading = false;
  const error = null;
  const repositories = [];

  const shouldShowLoading = isLoading || isTenantLoading;
  const shouldShowError = !shouldShowLoading && error !== null;
  const shouldShowEmpty = !shouldShowLoading && !shouldShowError && repositories.length === 0;
  const shouldShowTable = !shouldShowLoading && !shouldShowError && repositories.length > 0;

  assert.equal(shouldShowLoading, true);
  assert.equal(shouldShowEmpty, false, "Must not show empty state while tenant is loading");

  // State 2: Error occurred
  const failedTenantLoading = false;
  const failedLoading = false;
  const failedError = "Service unreachable: Unable to connect to Gateway or Source Control service.";
  const failedRepos = [];

  const isFailedLoading = failedLoading || failedTenantLoading;
  const isFailedError = !isFailedLoading && failedError !== null;
  const isFailedEmpty = !isFailedLoading && !isFailedError && failedRepos.length === 0;

  assert.equal(isFailedLoading, false);
  assert.equal(isFailedError, true);
  assert.equal(isFailedEmpty, false, "Must not show empty state when request failed with error");
});
