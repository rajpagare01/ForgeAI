"use client";

import React, { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import {
  Repository,
  GitHubHealth,
  GitHubInstallation,
  AvailableRepository,
  InstallationDiscovery,
  ReconciliationReport,
} from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
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
  ServerOff,
  Sparkles,
  Layers,
  CheckSquare,
  Square,
  ArrowRight,
} from "lucide-react";

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
import { Input } from "@/components/ui/input";
import { formatDate } from "@/lib/utils";

export default function RepositoriesPage() {
  const { currentOrg, isLoading: isTenantLoading, activeWorkspace, isPersonalWorkspace } = useTenant();
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [githubHealth, setGithubHealth] = useState<GitHubHealth | null>(null);
  const [currentInstallation, setCurrentInstallation] = useState<GitHubInstallation | null>(null);
  const [discoveryData, setDiscoveryData] = useState<InstallationDiscovery | null>(null);
  const [availableRepos, setAvailableRepos] = useState<AvailableRepository[]>([]);
  
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingAvailable, setIsLoadingAvailable] = useState(false);
  const [isReconciling, setIsReconciling] = useState(false);
  const [isOnboarding, setIsOnboarding] = useState(false);
  const [isImporting, setIsImporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Modal & Selection states
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [selectedRepoNames, setSelectedRepoNames] = useState<string[]>([]);
  const [manualFullName, setManualFullName] = useState("");

  const workspaceDisplayName = isPersonalWorkspace ? "Personal Workspace" : currentOrg?.name || "Organization Workspace";

  const fetchData = useCallback(async () => {
    if (isTenantLoading || !activeWorkspace?.id) return;
    setIsLoading(true);
    setError(null);

    // 1. Fetch GitHub Health diagnostic
    try {
      const health = await apiClient.get<GitHubHealth>("/api/v1/github/health");
      setGithubHealth(health);
    } catch (e: any) {
      console.warn("Could not fetch GitHub health endpoint:", e);
      setGithubHealth({
        configured: false,
        status: "UNREACHABLE",
        httpStatus: e?.status || undefined,
        message: e?.message || "GitHub health endpoint unreachable",
      });
    }

    // 2. Fetch current claimed installation for this workspace
    try {
      const inst = await apiClient.get<GitHubInstallation>(`/api/v1/installations/current?workspaceId=${activeWorkspace.id}`);
      setCurrentInstallation(inst);
    } catch {
      setCurrentInstallation(null);
    }

    // 3. Fetch automated discovery data (candidates, app URL, user GitHub identity)
    try {
      const disc = await apiClient.get<InstallationDiscovery>(`/api/v1/installations/discover?workspaceId=${activeWorkspace.id}`);
      setDiscoveryData(disc);
    } catch (e) {
      console.warn("Could not fetch installation discovery:", e);
    }

    // 4. Fetch connected repositories
    try {
      const data = await apiClient.get<any[]>(`/api/v1/repositories?workspaceId=${activeWorkspace.id}`);
      if (Array.isArray(data)) {
        const mapped: Repository[] = data.map((r) => ({
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
        }));
        setRepositories(mapped);
      } else {
        setRepositories([]);
      }
    } catch (err: any) {
      console.warn("Failed to load repositories from Gateway:", err);
      const status = err?.status;
      let userFriendlyError = `Could not load repositories for ${workspaceDisplayName}.`;

      if (status === 401 || status === 403) {
        userFriendlyError = `Authorization error (HTTP ${status}): Access denied to repositories for this workspace.`;
      } else if (status === 404) {
        userFriendlyError = "Scope error (HTTP 404): Workspace or repository scope not found.";
      } else if (status === 503) {
        userFriendlyError = "Service unavailable (HTTP 503): GitHub integration service is temporarily unavailable.";
      } else if (err?.isNetworkError || !status) {
        userFriendlyError = "Service unreachable: Unable to connect to Gateway or Source Control service.";
      } else {
        userFriendlyError = `Error loading repositories (HTTP ${status}): ${err.message || "Unexpected failure"}`;
      }

      setError(userFriendlyError);
      setRepositories([]);
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace?.id, isTenantLoading, workspaceDisplayName]);

  // Handle GitHub App setup redirect URL (?installation_id=...) with strict cryptographic/API ownership check
  useEffect(() => {
    if (typeof window === "undefined" || !activeWorkspace?.id) return;
    const params = new URLSearchParams(window.location.search);
    const instIdParam = params.get("installation_id");

    if (instIdParam) {
      const parsedId = parseInt(instIdParam.trim(), 10);
      if (!isNaN(parsedId) && parsedId > 0) {
        setIsOnboarding(true);
        setError(null);
        apiClient.post<GitHubInstallation>("/api/v1/installations/setup-callback", {
          installationId: parsedId,
          workspaceId: activeWorkspace.id,
        })
          .then((claimed) => {
            setCurrentInstallation(claimed);
            setSuccess(`Successfully verified and connected GitHub App installation for @${claimed.accountLogin}!`);
            // Clean up URL query parameters without reloading
            const cleanUrl = window.location.pathname;
            window.history.replaceState({}, document.title, cleanUrl);
            fetchData();
          })
          .catch((err) => {
            console.error("Setup callback failed:", err);
            setError(err?.message || "Verification failed: Could not validate GitHub installation ownership.");
          })
          .finally(() => {
            setIsOnboarding(false);
          });
      }
    }
  }, [activeWorkspace?.id, fetchData]);

  useEffect(() => {
    if (!isTenantLoading && activeWorkspace?.id) {
      fetchData();
    }
  }, [fetchData, isTenantLoading, activeWorkspace?.id]);

  // Automated One-Click Onboard flow (Zero manual installation ID required)
  const handleAutoOnboard = async (candidateInstallationId?: number) => {
    if (!activeWorkspace?.id) return;
    setIsOnboarding(true);
    setError(null);
    setSuccess(null);

    try {
      const onboarded = await apiClient.post<GitHubInstallation>("/api/v1/installations/auto-onboard", {
        installationId: candidateInstallationId,
        workspaceId: activeWorkspace.id,
      });
      setCurrentInstallation(onboarded);
      setSuccess(`GitHub App installation for @${onboarded.accountLogin} connected and synchronized.`);
      await fetchData();
    } catch (err: any) {
      console.error("Auto-onboarding failed:", err);
      setError(err?.message || "Failed to onboard GitHub App installation.");
    } finally {
      setIsOnboarding(false);
    }
  };

  // Idempotent reconciliation
  const handleReconcile = async () => {
    if (!currentInstallation?.installationId || !activeWorkspace?.id) return;
    setIsReconciling(true);
    setError(null);
    setSuccess(null);

    try {
      const report = await apiClient.post<ReconciliationReport>(
        `/api/v1/installations/${currentInstallation.installationId}/reconcile?workspaceId=${activeWorkspace.id}`
      );
      setSuccess(
        `Reconciliation complete: ${report.totalAccessible} accessible repositories live on GitHub (${report.newlyAdded} newly added, ${report.removedOrInaccessible} removed/inaccessible).`
      );
      await fetchData();
    } catch (err: any) {
      console.error("Reconciliation failed:", err);
      setError(err?.message || "Reconciliation failed.");
    } finally {
      setIsReconciling(false);
    }
  };

  // Open repository import dialog and fetch available repositories live from GitHub
  const handleOpenConnectModal = async () => {
    setShowConnectModal(true);
    setSelectedRepoNames([]);
    setManualFullName("");
    setError(null);

    if (currentInstallation?.status === "ACTIVE" && activeWorkspace?.id) {
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

  const toggleSelectAll = () => {
    const existingFullNames = new Set(repositories.map((r) => r.fullName.toLowerCase()));
    const unimportedRepos = availableRepos.filter((r) => !existingFullNames.has(r.fullName.toLowerCase()));
    
    if (selectedRepoNames.length === unimportedRepos.length) {
      setSelectedRepoNames([]);
    } else {
      setSelectedRepoNames(unimportedRepos.map((r) => r.fullName));
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

    const reposToConnect = selectedRepoNames.length > 0 
      ? selectedRepoNames 
      : (manualFullName.trim() ? [manualFullName.trim()] : []);

    if (reposToConnect.length === 0) {
      setError("Please select at least one repository to import.");
      return;
    }

    setIsImporting(true);
    setError(null);
    setSuccess(null);

    try {
      const res = await apiClient.post<any[]>("/api/v1/repositories/batch-connect", {
        repositoryNames: reposToConnect,
        workspaceId: activeWorkspace.id,
      });

      setShowConnectModal(false);
      setSelectedRepoNames([]);
      setManualFullName("");
      setSuccess(`Successfully imported and synchronized ${Array.isArray(res) ? res.length : reposToConnect.length} repository/repositories.`);
      await fetchData();
    } catch (err: any) {
      console.error("Batch repository import failed:", err);
      setError(err?.message || "Failed to import selected repositories.");
    } finally {
      setIsImporting(false);
    }
  };

  const existingRepoFullNames = new Set(repositories.map((r) => r.fullName.toLowerCase()));
  const unimportedAvailableCount = availableRepos.filter((r) => !existingRepoFullNames.has(r.fullName.toLowerCase())).length;

  return (
    <div className="space-y-8">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <Radio className="h-6 w-6 text-sky-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              Connected Source Control Repositories
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Automatic GitHub App discovery, cryptographic webhook verification, and workspace traceability for{" "}
            <span className="text-sky-300 font-medium">{workspaceDisplayName}</span>.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={fetchData}
            disabled={isLoading || isReconciling}
            className="gap-2"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            Sync Now
          </Button>

          {currentInstallation?.status === "ACTIVE" ? (
            <Button
              variant="glow"
              size="sm"
              onClick={handleOpenConnectModal}
              className="gap-2"
            >
              <Plus className="h-4 w-4" />
              Import Repositories
            </Button>
          ) : discoveryData?.candidates && discoveryData.candidates.length > 0 ? (
            <Button
              variant="glow"
              size="sm"
              onClick={() => handleAutoOnboard(discoveryData.candidates[0].installationId)}
              disabled={isOnboarding}
              className="gap-2"
            >
              <Sparkles className="h-4 w-4 text-amber-300" />
              {isOnboarding ? "Connecting..." : "Connect Discovered Installation"}
            </Button>
          ) : (
            <a
              href={discoveryData?.appInstallUrl || "https://github.com/apps/forgeai-app/installations/new"}
              target="_blank"
              rel="noopener noreferrer"
            >
              <Button variant="glow" size="sm" className="gap-2">
                <ExternalLink className="h-4 w-4" />
                Install ForgeAI GitHub App
              </Button>
            </a>
          )}
        </div>
      </div>

      {/* Global Alerts */}
      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {success && (
        <div className="flex items-center gap-2 rounded-xl border border-emerald-500/30 bg-emerald-950/40 p-3 text-xs text-emerald-300">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
          <span>{success}</span>
        </div>
      )}

      {/* Diagnostic & Lifecycle Status Banners */}
      {githubHealth?.status === "UNREACHABLE" ? (
        <Card className="border-rose-500/30 bg-rose-950/20">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-rose-500/20 text-rose-400 border border-rose-500/30">
                <ServerOff className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub Integration Unreachable</h4>
                  <Badge variant="destructive" className="text-[10px]">Unreachable</Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  Could not reach the GitHub health diagnostic endpoint. The API Gateway or Source Control service may be restarting.
                </p>
              </div>
            </div>
            <div className="text-xs font-mono text-rose-400 font-medium">
              {githubHealth.httpStatus ? `Status: HTTP ${githubHealth.httpStatus}` : "Offline"}
            </div>
          </CardContent>
        </Card>
      ) : githubHealth?.status === "UNCONFIGURED" ? (
        <Card className="border-amber-500/30 bg-amber-950/20">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30">
                <AlertTriangle className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub Integration Not Configured</h4>
                  <Badge variant="warning" className="text-[10px]">Credentials Required</Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  Server environment variables <code className="text-amber-300 font-mono">GITHUB_APP_ID</code> and{" "}
                  <code className="text-amber-300 font-mono">GITHUB_PRIVATE_KEY</code> are required for automated discovery.
                </p>
              </div>
            </div>
          </CardContent>
        </Card>
      ) : currentInstallation?.status === "ACTIVE" ? (
        /* Active Installation Banner with Metadata & Reconcile Button */
        <Card className="border-emerald-500/30 bg-emerald-950/10">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
                <ShieldCheck className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub App Connected</h4>
                  <Badge variant="success" className="text-[10px]">Active</Badge>
                  <span className="text-xs font-mono text-emerald-300">
                    @{currentInstallation.accountLogin}
                  </span>
                  <Badge variant="outline" className="text-[10px] text-slate-400 border-slate-700">
                    {currentInstallation.scope || "PERSONAL"}
                  </Badge>
                  {currentInstallation.repositorySelection && (
                    <Badge variant="outline" className="text-[10px] text-sky-400 border-sky-500/30">
                      Access: {currentInstallation.repositorySelection}
                    </Badge>
                  )}
                </div>
                <p className="text-xs text-slate-400 mt-0.5">
                  Synchronized with ForgeAI. Webhooks verified with HMAC SHA-256. Short-lived access tokens generated on-demand.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-3">
              <Button
                variant="outline"
                size="sm"
                onClick={handleReconcile}
                disabled={isReconciling}
                className="gap-2 text-xs border-emerald-500/30 hover:bg-emerald-950/20"
              >
                <RefreshCw className={`h-3 w-3 ${isReconciling ? "animate-spin" : ""}`} />
                {isReconciling ? "Reconciling..." : "Reconcile Access"}
              </Button>
              <Button
                variant="glow"
                size="sm"
                onClick={handleOpenConnectModal}
                className="gap-2 text-xs"
              >
                <Layers className="h-3.5 w-3.5" />
                Import Repositories
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : discoveryData?.candidates && discoveryData.candidates.length > 0 ? (
        /* Discovered Installation Ready for Zero-Click Association */
        <Card className="border-sky-500/40 bg-sky-950/20">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-sky-500/20 text-sky-400 border border-sky-500/40 animate-pulse">
                <Sparkles className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub App Installation Detected</h4>
                  <Badge variant="outline" className="text-[10px] text-amber-300 border-amber-500/40">Ready to Onboard</Badge>
                  <span className="text-xs font-mono text-sky-300 font-semibold">
                    @{discoveryData.candidates[0].accountLogin}
                  </span>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  ForgeAI automatically discovered your GitHub App installation on account{" "}
                  <strong className="text-white">@{discoveryData.candidates[0].accountLogin}</strong>.
                  Connect it to <span className="text-sky-300 font-medium">{workspaceDisplayName}</span> without manually entering any IDs.
                </p>
              </div>
            </div>

            <Button
              variant="glow"
              size="sm"
              onClick={() => handleAutoOnboard(discoveryData.candidates[0].installationId)}
              disabled={isOnboarding}
              className="gap-2 shrink-0 bg-sky-500 hover:bg-sky-400 text-slate-950 font-semibold"
            >
              {isOnboarding ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  Connecting...
                </>
              ) : (
                <>
                  <ArrowRight className="h-4 w-4" />
                  Connect & Discover Repos
                </>
              )}
            </Button>
          </CardContent>
        </Card>
      ) : (
        /* No Installation Detected — Direct Link to Install */
        <Card className="border-sky-500/20 bg-slate-900/40">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-sky-500/10 text-sky-400 border border-sky-500/20">
                <ShieldAlert className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">Install ForgeAI GitHub App</h4>
                  <Badge variant="outline" className="text-[10px] text-slate-400 border-slate-700">Not Installed</Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  Install the ForgeAI GitHub App on your personal GitHub account or organization. Once installed, return here and ForgeAI will automatically detect and onboard your repositories.
                </p>
              </div>
            </div>

            <div className="flex items-center gap-3 shrink-0">
              <a
                href={discoveryData?.appInstallUrl || "https://github.com/apps/forgeai-app/installations/new"}
                target="_blank"
                rel="noopener noreferrer"
              >
                <Button variant="glow" size="sm" className="gap-2">
                  <ExternalLink className="h-4 w-4" />
                  Install on GitHub
                </Button>
              </a>
              <Button
                variant="outline"
                size="sm"
                onClick={fetchData}
                disabled={isLoading}
                className="gap-2 text-xs"
              >
                <RefreshCw className={`h-3 w-3 ${isLoading ? "animate-spin" : ""}`} />
                Check Installation
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Repositories Table */}
      <Card>
        <div className="flex items-center justify-between border-b border-slate-800 p-4">
          <div className="flex items-center gap-2">
            <GitBranch className="h-4 w-4 text-sky-400" />
            <h3 className="text-sm font-medium text-white">Repositories</h3>
            <Badge variant="outline" className="text-xs">{repositories.length}</Badge>
          </div>
          {currentInstallation?.status === "ACTIVE" && (
            <Button
              variant="outline"
              size="sm"
              onClick={handleOpenConnectModal}
              className="gap-2 text-xs"
            >
              <Plus className="h-3.5 w-3.5" />
              Import Repositories
            </Button>
          )}
        </div>

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Repository</TableHead>
              <TableHead>Visibility</TableHead>
              <TableHead>Default Branch</TableHead>
              <TableHead>Scope</TableHead>
              <TableHead>Sync Status</TableHead>
              <TableHead>Last Synchronized</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-slate-500">
                  <Loader2 className="mx-auto h-6 w-6 animate-spin text-sky-400" />
                  <p className="mt-2 text-xs">Loading repositories...</p>
                </TableCell>
              </TableRow>
            ) : repositories.length === 0 ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-slate-500">
                  <Radio className="mx-auto h-8 w-8 text-slate-600 mb-2" />
                  <p className="text-sm font-medium text-slate-400">No repositories imported yet</p>
                  <p className="text-xs text-slate-500 mt-1">
                    {currentInstallation?.status === "ACTIVE"
                      ? "Click 'Import Repositories' to select accessible repositories from GitHub."
                      : "Install and connect the ForgeAI GitHub App to discover and import repositories."}
                  </p>
                </TableCell>
              </TableRow>
            ) : (
              repositories.map((repo) => (
                <TableRow key={repo.id}>
                  <TableCell>
                    <div className="flex items-center gap-2">
                      <span className="font-medium text-white">{repo.fullName}</span>
                      <a
                        href={`https://github.com/${repo.fullName}`}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-slate-400 hover:text-white"
                      >
                        <ExternalLink className="h-3.5 w-3.5" />
                      </a>
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1.5 text-xs text-slate-300">
                      {repo.isPrivate ? (
                        <>
                          <Lock className="h-3.5 w-3.5 text-amber-400" />
                          <span>Private</span>
                        </>
                      ) : (
                        <>
                          <Globe className="h-3.5 w-3.5 text-slate-400" />
                          <span>Public</span>
                        </>
                      )}
                    </div>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="font-mono text-xs text-slate-300 border-slate-700">
                      {repo.defaultBranch}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="text-xs border-slate-700 text-slate-400">
                      {repo.scope || (isPersonalWorkspace ? "PERSONAL" : "ORGANIZATION")}
                    </Badge>
                  </TableCell>
                  <TableCell>
                    <div className="flex items-center gap-1.5">
                      <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
                      <span className="text-xs text-emerald-400 font-medium">Synchronized</span>
                    </div>
                  </TableCell>
                  <TableCell className="text-xs text-slate-400">
                    {formatDate(repo.lastSyncAt)}
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </Card>

      {/* Multi-Select Repository Import Modal (Section 20 UI) */}
      <Dialog open={showConnectModal} onOpenChange={setShowConnectModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Import GitHub Repositories</DialogTitle>
              <DialogDescription>
                Select repositories accessible to your ForgeAI GitHub App installation to import into{" "}
                <span className="text-sky-300 font-medium">{workspaceDisplayName}</span>.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowConnectModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleBatchImport} className="space-y-4 mt-2">
          {isLoadingAvailable ? (
            <div className="py-12 text-center text-slate-400 space-y-2">
              <Loader2 className="mx-auto h-6 w-6 animate-spin text-sky-400" />
              <p className="text-xs">Fetching accessible repositories live from GitHub...</p>
            </div>
          ) : availableRepos.length > 0 ? (
            <div className="space-y-3">
              <div className="flex items-center justify-between text-xs text-slate-400 pb-2 border-b border-slate-800">
                <button
                  type="button"
                  onClick={toggleSelectAll}
                  className="flex items-center gap-2 hover:text-white transition-colors"
                >
                  {selectedRepoNames.length > 0 && selectedRepoNames.length === unimportedAvailableCount ? (
                    <CheckSquare className="h-4 w-4 text-sky-400" />
                  ) : (
                    <Square className="h-4 w-4 text-slate-500" />
                  )}
                  <span>Select All Accessible Repositories</span>
                </button>
                <span className="font-mono text-slate-400">
                  {selectedRepoNames.length} selected
                </span>
              </div>

              <div className="max-h-60 overflow-y-auto space-y-1.5 pr-1">
                {availableRepos.map((r) => {
                  const isAlreadyConnected = existingRepoFullNames.has(r.fullName.toLowerCase());
                  const isSelected = selectedRepoNames.includes(r.fullName);

                  return (
                    <div
                      key={r.id}
                      onClick={() => !isAlreadyConnected && toggleRepoSelection(r.fullName)}
                      className={`flex items-center justify-between p-2.5 rounded-lg border transition-all ${
                        isAlreadyConnected
                          ? "opacity-50 border-slate-800/60 bg-slate-900/20 cursor-not-allowed"
                          : isSelected
                          ? "border-sky-500/60 bg-sky-950/30 cursor-pointer"
                          : "border-slate-800 bg-slate-900/40 hover:border-slate-700 cursor-pointer"
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        {isAlreadyConnected ? (
                          <Check className="h-4 w-4 text-emerald-400 shrink-0" />
                        ) : isSelected ? (
                          <CheckSquare className="h-4 w-4 text-sky-400 shrink-0" />
                        ) : (
                          <Square className="h-4 w-4 text-slate-600 shrink-0" />
                        )}
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-semibold text-white">{r.fullName}</span>
                            {r.isPrivate ? (
                              <Lock className="h-3 w-3 text-amber-400" />
                            ) : (
                              <Globe className="h-3 w-3 text-slate-400" />
                            )}
                          </div>
                          <p className="text-[11px] text-slate-400">Default branch: {r.defaultBranch}</p>
                        </div>
                      </div>

                      {isAlreadyConnected ? (
                        <Badge variant="outline" className="text-[10px] text-emerald-400 border-emerald-500/30">
                          Connected
                        </Badge>
                      ) : (
                        <span className="text-[11px] text-slate-500 font-mono">Ready to import</span>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="p-4 rounded-xl border border-slate-800 bg-slate-900/40 text-center space-y-2">
              <p className="text-xs text-slate-300">
                No accessible repositories returned by GitHub for this installation.
              </p>
              <p className="text-[11px] text-slate-500">
                Verify repository selection in GitHub App settings or configure a repository manually below:
              </p>
              <div className="pt-2">
                <Input
                  placeholder="e.g. owner/repo-name"
                  value={manualFullName}
                  onChange={(e) => setManualFullName(e.target.value)}
                  className="text-xs"
                />
              </div>
            </div>
          )}

          <DialogFooter className="mt-4">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowConnectModal(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="glow"
              size="sm"
              disabled={
                isImporting ||
                (selectedRepoNames.length === 0 && !manualFullName.trim())
              }
            >
              {isImporting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin mr-1.5" />
                  Importing Repositories...
                </>
              ) : (
                `Import Selected (${selectedRepoNames.length > 0 ? selectedRepoNames.length : manualFullName.trim() ? 1 : 0})`
              )}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}
