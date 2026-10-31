/**
 * ForgeAI Enterprise Gateway API Client
 * Automatically handles JWT injection, Multi-Tenant X-Org-Id scoping,
 * W3C Trace Correlation headers, and automatic token refresh rotation.
 */

const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL || "http://localhost:8080";

function generateHex(length: number): string {
  const bytes = new Uint8Array(length / 2);
  if (typeof window !== "undefined" && window.crypto) {
    window.crypto.getRandomValues(bytes);
    return Array.from(bytes)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
  }
  // Fallback for SSR
  let result = "";
  for (let i = 0; i < length; i++) {
    result += Math.floor(Math.random() * 16).toString(16);
  }
  return result;
}

function generateCorrelationId(): string {
  if (typeof window !== "undefined" && window.crypto && window.crypto.randomUUID) {
    return window.crypto.randomUUID().replace(/-/g, "");
  }
  return generateHex(32);
}

function generateTraceparent(): string {
  const traceId = generateHex(32);
  const spanId = generateHex(16);
  return `00-${traceId}-${spanId}-01`;
}

interface RequestOptions extends RequestInit {
  orgId?: string;
  skipAuth?: boolean;
  _retry?: boolean;
}

class ApiClient {
  private baseUrl: string;
  private refreshPromise: Promise<string | null> | null = null;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
  }

  public getAccessToken(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem("forgeai_access_token");
  }

  public setAccessToken(token: string | null): void {
    if (typeof window === "undefined") return;
    if (token) {
      localStorage.setItem("forgeai_access_token", token);
    } else {
      localStorage.removeItem("forgeai_access_token");
    }
  }

  public getRefreshToken(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem("forgeai_refresh_token");
  }

  public setRefreshToken(token: string | null): void {
    if (typeof window === "undefined") return;
    if (token) {
      localStorage.setItem("forgeai_refresh_token", token);
    } else {
      localStorage.removeItem("forgeai_refresh_token");
    }
  }

  public getActiveOrgId(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem("forgeai_org_id");
  }

  public setActiveOrgId(orgId: string | null): void {
    if (typeof window === "undefined") return;
    if (orgId) {
      localStorage.setItem("forgeai_org_id", orgId);
    } else {
      localStorage.removeItem("forgeai_org_id");
    }
  }

  public getActiveWorkspaceId(): string | null {
    if (typeof window === "undefined") return null;
    return localStorage.getItem("forgeai_workspace_id");
  }

  public setActiveWorkspaceId(workspaceId: string | null): void {
    if (typeof window === "undefined") return;
    if (workspaceId) {
      localStorage.setItem("forgeai_workspace_id", workspaceId);
    } else {
      localStorage.removeItem("forgeai_workspace_id");
    }
  }

  public getActiveWorkspaceType(): "PERSONAL" | "ORGANIZATION" {
    if (typeof window === "undefined") return "PERSONAL";
    const val = localStorage.getItem("forgeai_workspace_type");
    if (val === "ORGANIZATION") return "ORGANIZATION";
    if (val === "PERSONAL") return "PERSONAL";
    return localStorage.getItem("forgeai_org_id") ? "ORGANIZATION" : "PERSONAL";
  }

  public setActiveWorkspaceType(type: "PERSONAL" | "ORGANIZATION"): void {
    if (typeof window === "undefined") return;
    localStorage.setItem("forgeai_workspace_type", type);
  }


  public async tryRefreshToken(): Promise<string | null> {
    if (this.refreshPromise) {
      return this.refreshPromise;
    }

    const refreshToken = this.getRefreshToken();
    if (!refreshToken) {
      this.clearTokensAndRedirect();
      return null;
    }

    this.refreshPromise = (async () => {
      try {
        const response = await fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Correlation-Id": generateCorrelationId(),
            traceparent: generateTraceparent(),
          },
          body: JSON.stringify({ refreshToken }),
        });

        if (!response.ok) {
          throw new Error(`Token refresh rejected with HTTP ${response.status}`);
        }

        const data = await response.json();
        const newAccessToken = data.tokens?.accessToken || data.accessToken;
        const newRefreshToken = data.tokens?.refreshToken || data.refreshToken;

        if (newAccessToken) {
          this.setAccessToken(newAccessToken);
        }
        if (newRefreshToken) {
          this.setRefreshToken(newRefreshToken);
        }

        return newAccessToken || null;
      } catch (err) {
        console.warn("[ApiClient] Refresh token rotation rejected:", err);
        this.clearTokensAndRedirect();
        return null;
      } finally {
        this.refreshPromise = null;
      }
    })();

    return this.refreshPromise;
  }

  private clearTokensAndRedirect() {
    this.setAccessToken(null);
    this.setRefreshToken(null);
    if (typeof window !== "undefined") {
      const pathname = window.location.pathname;
      if (pathname.startsWith("/login") || pathname.startsWith("/auth/github/callback")) {
        return;
      }
      window.location.href = `/login?expired=true&redirect=${encodeURIComponent(
        pathname
      )}`;
    }
  }

  public async request<T = unknown>(
    endpoint: string,
    options: RequestOptions = {}
  ): Promise<T> {
    const url = endpoint.startsWith("http")
      ? endpoint
      : `${this.baseUrl}${endpoint.startsWith("/") ? "" : "/"}${endpoint}`;

    const headers = new Headers(options.headers || {});

    // Default Content-Type if JSON payload
    if (!headers.has("Content-Type") && options.body && typeof options.body === "string") {
      headers.set("Content-Type", "application/json");
    }

    // 1. Correlation & Traceability Headers
    if (!headers.has("X-Correlation-Id")) {
      headers.set("X-Correlation-Id", generateCorrelationId());
    }
    if (!headers.has("traceparent")) {
      headers.set("traceparent", generateTraceparent());
    }

    // 2. Multi-Tenant Scoping Header
    // Do not attach X-Org-Id to user-scoped Identity routes (/api/v1/organizations, /api/v1/auth, /api/v1/users, etc.)
    const isUserScopedEndpoint =
      endpoint.startsWith("/api/v1/organizations") ||
      endpoint.startsWith("/api/v1/workspaces") ||
      endpoint.startsWith("/api/v1/auth") ||
      endpoint.startsWith("/api/v1/users") ||
      endpoint.startsWith("/.well-known") ||
      endpoint.startsWith("/actuator");

    if (!isUserScopedEndpoint) {
      const orgId = options.orgId || (this.getActiveWorkspaceType() === "ORGANIZATION" ? this.getActiveOrgId() : null);
      const isValidUuid =
        typeof orgId === "string" &&
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(orgId);
      if (isValidUuid && !headers.has("X-Org-Id")) {
        headers.set("X-Org-Id", orgId as string);
      }

      const wsId = this.getActiveWorkspaceId();
      if (wsId && !headers.has("X-Workspace-Id")) {
        const isValidWsUuid =
          typeof wsId === "string" &&
          /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(wsId);
        if (isValidWsUuid) {
          headers.set("X-Workspace-Id", wsId);
          headers.set("X-Workspace-Scope", this.getActiveWorkspaceType());
        }
      }
    }


    // 3. JWT Bearer Token Injection
    if (!options.skipAuth) {
      const token = this.getAccessToken();
      if (token && !headers.has("Authorization")) {
        headers.set("Authorization", `Bearer ${token}`);
      }
    }

    let response: Response;
    try {
      response = await fetch(url, { ...options, headers });
    } catch (networkError: any) {
      console.warn(`Gateway fetch failed for ${url}:`, networkError);
      if (networkError) {
        networkError.isNetworkError = true;
      }
      throw networkError;
    }

    // 4. Automatic 401 Interception & Refresh Rotation (One Retry Guaranteed)
    if (response.status === 401 && !options.skipAuth && !options._retry) {
      const newToken = await this.tryRefreshToken();
      if (newToken) {
        headers.set("Authorization", `Bearer ${newToken}`);
        return this.request<T>(endpoint, { ...options, headers, _retry: true });
      } else {
        const sessionErr = new Error("Session expired. Please log in again.");
        throw sessionErr;
      }
    }

    if (!response.ok) {
      let errorMessage = `HTTP Error ${response.status}: ${response.statusText}`;
      let errorData: any = null;
      try {
        errorData = await response.json();
        errorMessage = errorData.detail || errorData.message || errorData.error || errorMessage;
      } catch {
        // fallback to statusText
      }
      const apiError: any = new Error(errorMessage);
      apiError.status = response.status;
      apiError.statusText = response.statusText;
      apiError.data = errorData;
      throw apiError;
    }

    // Check for empty body / 204
    if (response.status === 204) {
      return {} as T;
    }

    const contentType = response.headers.get("content-type");
    if (contentType && contentType.includes("application/json")) {
      return (await response.json()) as T;
    }

    return (await response.text()) as unknown as T;
  }

  public get<T = unknown>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: "GET" });
  }

  public post<T = unknown>(
    endpoint: string,
    data?: unknown,
    options: RequestOptions = {}
  ): Promise<T> {
    return this.request<T>(endpoint, {
      ...options,
      method: "POST",
      body: typeof data === "string" ? data : (data ? JSON.stringify(data) : undefined),
    });
  }

  public put<T = unknown>(
    endpoint: string,
    data?: unknown,
    options: RequestOptions = {}
  ): Promise<T> {
    return this.request<T>(endpoint, {
      ...options,
      method: "PUT",
      body: typeof data === "string" ? data : (data ? JSON.stringify(data) : undefined),
    });
  }

  public patch<T = unknown>(
    endpoint: string,
    data?: unknown,
    options: RequestOptions = {}
  ): Promise<T> {
    return this.request<T>(endpoint, {
      ...options,
      method: "PATCH",
      body: typeof data === "string" ? data : (data ? JSON.stringify(data) : undefined),
    });
  }

  public delete<T = unknown>(endpoint: string, options: RequestOptions = {}): Promise<T> {
    return this.request<T>(endpoint, { ...options, method: "DELETE" });
  }

  // Unified Traceability & Project Intelligence
  public getProjectTraceability<T = unknown>(projectId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/traceability`);
  }

  public getProjectHealth<T = unknown>(projectId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/traceability/health`);
  }

  public getProjectGaps<T = unknown>(projectId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/traceability/gaps`);
  }

  public syncTraceability<T = unknown>(projectId: string): Promise<T> {
    return this.post<T>(`/api/v1/projects/${projectId}/traceability/sync`);
  }

  public createTraceabilityLink<T = unknown>(projectId: string, link: unknown): Promise<T> {
    return this.post<T>(`/api/v1/projects/${projectId}/traceability/links`, link);
  }

  public deleteTraceabilityLink<T = unknown>(projectId: string, linkId: string): Promise<T> {
    return this.delete<T>(`/api/v1/projects/${projectId}/traceability/links/${linkId}`);
  }

  public listProjectRepositories<T = unknown>(projectId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/repositories`);
  }

  public linkProjectRepository<T = unknown>(projectId: string, repositoryId: string, repositoryName?: string): Promise<T> {
    return this.post<T>(`/api/v1/projects/${projectId}/repositories`, { repositoryId, repositoryName });
  }

  public unlinkProjectRepository<T = unknown>(projectId: string, repositoryId: string): Promise<T> {
    return this.delete<T>(`/api/v1/projects/${projectId}/repositories/${repositoryId}`);
  }

  public createTraceabilitySnapshot<T = unknown>(projectId: string, snapshot: unknown): Promise<T> {
    return this.post<T>(`/api/v1/projects/${projectId}/traceability/snapshots`, snapshot);
  }

  public listTraceabilitySnapshots<T = unknown>(projectId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/traceability/snapshots`);
  }

  public getTraceabilitySnapshot<T = unknown>(projectId: string, snapshotId: string): Promise<T> {
    return this.get<T>(`/api/v1/projects/${projectId}/traceability/snapshots/${snapshotId}`);
  }

  public getRequirementTraceability<T = unknown>(requirementId: string): Promise<T> {
    return this.get<T>(`/api/v1/requirements/${requirementId}/traceability`);
  }

  public getTaskTraceability<T = unknown>(taskId: string): Promise<T> {
    return this.get<T>(`/api/v1/tasks/${taskId}/traceability`);
  }

  public getBaseUrl(): string {
    return this.baseUrl;
  }
}

export const apiClient = new ApiClient(GATEWAY_URL);

