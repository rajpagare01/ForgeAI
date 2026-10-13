"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { User, AuthResponse } from "@/types";
import { apiClient } from "./api-client";

interface AuthContextType {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (identifier: string, password: string) => Promise<void>;
  register: (fullName: string, email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  // Initialize session on mount
  useEffect(() => {
    try {
      const storedToken = apiClient.getAccessToken();
      const storedUser = localStorage.getItem("forgeai_user");

      if (storedToken && storedUser) {
        setAccessToken(storedToken);
        setUser(JSON.parse(storedUser));
      } else {
        setAccessToken(null);
        setUser(null);
      }
    } catch (e) {
      console.warn("Auth initialization error:", e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  const login = useCallback(async (identifier: string, password: string) => {
    setIsLoading(true);
    try {
      let data: any;
      try {
        data = await apiClient.post<any>(
          "/api/v1/auth/login",
          { identifier, password },
          { skipAuth: true }
        );
      } catch (loginError: any) {
        // If demo user doesn't exist yet, attempt automatic registration then retry login
        if (identifier === "admin@forgeai.dev" || identifier === "admin_dev") {
          try {
            await apiClient.post<any>(
              "/api/v1/auth/register",
              {
                email: "admin@forgeai.dev",
                username: "admin_dev",
                password: "password123",
                firstName: "Forge",
                lastName: "Admin",
              },
              { skipAuth: true }
            );
            data = await apiClient.post<any>(
              "/api/v1/auth/login",
              { identifier: "admin@forgeai.dev", password: "password123" },
              { skipAuth: true }
            );
          } catch (regError) {
            console.error("Auto-registration for demo user failed:", regError);
            throw loginError;
          }
        } else {
          throw loginError;
        }
      }

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
          roles: userData.roles || ["ROLE_ADMIN"],
        };
        setUser(mappedUser);
        localStorage.setItem("forgeai_user", JSON.stringify(mappedUser));
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  const register = useCallback(
    async (fullName: string, email: string, password: string) => {
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
    localStorage.removeItem("forgeai_user");
    setUser(null);
    setAccessToken(null);
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
        login,
        register,
        logout,
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
