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
  location: { pathname: "/auth/github/callback", origin: "http://localhost:3000" },
};
globalThis.localStorage = mockStorage;

const { apiClient } = await import("../lib/api-client.ts");

test("GitHub Auth: Start authorization returns authorize URL and anti-CSRF state", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url) => {
    assert.match(url.toString(), /\/api\/v1\/auth\/github\/start\?redirectUri=/);
    return new Response(JSON.stringify({
      authorizationUrl: "https://github.com/login/oauth/authorize?client_id=gh-123&state=xyz987",
      state: "xyz987"
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" }
    });
  };

  try {
    const callbackUri = "http://localhost:3000/auth/github/callback";
    const res = await apiClient.get(
      `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`,
      { skipAuth: true }
    );

    assert.equal(res.state, "xyz987");
    assert.match(res.authorizationUrl, /github\.com\/login\/oauth\/authorize/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("GitHub Auth: Callback exchanges code and receives ForgeAI JWT session", async () => {
  const originalFetch = globalThis.fetch;
  let receivedBody = null;

  globalThis.fetch = async (url, options) => {
    if (url.toString().includes("/api/v1/auth/github/callback")) {
      receivedBody = JSON.parse(options.body);
      return new Response(JSON.stringify({
        accessToken: "forgeai-jwt-session-abc",
        refreshToken: "refresh-token-xyz",
        tokenType: "Bearer",
        expiresIn: 900,
        user: {
          id: "11111111-2222-3333-4444-555555555555",
          username: "octocat",
          email: "octocat@github.com",
          firstName: "The",
          lastName: "Octocat"
        }
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("{}", { status: 200 });
  };

  try {
    const res = await apiClient.post(
      "/api/v1/auth/github/callback",
      { code: "code123", state: "state456", redirectUri: "http://localhost:3000/auth/github/callback" },
      { skipAuth: true }
    );

    assert.equal(receivedBody.code, "code123");
    assert.equal(receivedBody.state, "state456");
    assert.equal(res.accessToken, "forgeai-jwt-session-abc");
    assert.equal(res.user.username, "octocat");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("GitHub Auth: Link account attaches GitHub identity to authenticated user", async () => {
  const originalFetch = globalThis.fetch;
  apiClient.setAccessToken("valid-user-jwt");

  globalThis.fetch = async (url, options) => {
    if (url.toString().includes("/api/v1/auth/github/link")) {
      return new Response(JSON.stringify({
        githubUserId: 99999,
        githubLogin: "developer_octo",
        name: "Dev Octo",
        email: "dev@octo.com",
        avatarUrl: "https://avatars.github.com/u/99999",
        profileUrl: "https://github.com/developer_octo"
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("{}", { status: 200 });
  };

  try {
    const res = await apiClient.post(
      "/api/v1/auth/github/link",
      { code: "code_link", state: "state_link" }
    );

    assert.equal(res.githubUserId, 99999);
    assert.equal(res.githubLogin, "developer_octo");
  } finally {
    globalThis.fetch = originalFetch;
    apiClient.setAccessToken(null);
  }
});

test("GitHub Auth: Disconnect account calls backend unlinking endpoint", async () => {
  const originalFetch = globalThis.fetch;
  apiClient.setAccessToken("valid-user-jwt");
  let disconnectCalled = false;

  globalThis.fetch = async (url, options) => {
    if (url.toString().includes("/api/v1/auth/github/disconnect")) {
      disconnectCalled = true;
      assert.equal(options.method, "POST");
      return new Response(JSON.stringify({ message: "GitHub account unlinked successfully" }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("{}", { status: 200 });
  };

  try {
    const res = await apiClient.post("/api/v1/auth/github/disconnect", {});
    assert.equal(disconnectCalled, true);
    assert.equal(res.message, "GitHub account unlinked successfully");
  } finally {
    globalThis.fetch = originalFetch;
    apiClient.setAccessToken(null);
  }
});

test("GitHub Auth: Status endpoint returns linked state and public profile", async () => {
  const originalFetch = globalThis.fetch;
  apiClient.setAccessToken("valid-user-jwt");

  globalThis.fetch = async (url) => {
    if (url.toString().includes("/api/v1/auth/github/status")) {
      return new Response(JSON.stringify({
        linked: true,
        identity: {
          githubUserId: 12345,
          githubLogin: "monalisa",
          name: "Mona Lisa",
          avatarUrl: "https://avatars.github.com/u/12345"
        }
      }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("{}", { status: 200 });
  };

  try {
    const res = await apiClient.get("/api/v1/auth/github/status");
    assert.equal(res.linked, true);
    assert.equal(res.identity.githubLogin, "monalisa");
  } finally {
    globalThis.fetch = originalFetch;
    apiClient.setAccessToken(null);
  }
});
