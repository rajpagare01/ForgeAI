"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { Organization } from "@/types";
import { apiClient } from "./api-client";
import { useAuth } from "./auth-context";

interface TenantContextType {
  currentOrg: Organization;
  organizations: Organization[];
  isLoading: boolean;
  switchOrganization: (orgId: string) => void;
  addOrganization: (name: string, description?: string) => Promise<void>;
  refreshOrganizations: () => Promise<void>;
}

const FALLBACK_DEFAULT_ORG: Organization = {
  id: "cc28c8a3-2224-4bad-9ab2-ea36ede52577",
  name: "ForgeAI Core Engineering",
  slug: "forgeai-core",
  role: "ADMIN",
  projectsCount: 0,
};

const TenantContext = createContext<TenantContextType | undefined>(undefined);

export const TenantProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  const [organizations, setOrganizations] = useState<Organization[]>([FALLBACK_DEFAULT_ORG]);
  const [currentOrgId, setCurrentOrgId] = useState<string>(FALLBACK_DEFAULT_ORG.id);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const fetchOrganizations = useCallback(async () => {
    if (!apiClient.getAccessToken()) {
      setIsLoading(false);
      return;
    }

    try {
      const data = await apiClient.get<any[]>("/api/v1/organizations");
      if (Array.isArray(data) && data.length > 0) {
        const mappedOrgs: Organization[] = data.map((item) => ({
          id: item.id,
          name: item.name,
          slug: item.slug,
          role: "ADMIN",
          projectsCount: item.projectsCount ?? 0,
        }));
        setOrganizations(mappedOrgs);

        const storedOrgId = apiClient.getActiveOrgId();
        const found = mappedOrgs.find((o) => o.id === storedOrgId);
        const activeId = found ? found.id : mappedOrgs[0].id;
        setCurrentOrgId(activeId);
        apiClient.setActiveOrgId(activeId);
      } else if (Array.isArray(data) && data.length === 0) {
        // Automatically create initial organization for brand new user
        try {
          const newOrg = await apiClient.post<any>("/api/v1/organizations", {
            name: "ForgeAI Core",
            slug: "forgeai-core",
            description: "Default engineering workspace",
          });
          const mapped: Organization = {
            id: newOrg.id,
            name: newOrg.name,
            slug: newOrg.slug,
            role: "ADMIN",
            projectsCount: 0,
          };
          setOrganizations([mapped]);
          setCurrentOrgId(mapped.id);
          apiClient.setActiveOrgId(mapped.id);
        } catch (e) {
          console.warn("Failed to create initial org:", e);
        }
      }
    } catch (err) {
      console.warn("Failed to load organizations from gateway:", err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchOrganizations();
  }, [isAuthenticated, fetchOrganizations]);

  const switchOrganization = useCallback((orgId: string) => {
    setCurrentOrgId(orgId);
    apiClient.setActiveOrgId(orgId);
  }, []);

  const addOrganization = useCallback(
    async (name: string, description?: string) => {
      let slug = name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-|-$/g, "");
      if (slug.length < 3) slug = `${slug}-org`;

      try {
        const created = await apiClient.post<any>("/api/v1/organizations", {
          name,
          slug,
          description: description || "Managed engineering workspace",
        });

        const newOrg: Organization = {
          id: created.id,
          name: created.name,
          slug: created.slug,
          role: "ADMIN",
          projectsCount: 0,
        };

        setOrganizations((prev) => [...prev, newOrg]);
        switchOrganization(newOrg.id);
      } catch (err) {
        console.error("Failed to create organization via API:", err);
        throw err;
      }
    },
    [switchOrganization]
  );

  const currentOrg =
    organizations.find((o) => o.id === currentOrgId) || organizations[0] || FALLBACK_DEFAULT_ORG;

  return (
    <TenantContext.Provider
      value={{
        currentOrg,
        organizations,
        isLoading,
        switchOrganization,
        addOrganization,
        refreshOrganizations: fetchOrganizations,
      }}
    >
      {children}
    </TenantContext.Provider>
  );
};

export const useTenant = (): TenantContextType => {
  const context = useContext(TenantContext);
  if (!context) {
    throw new Error("useTenant must be used within a TenantProvider");
  }
  return context;
};
