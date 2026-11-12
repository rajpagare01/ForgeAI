"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { User, AuthResponse, GitHubStatusResponse, GitHubIdentityDto } from "@/types";
import { apiClient } from "./api-client";

interface AuthContextType {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  githubStatus: GitHubStatusResponse | null;
  login: (identifier: string, password: string) => Promise<void>;
  register: (fullName: string, email: string, password: string, orgName?: string) => Promise<void>;
  logout: () => void;
  fetchGitHubStatus: () => Promise<GitHubStatusResponse | null>;
  loginWithGitHub: (code: string, state: string, redirectUri?: string) => Promise<void>;
  linkGitHubAccount: (code: string, state: string, redirectUri?: string) => Promise<GitHubIdentityDto>;
  disconnectGitHubAccount: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [githubStatus, setGithubStatus] = useState<GitHubStatusResponse | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const fetchGitHubStatus = useCallback(async (): Promise<GitHubStatusResponse | null> => {
    if (!apiClient.getAccessToken()) {
      setGithubStatus(null);
      return null;
    }
    try {
      const res = await apiClient.get<GitHubStatusResponse>("/api/v1/auth/github/status");
      setGithubStatus(res);
      return res;
    } catch {
      return null;
    }
  }, []);

  // Initialize session on mount
  useEffect(() => {
    try {
      const storedToken = apiClient.getAccessToken();
      const storedUser = localStorage.getItem("forgeai_user");

      if (storedToken && storedUser) {
        setAccessToken(storedToken);
        setUser(JSON.parse(storedUser));
        fetchGitHubStatus();
      } else {
        setAccessToken(null);
        setUser(null);
        setGithubStatus(null);
      }
    } catch (e) {
      console.warn("Auth initialization error:", e);
    } finally {
      setIsLoading(false);
    }
  }, [fetchGitHubStatus]);

  const login = useCallback(async (identifier: string, password: string) => {
    setIsLoading(true);
    try {
      const data = await apiClient.post<any>(
        "/api/v1/auth/login",
        { identifier, password },
        { skipAuth: true }
      );

      const token = data.accessToken || data.tokens?.accessToken;
      const refreshToken = data.refreshToken || data.tokens?.refreshToken;
      const userData = data.user;

      if (token) {
        apiClient.setAccessToken(token);
        setAccessToken(token);
      }
      if (refreshToken) {
        apiClient.setRefreshToken(refreshToken);
      }
      if (userData) {
        const mappedUser: User = {
          id: userData.id,
          email: userData.email,
          fullName: userData.firstName
            ? `${userData.firstName} ${userData.lastName || ""}`.trim()
            : userData.username || identifier,
          username: userData.username,
          roles: userData.roles || ["ROLE_ADMIN"],
        };
        setUser(mappedUser);
        localStorage.setItem("forgeai_user", JSON.stringify(mappedUser));
      }
      fetchGitHubStatus();
    } finally {
      setIsLoading(false);
    }
  }, [fetchGitHubStatus]);

  const loginWithGitHub = useCallback(
    async (code: string, state: string, redirectUri?: string) => {
      setIsLoading(true);
      try {
        const data = await apiClient.post<any>(
          "/api/v1/auth/github/callback",
          { code, state, redirectUri },
          { skipAuth: true }
        );

        const token = data.accessToken || data.tokens?.accessToken;
        const refreshToken = data.refreshToken || data.tokens?.refreshToken;
        const userData = data.user;

        if (token) {
          apiClient.setAccessToken(token);
          setAccessToken(token);
        }
        if (refreshToken) {
          apiClient.setRefreshToken(refreshToken);
        }
        if (userData) {
          const mappedUser: User = {
            id: userData.id,
            email: userData.email,
            fullName: userData.firstName
              ? `${userData.firstName} ${userData.lastName || ""}`.trim()
              : userData.username || userData.email,
            username: userData.username,
            roles: userData.roles || ["ROLE_ADMIN"],
          };
          setUser(mappedUser);
          localStorage.setItem("forgeai_user", JSON.stringify(mappedUser));
        }

        fetchGitHubStatus();
      } finally {
        setIsLoading(false);
      }
    },
    [fetchGitHubStatus]
  );

  const linkGitHubAccount = useCallback(
    async (code: string, state: string, redirectUri?: string): Promise<GitHubIdentityDto> => {
      const identity = await apiClient.post<GitHubIdentityDto>(
        "/api/v1/auth/github/link",
        { code, state, redirectUri }
      );
      setGithubStatus({ linked: true, identity });
      return identity;
    },
    []
  );

  const disconnectGitHubAccount = useCallback(async () => {
    await apiClient.post("/api/v1/auth/github/disconnect", {});
    setGithubStatus({ linked: false, identity: null });
  }, []);

  const register = useCallback(
    async (fullName: string, email: string, password: string, orgName?: string) => {
      setIsLoading(true);
      try {
        const parts = fullName.trim().split(/\s+/);
        const firstName = parts[0] || "Forge";
        const lastName = parts.slice(1).join(" ") || "Engineer";
        const rawUsername = email.split("@")[0].replace(/[^a-zA-Z0-9_-]/g, "_");
        const username =
          rawUsername.length >= 3
            ? rawUsername.substring(0, 50)
            : `${rawUsername}_dev`;

        await apiClient.post<any>(
          "/api/v1/auth/register",
          {
            email,
            username,
            password,
            firstName,
            lastName,
          },
          { skipAuth: true }
        );

        // Immediately authenticate with newly created credentials
        await login(email, password);

        // If organization name was provided during registration, create it
        if (orgName && orgName.trim()) {
          try {
            const orgSlug = orgName.trim().toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "org-" + Date.now();
            await apiClient.post<any>("/api/v1/organizations", {
              name: orgName.trim(),
              slug: orgSlug,
              description: `Organization ${orgName.trim()}`,
            });
          } catch (orgErr) {
            console.error("Failed to automatically create initial organization:", orgErr);
          }
        }
      } finally {
        setIsLoading(false);
      }
    },
    [login]
  );

  const logout = useCallback(() => {
    const refreshToken = apiClient.getRefreshToken();
    if (refreshToken) {
      apiClient
        .post("/api/v1/auth/logout", { refreshToken }, { skipAuth: false })
        .catch((e) => console.warn("Logout notification failed:", e));
    }
    apiClient.setAccessToken(null);
    apiClient.setRefreshToken(null);
    apiClient.clearTenantAndAnalysisCaches();
    localStorage.removeItem("forgeai_user");
    setUser(null);
    setAccessToken(null);
    setGithubStatus(null);
    if (typeof window !== "undefined") {
      window.location.href = "/login";
    }
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        accessToken,
        isAuthenticated: !!accessToken,
        isLoading,
        githubStatus,
        login,
        register,
        logout,
        fetchGitHubStatus,
        loginWithGitHub,
        linkGitHubAccount,
        disconnectGitHubAccount,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
};
