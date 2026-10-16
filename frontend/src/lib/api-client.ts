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
}

class ApiClient {
  private baseUrl: string;
  private isRefreshing = false;
  private refreshSubscribers: Array<(token: string) => void> = [];

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
    return localStorage.getItem("forgeai_org_id") || "org-forgeai-core";
  }

  public setActiveOrgId(orgId: string): void {
    if (typeof window === "undefined") return;
    localStorage.setItem("forgeai_org_id", orgId);
  }

  private onTokenRefreshed(token: string) {
    this.refreshSubscribers.forEach((callback) => callback(token));
    this.refreshSubscribers = [];
  }

  private addRefreshSubscriber(callback: (token: string) => void) {
    this.refreshSubscribers.push(callback);
  }

  private async tryRefreshToken(): Promise<string | null> {
    const refreshToken = this.getRefreshToken();
    if (!refreshToken) {
      this.clearTokensAndRedirect();
      return null;
    }

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
        throw new Error("Token refresh rejected");
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

      return newAccessToken;
    } catch {
      this.clearTokensAndRedirect();
      return null;
    }
  }

  private clearTokensAndRedirect() {
    this.setAccessToken(null);
    this.setRefreshToken(null);
    if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
      window.location.href = `/login?expired=true&redirect=${encodeURIComponent(
        window.location.pathname
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
    const orgId = options.orgId || this.getActiveOrgId();
    if (orgId && !headers.has("X-Org-Id")) {
      headers.set("X-Org-Id", orgId);
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
    } catch (networkError) {
      console.warn(`Gateway fetch failed for ${url}:`, networkError);
      throw networkError;
    }

    // 4. Automatic 401 Interception & Refresh Rotation
    if (response.status === 401 && !options.skipAuth) {
      if (!this.isRefreshing) {
        this.isRefreshing = true;
        const newToken = await this.tryRefreshToken();
        this.isRefreshing = false;

        if (newToken) {
          this.onTokenRefreshed(newToken);
          headers.set("Authorization", `Bearer ${newToken}`);
          return this.request<T>(endpoint, { ...options, headers });
        } else {
          throw new Error("Session expired. Please log in again.");
        }
      } else {
        // Queue concurrent requests until refresh completes
        return new Promise<T>((resolve, reject) => {
          this.addRefreshSubscriber(async (newToken: string) => {
            try {
              headers.set("Authorization", `Bearer ${newToken}`);
              const retryResponse = await this.request<T>(endpoint, {
                ...options,
                headers,
              });
              resolve(retryResponse);
            } catch (err) {
              reject(err);
            }
          });
        });
      }
    }

    if (!response.ok) {
      let errorMessage = `HTTP Error ${response.status}: ${response.statusText}`;
      try {
        const errorJson = await response.json();
        errorMessage = errorJson.message || errorJson.error || errorMessage;
      } catch {
        // fallback to statusText
      }
      throw new Error(errorMessage);
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
      body: data ? JSON.stringify(data) : undefined,
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
      body: data ? JSON.stringify(data) : undefined,
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
}

export const apiClient = new ApiClient(GATEWAY_URL);

