"use client";

import React, { createContext, useContext, useEffect, useState, useCallback } from "react";
import { Organization, Workspace } from "@/types";
import { apiClient } from "./api-client";
import { useAuth } from "./auth-context";

export interface TenantContextType {
  // Workspace abstraction
  activeWorkspace: Workspace | null;
  workspaces: Workspace[];
  isPersonalWorkspace: boolean;
  switchWorkspace: (workspaceId: string) => void;
  switchToPersonalWorkspace: () => void;
  refreshWorkspaces: () => Promise<void>;

  // Organization backwards compatibility
  currentOrg: Organization | null;
  organizations: Organization[];
  isLoading: boolean;
  switchOrganization: (orgId: string) => void;
  addOrganization: (name: string, description?: string) => Promise<void>;
  refreshOrganizations: () => Promise<void>;
}

const TenantContext = createContext<TenantContextType | undefined>(undefined);

export const TenantProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated, user } = useAuth();
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [activeWorkspace, setActiveWorkspace] = useState<Workspace | null>(null);
  const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [currentOrgId, setCurrentOrgId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  const fetchWorkspacesAndOrgs = useCallback(async (): Promise<{ workspaces: Workspace[]; orgs: Organization[] }> => {
    if (!apiClient.getAccessToken()) {
      setWorkspaces([]);
      setActiveWorkspace(null);
      setOrganizations([]);
      setCurrentOrgId(null);
      setIsLoading(false);
      return { workspaces: [], orgs: [] };
    }

    try {
      setIsLoading(true);

      // 1. Fetch authoritative workspaces for authenticated user
      let loadedWorkspaces: Workspace[] = [];
      try {
        const wsData = await apiClient.get<Workspace[]>("/api/v1/workspaces");
        if (Array.isArray(wsData) && wsData.length > 0) {
          loadedWorkspaces = wsData;
        } else {
          // Reconcile personal workspace from backend
          const personalWs = await apiClient.get<Workspace>("/api/v1/workspaces/personal");
          if (personalWs && personalWs.id) {
            loadedWorkspaces = [personalWs];
          }
        }
      } catch (wsErr) {
        console.warn("Failed to load /api/v1/workspaces, checking /api/v1/workspaces/personal:", wsErr);
        try {
          const personalWs = await apiClient.get<Workspace>("/api/v1/workspaces/personal");
          if (personalWs && personalWs.id) {
            loadedWorkspaces = [personalWs];
          }
        } catch (persErr) {
          console.warn("Could not load personal workspace from backend:", persErr);
          loadedWorkspaces = [];
        }
      }

      setWorkspaces(loadedWorkspaces);

      // 2. Fetch authoritative organizations
      let loadedOrgs: Organization[] = [];
      try {
        const orgData = await apiClient.get<any[]>("/api/v1/organizations");
        if (Array.isArray(orgData)) {
          loadedOrgs = orgData.map((item) => ({
            id: item.id,
            name: item.name,
            slug: item.slug,
            role: item.role || "ADMIN",
            projectsCount: item.projectsCount ?? 0,
          }));
        }
      } catch (orgErr) {
        console.warn("Failed to load organizations:", orgErr);
        loadedOrgs = [];
      }
      setOrganizations(loadedOrgs);

      // 3. Determine active workspace
      const storedWsId = apiClient.getActiveWorkspaceId();

      let selectedWs: Workspace | null = null;
      if (storedWsId) {
        selectedWs = loadedWorkspaces.find((w) => w.id === storedWsId) || null;
      }
      if (!selectedWs && loadedWorkspaces.length > 0) {
        // Default to personal workspace or first loaded workspace
        selectedWs = loadedWorkspaces.find((w) => w.type === "PERSONAL") || loadedWorkspaces[0];
      }

      if (selectedWs) {
        setActiveWorkspace(selectedWs);
        apiClient.setActiveWorkspaceId(selectedWs.id);
        apiClient.setActiveWorkspaceType(selectedWs.type);

        if (selectedWs.type === "ORGANIZATION" && selectedWs.organizationId) {
          setCurrentOrgId(selectedWs.organizationId);
          apiClient.setActiveOrgId(selectedWs.organizationId);
        } else {
          // In personal workspace, clear active org header so headers don't leak
          setCurrentOrgId(null);
          apiClient.setActiveOrgId(null);
        }
      } else {
        if (loadedOrgs.length > 0) {
          const org = loadedOrgs[0];
          const syntheticOrgWs: Workspace = {
            id: org.id,
            type: "ORGANIZATION",
            name: org.name,
            organizationId: org.id,
            ownerUserId: user?.id || "",
          };
          setActiveWorkspace(syntheticOrgWs);
          apiClient.setActiveWorkspaceId(org.id);
          apiClient.setActiveWorkspaceType("ORGANIZATION");
          setCurrentOrgId(org.id);
          apiClient.setActiveOrgId(org.id);
        } else {
          setActiveWorkspace(null);
          apiClient.setActiveWorkspaceId(null);
          setCurrentOrgId(null);
          apiClient.setActiveOrgId(null);
        }
      }
      return { workspaces: loadedWorkspaces, orgs: loadedOrgs };
    } catch (err) {
      console.warn("Error initializing tenant context:", err);
      return { workspaces: [], orgs: [] };
    } finally {
      setIsLoading(false);
    }
  }, [user]);

  useEffect(() => {
    fetchWorkspacesAndOrgs();
  }, [isAuthenticated, fetchWorkspacesAndOrgs]);

  const switchWorkspace = useCallback(
    (workspaceId: string) => {
      const target = workspaces.find((w) => w.id === workspaceId);
      if (!target) return;

      setActiveWorkspace(target);
      apiClient.setActiveWorkspaceId(target.id);
      apiClient.setActiveWorkspaceType(target.type);

      if (target.type === "ORGANIZATION" && target.organizationId) {
        setCurrentOrgId(target.organizationId);
        apiClient.setActiveOrgId(target.organizationId);
      } else {
        setCurrentOrgId(null);
        apiClient.setActiveOrgId(null);
      }
    },
    [workspaces]
  );

  const switchToPersonalWorkspace = useCallback(() => {
    const personal = workspaces.find((w) => w.type === "PERSONAL");
    if (personal) {
      switchWorkspace(personal.id);
    }
  }, [workspaces, switchWorkspace]);

  const switchOrganization = useCallback(
    (orgId: string, overrideWorkspaces?: Workspace[], overrideOrgs?: Organization[]) => {
      const wsSource = overrideWorkspaces || workspaces;
      const orgSource = overrideOrgs || organizations;
      const orgWs = wsSource.find((w) => w.type === "ORGANIZATION" && w.organizationId === orgId);
      if (orgWs) {
        setActiveWorkspace(orgWs);
        apiClient.setActiveWorkspaceId(orgWs.id);
        apiClient.setActiveWorkspaceType(orgWs.type);
        setCurrentOrgId(orgId);
        apiClient.setActiveOrgId(orgId);
      } else {
        const matchedOrg = orgSource.find((o) => o.id === orgId);
        const syntheticOrgWs: Workspace = {
          id: orgId,
          type: "ORGANIZATION",
          name: matchedOrg?.name || "Organization Workspace",
          organizationId: orgId,
          ownerUserId: user?.id || "",
        };
        setActiveWorkspace(syntheticOrgWs);
        apiClient.setActiveWorkspaceId(orgId);
        apiClient.setActiveWorkspaceType("ORGANIZATION");
        setCurrentOrgId(orgId);
        apiClient.setActiveOrgId(orgId);
      }
    },
    [workspaces, organizations]
  );

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

        // Refresh workspaces so the newly created organization workspace is present
        const { workspaces: loadedWs, orgs: loadedOrgs } = await fetchWorkspacesAndOrgs();
        const mergedOrgs = loadedOrgs.some((o) => o.id === newOrg.id)
          ? loadedOrgs
          : [...loadedOrgs, newOrg];
        setOrganizations(mergedOrgs);
        switchOrganization(newOrg.id, loadedWs, mergedOrgs);
      } catch (err) {
        console.error("Failed to create organization via API:", err);
        throw err;
      }
    },
    [fetchWorkspacesAndOrgs, switchOrganization]
  );

  const isPersonalWorkspace = activeWorkspace ? activeWorkspace.type === "PERSONAL" : true;

  const currentOrg: Organization | null = isPersonalWorkspace
    ? null
    : organizations.find((o) => o.id === currentOrgId) || organizations[0] || null;

  return (
    <TenantContext.Provider
      value={{
        activeWorkspace,
        workspaces,
        isPersonalWorkspace,
        switchWorkspace,
        switchToPersonalWorkspace,
        refreshWorkspaces: async () => {
          await fetchWorkspacesAndOrgs();
        },
        currentOrg,
        organizations,
        isLoading,
        switchOrganization,
        addOrganization,
        refreshOrganizations: async () => {
          await fetchWorkspacesAndOrgs();
        },
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
