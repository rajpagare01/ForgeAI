"use client";

import React, { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import {
  Repository,
  GitHubHealth,
  AvailableRepository,
  GitHubConnectionResponse,
  GitHubConnectionState,
} from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient, formatUserError } from "@/lib/api-client";
import {
  GitPullRequest,
  CheckCircle2,
  RefreshCw,
  ExternalLink,
  ShieldCheck,
  ShieldAlert,
  GitBranch,
  Lock,
  Globe,
  Radio,
  Plus,
  Loader2,
  AlertCircle,
  AlertTriangle,
  Check,
  CheckSquare,
  Square,
  Trash2,
} from "lucide-react";

const GitHubIcon: React.FC<{ className?: string }> = ({ className = "h-4 w-4" }) => (
  <svg className={`${className} fill-current`} viewBox="0 0 24 24">
    <path
      fillRule="evenodd"
      clipRule="evenodd"
      d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"
    />
  </svg>
);

import { Card, CardContent } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Dialog,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogClose,
  DialogFooter,
} from "@/components/ui/dialog";
import { formatDate } from "@/lib/utils";

export default function RepositoriesPage() {
  const { currentOrg, isLoading: isTenantLoading, activeWorkspace, isPersonalWorkspace } = useTenant();
  
  // Core unified state
  const [connection, setConnection] = useState<GitHubConnectionResponse | null>(null);
  const [connectionState, setConnectionState] = useState<GitHubConnectionState>("CHECKING_APP_INSTALLATION");
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [availableRepos, setAvailableRepos] = useState<AvailableRepository[]>([]);
  const [githubHealth, setGithubHealth] = useState<GitHubHealth | null>(null);

  // Operation flags
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingAvailable, setIsLoadingAvailable] = useState(false);
  const [isConnecting, setIsConnecting] = useState(false);
  const [isReconciling, setIsReconciling] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [importingRepoName, setImportingRepoName] = useState<string | null>(null);
  const [disconnectingRepoId, setDisconnectingRepoId] = useState<string | null>(null);
  const [syncingRepoId, setSyncingRepoId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Modal & Selection state
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [selectedRepoNames, setSelectedRepoNames] = useState<string[]>([]);

  const workspaceDisplayName = isPersonalWorkspace
    ? "Personal Workspace"
    : currentOrg?.name || "Organization Workspace";

  // Fetch unified connection status and repositories
  const fetchData = useCallback(async () => {
    if (isTenantLoading) return;
    if (!activeWorkspace?.id) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setError(null);

    try {
      // 1. Fetch GitHub Health diagnostic
      try {
        const health = await apiClient.get<GitHubHealth>("/api/v1/github/health");
        setGithubHealth(health);
      } catch (e: any) {
        setGithubHealth({
          configured: false,
          status: "UNREACHABLE",
          httpStatus: e?.status || undefined,
          message: e?.message || "GitHub health endpoint unreachable",
        });
      }

      // 2. Fetch unified GitHub connection orchestration endpoint
      const conn = await apiClient.get<GitHubConnectionResponse>(
        `/api/v1/github/connection?workspaceId=${activeWorkspace.id}`
      );
      setConnection(conn);
      if (conn?.connectionState) {
        setConnectionState(conn.connectionState);
      }

      // 3. Fetch connected repositories
      const repoList = await apiClient.get<any[]>(
        `/api/v1/repositories?workspaceId=${activeWorkspace.id}`
      );
      if (Array.isArray(repoList)) {
        const mapped: Repository[] = repoList.map((r) => ({
          id: r.id,
          organizationId: r.organizationId,
          workspaceId: r.workspaceId,
          scope: r.scope,
          name: r.name,
          fullName: r.fullName,
          defaultBranch: r.defaultBranch || "Unknown",
          isPrivate: r.isPrivate ?? false,
          webhookInstalled: r.webhookInstalled ?? true,
          openPullRequestsCount: r.openPullRequestsCount ?? 0,
          lastSyncAt: r.lastSyncedAt || r.updatedAt || "",
          syncStatus: r.syncStatus || "SYNCED",
          latestObservedSha: r.latestObservedSha || null,
          latestIndexedSha: r.latestIndexedSha || null,
          lastError: r.lastError || null,
        }));
        setRepositories(mapped);
      } else {
        setRepositories([]);
      }

      // 4. If GitHub App is active, fetch accessible repositories live from GitHub
      if (conn?.installationStatus === "ACTIVE") {
        try {
          const avail = await apiClient.get<AvailableRepository[]>(
            `/api/v1/repositories/available?workspaceId=${activeWorkspace.id}`
          );
          setAvailableRepos(Array.isArray(avail) ? avail : []);
        } catch {
          setAvailableRepos([]);
        }
      } else {
        setAvailableRepos([]);
      }
    } catch (err: any) {
      console.warn("Failed to load GitHub connection or repositories:", err);
      const status = err?.status;
      if (status === 401 || status === 403) {
        setConnectionState("INSTALLATION_NOT_AUTHORIZED");
        setError("Authorization error: Access denied to repositories for this workspace.");
      } else if (status === 503 || err?.isNetworkError) {
        setConnectionState("GITHUB_UNAVAILABLE");
        setError("Service unavailable: Unable to connect to GitHub service.");
      } else {
        setError(formatUserError(err, "Failed to load GitHub connection."));
      }
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace?.id, isTenantLoading]);

  // Initial load
  useEffect(() => {
    if (!isTenantLoading) {
      fetchData();
    }
  }, [fetchData, isTenantLoading]);

  // Auto-detect GitHub App Setup Callback from URL
  useEffect(() => {
    if (typeof window === "undefined" || !activeWorkspace?.id) return;
    const params = new URLSearchParams(window.location.search);
    const installationIdStr = params.get("installation_id");
    const stateParam = params.get("state");
    const installedParam = params.get("installed");
    const errorParam = params.get("error");

    if (errorParam) {
      setError(decodeURIComponent(errorParam));
      setConnectionState("INSTALLATION_FAILED");
      window.history.replaceState({}, document.title, window.location.pathname);
      return;
    }

    if (installedParam === "true") {
      setConnectionState("REPOSITORIES_READY");
      setSuccess("GitHub App connected and verified successfully!");
      window.history.replaceState({}, document.title, window.location.pathname);
      fetchData();
      return;
    }

    if (installationIdStr) {
      const parsedId = parseInt(installationIdStr, 10);
      if (!isNaN(parsedId) && parsedId > 0) {
        setConnectionState("VERIFYING_INSTALLATION");
        setIsConnecting(true);
        setError(null);

        window.history.replaceState({}, document.title, window.location.pathname);

        const callbackEndpoint = stateParam ? "/api/v1/github/installation-callback" : "/api/v1/github/connect";
        const callbackPayload = stateParam
          ? { workspaceId: activeWorkspace.id, installationId: parsedId, state: stateParam }
          : { workspaceId: activeWorkspace.id, installationId: parsedId };

        apiClient
          .post<GitHubConnectionResponse>(callbackEndpoint, callbackPayload)
          .then((updatedConn) => {
            setConnection(updatedConn);
            setConnectionState("REPOSITORIES_READY");
            setSuccess("GitHub App connected and verified successfully!");
            fetchData();
          })
          .catch((err: any) => {
            console.error("Installation callback verification failed:", err);
            setConnectionState("INSTALLATION_FAILED");
            setError(formatUserError(err, "Failed to verify GitHub App installation."));
          })
          .finally(() => {
            setIsConnecting(false);
          });
      }
    }
  }, [activeWorkspace?.id, fetchData]);

  // Single primary user action
  const handlePrimaryAction = async () => {
    if (!activeWorkspace?.id) return;
    setError(null);
    setSuccess(null);

    if (!connection?.githubUserConnected) {
      setConnectionState("AUTHENTICATING_GITHUB");
      setIsConnecting(true);
      try {
        const callbackUri = `${window.location.origin}/auth/github/callback`;
        const res = await apiClient.get<{ authorizationUrl: string; state: string }>(
          `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`
        );
        if (res?.authorizationUrl) {
          if (typeof window !== "undefined") {
            sessionStorage.setItem("forgeai_github_oauth_action", "link");
          }
          window.location.href = res.authorizationUrl;
        } else {
          setConnectionState("AUTHENTICATION_FAILED");
          setError("Unable to initiate GitHub OAuth session.");
          setIsConnecting(false);
        }
      } catch (err: any) {
        setConnectionState("AUTHENTICATION_FAILED");
        setError(formatUserError(err, "GitHub OAuth service is currently unavailable."));
        setIsConnecting(false);
      }
      return;
    }

    if (connection?.installationRequired || connection?.installationStatus !== "ACTIVE") {
      setConnectionState("GITHUB_APP_INSTALLING");
      setIsConnecting(true);
      try {
        const startRes = await apiClient.post<{ state: string; installUrl: string }>(
          "/api/v1/github/installation-start",
          { workspaceId: activeWorkspace.id }
        );
        if (startRes?.installUrl) {
          window.location.href = startRes.installUrl;
          return;
        }
      } catch (startErr: any) {
        console.warn("Failed to generate dynamic installation state URL, using static fallback:", startErr);
      }

      const fallbackInstallUrl = connection?.appInstallUrl;
      if (fallbackInstallUrl) {
        window.location.href = fallbackInstallUrl;
        return;
      }
      setError("GitHub App installation URL is not configured. Please verify GitHub App configuration.");
      setIsConnecting(false);
      setConnectionState("DISCONNECTED");
      return;
    }

    handleOpenConnectModal();
  };

  // Reconcile repositories live from GitHub
  const handleReconcile = async () => {
    if (!activeWorkspace?.id) return;
    setIsReconciling(true);
    setError(null);
    setSuccess(null);

    try {
      const updatedConn = await apiClient.post<GitHubConnectionResponse>("/api/v1/github/reconcile", {
        workspaceId: activeWorkspace.id,
      });
      setConnection(updatedConn);
      setSuccess("Repository access reconciled and synchronized with GitHub.");
      await fetchData();
    } catch (err: any) {
      console.error("Reconciliation failed:", err);
      setError(err?.message || "Failed to reconcile repository access.");
    } finally {
      setIsReconciling(false);
    }
  };

  // Open multi-select repository import dialog
  const handleOpenConnectModal = async () => {
    setShowConnectModal(true);
    setSelectedRepoNames([]);
    setError(null);

    if (activeWorkspace?.id) {
      setIsLoadingAvailable(true);
      try {
        const repos = await apiClient.get<AvailableRepository[]>(
          `/api/v1/repositories/available?workspaceId=${activeWorkspace.id}`
        );
        setAvailableRepos(Array.isArray(repos) ? repos : []);
      } catch (e: any) {
        console.warn("Failed to fetch available repositories from GitHub:", e);
        setAvailableRepos([]);
      } finally {
        setIsLoadingAvailable(false);
      }
    }
  };

  const existingRepoFullNames = new Set(repositories.map((r) => r.fullName.toLowerCase()));
  const unimportedAvailable = availableRepos.filter(
    (r) => !existingRepoFullNames.has(r.fullName.toLowerCase())
  );

  const toggleSelectAll = () => {
    if (selectedRepoNames.length === unimportedAvailable.length) {
      setSelectedRepoNames([]);
    } else {
      setSelectedRepoNames(unimportedAvailable.map((r) => r.fullName));
    }
  };

  const toggleRepoSelection = (fullName: string) => {
    setSelectedRepoNames((prev) =>
      prev.includes(fullName) ? prev.filter((n) => n !== fullName) : [...prev, fullName]
    );
  };

  // Batch import selected repositories
  const handleBatchImport = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeWorkspace?.id) return;

    if (selectedRepoNames.length === 0) {
      setError("Please select at least one repository to import.");
      return;
    }

    setIsImporting(true);
    setError(null);
    setSuccess(null);

    try {
      const res = await apiClient.post<any[]>("/api/v1/repositories/batch-connect", {
        repositoryNames: selectedRepoNames,
        workspaceId: activeWorkspace.id,
      });

      setShowConnectModal(false);
      setSelectedRepoNames([]);
      setSuccess(
        `Successfully imported and synchronized ${Array.isArray(res) ? res.length : selectedRepoNames.length} repository/repositories.`
      );
      await fetchData();
    } catch (err: any) {
      console.error("Batch repository import failed:", err);
      setError(err?.message || "Failed to import selected repositories.");
    } finally {
      setIsImporting(false);
    }
  };

  // Single-repo connect
  const handleConnectSingle = async (fullName: string) => {
    if (!activeWorkspace?.id || !fullName) return;
    setImportingRepoName(fullName);
    setIsImporting(true);
    setError(null);
    setSuccess(null);
    try {
      await apiClient.post("/api/v1/repositories/connect", {
        fullName,
        workspaceId: activeWorkspace.id,
      });
      setSuccess(`Repository '${fullName}' connected and synchronized.`);
      await fetchData();
    } catch (err: any) {
      console.error("Single connect failed:", err);
      const msg = err?.message || "";
      if (msg.includes("not accessible to installation") || msg.includes("Access denied")) {
        setError(`ForgeAI GitHub App does not have permission for '${fullName}' yet. Please grant repository access on GitHub using 'Configure on GitHub' above.`);
      } else {
        setError(msg || `Failed to connect repository '${fullName}'.`);
      }
    } finally {
      setImportingRepoName(null);
      setIsImporting(false);
    }
  };

  // Disconnect repository from workspace
  const handleDisconnectRepository = async (repoId: string, fullName: string) => {
    if (!activeWorkspace?.id) return;
    setError(null);
    setSuccess(null);
    setDisconnectingRepoId(repoId);
    try {
      await apiClient.delete(`/api/v1/repositories/${repoId}?workspaceId=${activeWorkspace.id}`);
      setSuccess(`Repository '${fullName}' disconnected successfully.`);
      await fetchData();
    } catch (err: any) {
      console.error("Disconnect repository failed:", err);
      setError(formatUserError(err, `Failed to disconnect repository '${fullName}'.`));
    } finally {
      setDisconnectingRepoId(null);
    }
  };

  // Sync individual repository
  const handleSyncRepository = async (repoId: string, fullName: string) => {
    if (!activeWorkspace?.id) return;
    setError(null);
    setSuccess(null);
    setSyncingRepoId(repoId);
    try {
      await apiClient.post(`/api/v1/repositories/${repoId}/reconcile?workspaceId=${activeWorkspace.id}`);
      setSuccess(`Repository '${fullName}' synchronized successfully.`);
      await fetchData();
    } catch (err: any) {
      console.error("Sync repository failed:", err);
      setError(formatUserError(err, `Failed to synchronize repository '${fullName}'.`));
    } finally {
      setSyncingRepoId(null);
    }
  };

  // Render status badge based on syncStatus
  const renderSyncStatusBadge = (repo: Repository) => {
    const status = (repo.syncStatus || "SYNCED").toUpperCase();
    if (status === "SYNCED") {
      return (
        <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900 bg-emerald-950/20 gap-1 py-0.5">
          <CheckCircle2 className="h-3 w-3" />
          Synchronized
        </Badge>
      );
    }
    if (status === "SYNCING") {
      return (
        <Badge variant="outline" className="font-mono text-[10px] text-sky-400 border-sky-900 bg-sky-950/20 gap-1 py-0.5">
          <Loader2 className="h-3 w-3 animate-spin" />
          Syncing
        </Badge>
      );
    }
    if (status === "STALE") {
      return (
        <Badge variant="outline" className="font-mono text-[10px] text-amber-400 border-amber-900 bg-amber-950/20 gap-1 py-0.5">
          <AlertCircle className="h-3 w-3" />
          Stale
        </Badge>
      );
    }
    return (
      <Badge
        variant="outline"
        className="font-mono text-[10px] text-rose-400 border-rose-900 bg-rose-950/20 gap-1 py-0.5 cursor-help"
        title={repo.lastError || "Repository synchronization failed"}
      >
        <AlertTriangle className="h-3 w-3" />
        {status}
      </Badge>
    );
  };

  // Header primary button text based on connection state
  const getPrimaryButtonText = () => {
    if (isConnecting) return "Connecting...";
    if (!connection?.githubUserConnected) return "Connect GitHub";
    if (connection?.installationRequired || connection?.installationStatus !== "ACTIVE") {
      return "Continue GitHub Setup";
    }
    return "Add Repositories";
  };

  const shouldShowLoading = isLoading || isTenantLoading;
  const shouldShowError = !shouldShowLoading && error !== null;
  const shouldShowEmpty = !shouldShowLoading && !shouldShowError && repositories.length === 0;
  const shouldShowTable = !shouldShowLoading && !shouldShowError && repositories.length > 0;

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <Radio className="h-5 w-5 text-zinc-300" />
              <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
                Connected Source Control Repositories
              </h1>
            </div>
            <p className="text-xs text-zinc-400">
              Automatic GitHub App discovery, cryptographic webhook verification, and workspace traceability for{" "}
              <span className="text-zinc-200 font-medium">{workspaceDisplayName}</span>.
            </p>
          </div>

          <div className="flex items-center gap-2.5">
            <Button
              variant="outline"
              size="sm"
              onClick={fetchData}
              disabled={isLoading || isReconciling}
              className="h-8 text-xs gap-1.5 border-[#26262C] bg-[#141418] text-zinc-300"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
              Sync Now
            </Button>

            <Button
              size="sm"
              onClick={handlePrimaryAction}
              disabled={isConnecting || isLoading}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              {isConnecting ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : connection?.installationStatus === "ACTIVE" ? (
                <Plus className="h-3.5 w-3.5" />
              ) : (
                <GitHubIcon className="h-3.5 w-3.5" />
              )}
              {getPrimaryButtonText()}
            </Button>
          </div>
        </div>
      </div>

      {/* Global Alerts */}
      {error && (
        <div className="flex items-center justify-between gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300">
          <div className="flex items-center gap-2">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={fetchData}
            className="text-xs text-rose-300 hover:text-white h-7 px-2"
          >
            Retry
          </Button>
        </div>
      )}

      {success && (
        <div className="flex items-center gap-2 rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3 text-xs text-emerald-300">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
          <span>{success}</span>
        </div>
      )}

      {/* Unified Connection Status Banner */}
      {!connection?.githubUserConnected ? (
        /* State 1: Not connected */
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11]">
          <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-[#141418] border border-[#26262C] text-zinc-300">
                <GitHubIcon className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-zinc-100">GitHub</h4>
                  <Badge variant="outline" className="font-mono text-[10px] text-zinc-400 border-[#26262C]">
                    Not connected
                  </Badge>
                </div>
                <p className="text-xs text-zinc-400 mt-0.5 max-w-xl leading-relaxed">
                  Connect your GitHub account to enable automatic repository discovery, webhook verification, and workspace traceability.
                </p>
              </div>
            </div>

            <Button
              size="sm"
              onClick={handlePrimaryAction}
              disabled={isConnecting}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium shrink-0"
            >
              {isConnecting ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  Connecting...
                </>
              ) : (
                <>
                  <GitHubIcon className="h-3.5 w-3.5" />
                  Connect GitHub
                </>
              )}
            </Button>
          </div>
        </Card>
      ) : connection?.installationRequired || connection?.installationStatus !== "ACTIVE" ? (
        /* State 2: OAuth connected, App missing */
        <Card className="p-4 border-amber-900/60 bg-amber-950/20">
          <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-amber-950/40 border border-amber-800 text-amber-400">
                <ShieldAlert className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-zinc-100">GitHub: Connected as @{connection.githubLogin}</h4>
                  <Badge variant="outline" className="font-mono text-[10px] text-amber-300 border-amber-800">
                    Needs repository access
                  </Badge>
                </div>
                <p className="text-xs text-zinc-300 mt-0.5 max-w-xl leading-relaxed">
                  ForgeAI needs repository access through the GitHub App. Click below to approve repository permissions and complete your connection.
                </p>
              </div>
            </div>

            <Button
              size="sm"
              onClick={handlePrimaryAction}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium shrink-0"
            >
              <Check className="h-3.5 w-3.5" />
              Continue GitHub Setup
            </Button>
          </div>
        </Card>
      ) : (
        /* State 3: App installed & Ready */
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11]">
          <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-emerald-950/30 text-emerald-400 border border-emerald-900/60">
                <ShieldCheck className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <h4 className="text-sm font-semibold text-zinc-100">GitHub: Connected as @{connection.githubLogin}</h4>
                  <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900">
                    App Active
                  </Badge>
                  <Badge variant="outline" className="text-[10px] text-zinc-300 border-[#26262C] font-mono">
                    {connection.repositoriesAvailable} repositories available
                  </Badge>
                  <Badge variant="outline" className="text-[10px] text-zinc-400 border-[#26262C] font-mono">
                    {connection.workspaceScope}
                  </Badge>
                </div>
                <p className="text-xs text-zinc-400 mt-0.5">
                  Repositories synchronized with ForgeAI. Cryptographic webhooks verified with HMAC SHA-256.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              {connection.installationId && (
                <a
                  href={`https://github.com/settings/installations/${connection.installationId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs border-[#26262C] hover:text-white">
                    <ExternalLink className="h-3 w-3" />
                    Configure on GitHub
                  </Button>
                </a>
              )}
              <Button
                variant="outline"
                size="sm"
                onClick={handleReconcile}
                disabled={isReconciling}
                className="h-8 gap-1.5 text-xs border-[#26262C] text-zinc-300"
              >
                <RefreshCw className={`h-3 w-3 ${isReconciling ? "animate-spin" : ""}`} />
                {isReconciling ? "Reconciling..." : "Reconcile Access"}
              </Button>
              <Button
                size="sm"
                onClick={handleOpenConnectModal}
                className="h-8 gap-1.5 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
              >
                <Plus className="h-3.5 w-3.5" />
                Add Repositories
              </Button>
            </div>
          </div>
        </Card>
      )}

      {/* Connected Repositories Table Card */}
      <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
        <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
          <div className="flex items-center gap-2">
            <GitBranch className="h-4 w-4 text-zinc-400" />
            <h3 className="text-xs font-semibold text-zinc-200">Connected Repositories</h3>
            <Badge variant="outline" className="font-mono text-[10px] text-zinc-400 border-[#26262C]">{repositories.length}</Badge>
          </div>
          {connection?.installationStatus === "ACTIVE" && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleOpenConnectModal}
              className="h-7 gap-1 text-xs border-[#26262C] text-zinc-300"
            >
              <Plus className="h-3 w-3" />
              Add Repositories
            </Button>
          )}
        </div>

        {shouldShowLoading && (
          <div className="flex flex-col items-center justify-center p-12 text-zinc-400">
            <Loader2 className="h-6 w-6 animate-spin text-zinc-400 mb-2" />
            <p className="text-xs font-mono">Loading repositories...</p>
          </div>
        )}

        {shouldShowEmpty && (
          <div className="flex flex-col items-center justify-center p-12 text-center">
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-[#141418] text-zinc-500 mb-3 border border-[#26262C]">
              <GitBranch className="h-5 w-5" />
            </div>
            <h3 className="text-sm font-semibold text-zinc-200">No repositories connected yet</h3>
            <p className="text-xs text-zinc-400 max-w-sm mt-1 mb-5">
              Connect your GitHub account or import accessible repositories to start tracking code, pull requests, and CI runs.
            </p>
            <Button
              size="sm"
              onClick={handlePrimaryAction}
              disabled={isConnecting}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              {connection?.installationStatus === "ACTIVE" ? (
                <>
                  <Plus className="h-3.5 w-3.5" />
                  Add Repositories
                </>
              ) : (
                <>
                  <GitHubIcon className="h-3.5 w-3.5" />
                  Connect GitHub
                </>
              )}
            </Button>
          </div>
        )}

        {shouldShowTable && (
          <Table>
            <TableHeader>
              <TableRow className="border-[#1C1C21] hover:bg-transparent">
                <TableHead className="text-xs font-medium text-zinc-400">Repository</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Visibility</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Default Branch</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Scope</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Sync Status</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400">Last Synchronized</TableHead>
                <TableHead className="text-xs font-medium text-zinc-400 text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {repositories.map((repo) => (
                <TableRow key={repo.id} className="border-[#1C1C21] hover:bg-[#141418]">
                  <TableCell>
                    <div className="flex items-center gap-2.5">
                      <div className="flex h-7 w-7 items-center justify-center rounded bg-[#141418] text-zinc-400 border border-[#26262C]">
                        {repo.isPrivate ? <Lock className="h-3.5 w-3.5 text-amber-400" /> : <Globe className="h-3.5 w-3.5" />}
                      </div>
                      <div>
                        <div className="font-medium text-zinc-200 text-xs">{repo.fullName}</div>
                        <a
                          href={`https://github.com/${repo.fullName}`}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="text-[11px] text-zinc-500 hover:text-zinc-300 flex items-center gap-1"
                        >
                          View on GitHub
                          <ExternalLink className="h-2.5 w-2.5" />
                        </a>
                      </div>
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge
                      variant="outline"
                      className={`font-mono text-[10px] ${
                        repo.isPrivate
                          ? "border-amber-900 text-amber-400 bg-amber-950/20"
                          : "border-[#26262C] text-zinc-400"
                      }`}
                    >
                      {repo.isPrivate ? "Private" : "Public"}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1.5">
                      <span className="font-mono text-xs text-zinc-300">{repo.defaultBranch}</span>
                      {repo.latestObservedSha && (
                        <Badge variant="outline" className="text-[9px] font-mono text-zinc-400 border-[#26262C] px-1 py-0">
                          {repo.latestObservedSha.slice(0, 7)}
                        </Badge>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="font-mono text-[10px] border-[#26262C] text-zinc-400">
                      {repo.scope || "PERSONAL"}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    {renderSyncStatusBadge(repo)}
                  </TableCell>
                  <TableCell className="text-xs text-zinc-400 font-mono">
                    {repo.lastSyncAt ? formatDate(repo.lastSyncAt) : "Just now"}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleSyncRepository(repo.id, repo.fullName)}
                        disabled={syncingRepoId === repo.id || disconnectingRepoId === repo.id}
                        className="text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 text-xs gap-1 h-7 px-2"
                        title="Re-synchronize repository commits, pull requests, and webhooks"
                      >
                        <RefreshCw className={`h-3 w-3 ${syncingRepoId === repo.id ? "animate-spin" : ""}`} />
                        Sync
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleDisconnectRepository(repo.id, repo.fullName)}
                        disabled={disconnectingRepoId === repo.id || syncingRepoId === repo.id}
                        className="text-zinc-400 hover:text-rose-400 hover:bg-rose-950/20 text-xs gap-1 h-7 px-2"
                        title="Disconnect repository from workspace"
                      >
                        {disconnectingRepoId === repo.id ? (
                          <Loader2 className="h-3 w-3 animate-spin" />
                        ) : (
                          <Trash2 className="h-3 w-3" />
                        )}
                        Disconnect
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Card>

      {/* Available on GitHub Section */}
      {availableRepos.length > 0 && (
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-2 border-b border-[#1C1C21]">
            <div>
              <div className="flex items-center gap-2">
                <Globe className="h-4 w-4 text-zinc-400" />
                <h3 className="text-xs font-semibold text-zinc-200">Available on GitHub</h3>
                <Badge variant="outline" className="font-mono text-[10px] text-zinc-400 border-[#26262C]">
                  {availableRepos.length}
                </Badge>
              </div>
              <p className="text-[11px] text-zinc-500 mt-0.5">
                Discovered from your GitHub account. Click to connect any repository to this workspace.
              </p>
            </div>

            <div className="flex items-center gap-2 shrink-0">
              {connection?.installationId && (
                <a
                  href={`https://github.com/settings/installations/${connection.installationId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs border-[#26262C] text-zinc-300">
                    <ExternalLink className="h-3 w-3" />
                    Configure on GitHub
                  </Button>
                </a>
              )}
              {unimportedAvailable.length > 0 && (
                <Button
                  size="sm"
                  onClick={handleOpenConnectModal}
                  className="h-7 gap-1 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                >
                  <Plus className="h-3 w-3" />
                  Import Selected ({unimportedAvailable.length})
                </Button>
              )}
            </div>
          </div>

          <div className="divide-y divide-[#1C1C21]">
            {availableRepos.map((repo) => {
              const isAlreadyConnected = existingRepoFullNames.has(repo.fullName.toLowerCase());
              return (
                <div
                  key={repo.id}
                  className="flex items-center justify-between py-2.5 hover:bg-[#141418] transition-colors rounded-sm px-2"
                >
                  <div className="flex items-center gap-2.5">
                    <div className="flex h-7 w-7 items-center justify-center rounded bg-[#141418] text-zinc-400 border border-[#26262C]">
                      {repo.isPrivate ? (
                        <Lock className="h-3.5 w-3.5 text-amber-400" />
                      ) : (
                        <Globe className="h-3.5 w-3.5 text-zinc-400" />
                      )}
                    </div>
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-xs font-semibold text-zinc-200">{repo.fullName}</span>
                        <Badge
                          variant="outline"
                          className={`font-mono text-[9px] ${
                            repo.isPrivate
                              ? "text-amber-400 border-amber-900"
                              : "text-zinc-500 border-[#26262C]"
                          }`}
                        >
                          {repo.isPrivate ? "Private" : "Public"}
                        </Badge>
                        <span className="text-[10px] text-zinc-500 font-mono">
                          branch: {repo.defaultBranch}
                        </span>
                      </div>
                      <a
                        href={`https://github.com/${repo.fullName}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-[11px] text-zinc-500 hover:text-zinc-300 flex items-center gap-1"
                      >
                        github.com/{repo.fullName}
                        <ExternalLink className="h-2.5 w-2.5" />
                      </a>
                    </div>
                  </div>

                  <div>
                    {isAlreadyConnected ? (
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900 gap-1 py-0.5">
                          <Check className="h-3 w-3" />
                          Connected
                        </Badge>
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => {
                            const match = repositories.find((r) => r.fullName.toLowerCase() === repo.fullName.toLowerCase());
                            if (match) handleDisconnectRepository(match.id, match.fullName);
                          }}
                          disabled={disconnectingRepoId !== null}
                          className="text-[11px] h-6 px-2 text-zinc-400 hover:text-rose-400 hover:bg-rose-950/20"
                        >
                          Disconnect
                        </Button>
                      </div>
                    ) : (
                      <Button
                        size="sm"
                        onClick={() => handleConnectSingle(repo.fullName)}
                        disabled={isImporting}
                        className="h-7 px-2.5 gap-1 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                      >
                        {importingRepoName === repo.fullName ? (
                          <Loader2 className="h-3 w-3 animate-spin" />
                        ) : (
                          <Plus className="h-3 w-3" />
                        )}
                        {importingRepoName === repo.fullName ? "Connecting..." : "Connect"}
                      </Button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </Card>
      )}

      {/* Multi-Select Repository Import Modal */}
      <Dialog open={showConnectModal} onOpenChange={setShowConnectModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Import GitHub Repositories</DialogTitle>
              <DialogDescription>
                Select repositories accessible on your GitHub account to import into{" "}
                <span className="text-zinc-200 font-medium">{workspaceDisplayName}</span>.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowConnectModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleBatchImport} className="space-y-3.5 mt-2">
          {isLoadingAvailable ? (
            <div className="py-12 text-center text-zinc-400 space-y-2">
              <Loader2 className="mx-auto h-6 w-6 animate-spin text-zinc-400" />
              <p className="text-xs font-mono">Fetching accessible repositories live from GitHub...</p>
            </div>
          ) : availableRepos.length > 0 ? (
            <div className="space-y-3">
              {unimportedAvailable.length === 0 ? (
                <div className="rounded-md border border-[#26262C] bg-[#141418] p-3 text-xs text-zinc-400 flex items-center justify-between">
                  <span>All accessible repositories are currently imported into this workspace.</span>
                  {connection?.installationId && (
                    <a
                      href={`https://github.com/settings/installations/${connection.installationId}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="text-zinc-200 hover:underline flex items-center gap-1 font-medium"
                    >
                      Add more on GitHub
                      <ExternalLink className="h-3 w-3" />
                    </a>
                  )}
                </div>
              ) : (
                <div className="flex items-center justify-between text-xs text-zinc-400 pb-2 border-b border-[#1C1C21]">
                  <button
                    type="button"
                    onClick={toggleSelectAll}
                    className="flex items-center gap-2 hover:text-white transition-colors"
                  >
                    {selectedRepoNames.length > 0 && selectedRepoNames.length === unimportedAvailable.length ? (
                      <CheckSquare className="h-4 w-4 text-zinc-200" />
                    ) : (
                      <Square className="h-4 w-4 text-zinc-500" />
                    )}
                    <span>Select All Repositories ({unimportedAvailable.length})</span>
                  </button>
                  <span className="font-mono text-[11px] text-zinc-400">{selectedRepoNames.length} selected</span>
                </div>
              )}

              <div className="max-h-64 overflow-y-auto space-y-1.5 pr-1 divide-y divide-[#1C1C21]">
                {availableRepos.map((repo) => {
                  const isConnected = existingRepoFullNames.has(repo.fullName.toLowerCase());
                  const isChecked = selectedRepoNames.includes(repo.fullName);

                  return (
                    <div
                      key={repo.id}
                      onClick={() => !isConnected && toggleRepoSelection(repo.fullName)}
                      className={`flex items-center justify-between p-2 rounded-md border transition-all ${
                        isConnected
                          ? "border-transparent bg-[#141418]/50"
                          : isChecked
                          ? "border-zinc-500 bg-[#141418] cursor-pointer"
                          : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C] cursor-pointer"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        {!isConnected && (
                          <div className="text-zinc-200">
                            {isChecked ? (
                              <CheckSquare className="h-3.5 w-3.5" />
                            ) : (
                              <Square className="h-3.5 w-3.5 text-zinc-500" />
                            )}
                          </div>
                        )}
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-semibold text-zinc-200">{repo.fullName}</span>
                            <Badge
                              variant="outline"
                              className={`font-mono text-[9px] py-0 px-1.5 ${
                                repo.isPrivate
                                  ? "text-amber-400 border-amber-900"
                                  : "text-zinc-500 border-[#26262C]"
                              }`}
                            >
                              {repo.isPrivate ? "Private" : "Public"}
                            </Badge>
                          </div>
                          <span className="text-[10px] text-zinc-500 font-mono">
                            branch: {repo.defaultBranch}
                          </span>
                        </div>
                      </div>

                      {isConnected ? (
                        <div className="flex items-center gap-2">
                          <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900">
                            Imported
                          </Badge>
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={(e) => {
                              e.stopPropagation();
                              const match = repositories.find((r) => r.fullName.toLowerCase() === repo.fullName.toLowerCase());
                              if (match) handleDisconnectRepository(match.id, match.fullName);
                            }}
                            disabled={disconnectingRepoId !== null}
                            className="text-[10px] h-6 px-1.5 text-zinc-400 hover:text-rose-400 hover:bg-rose-950/20"
                          >
                            Disconnect
                          </Button>
                        </div>
                      ) : (
                        <Button
                          type="button"
                          size="sm"
                          disabled={isImporting}
                          onClick={(e) => {
                            e.stopPropagation();
                            handleConnectSingle(repo.fullName);
                          }}
                          className="text-[10px] h-6 px-2 gap-1 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                        >
                          {importingRepoName === repo.fullName ? (
                            <Loader2 className="h-3 w-3 animate-spin" />
                          ) : (
                            <Plus className="h-3 w-3" />
                          )}
                          Import
                        </Button>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="py-8 text-center text-zinc-400 space-y-3">
              <ShieldAlert className="mx-auto h-8 w-8 text-amber-400" />
              <p className="text-xs">No repositories found for this GitHub App installation.</p>
              {connection?.installationId && (
                <a
                  href={`https://github.com/settings/installations/${connection.installationId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs border-[#26262C]">
                    <ExternalLink className="h-3.5 w-3.5" />
                    Configure Access on GitHub
                  </Button>
                </a>
              )}
            </div>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowConnectModal(false)}
              className="h-8 text-xs border-[#26262C]"
            >
              Cancel
            </Button>
            <Button
              type="submit"
              size="sm"
              disabled={isImporting || selectedRepoNames.length === 0}
              className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              {isImporting ? (
                <>
                  <Loader2 className="h-3.5 w-3.5 animate-spin" />
                  Importing...
                </>
              ) : (
                <>
                  <Plus className="h-3.5 w-3.5" />
                  Import ({selectedRepoNames.length})
                </>
              )}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}
