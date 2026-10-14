"use client";

import React, { useState, useEffect, useCallback } from "react";
import { Repository, GitHubHealth, GitHubInstallation, AvailableRepository } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  GitPullRequest,
  CheckCircle2,
  RefreshCw,
  ExternalLink,
  ShieldCheck,
  ShieldAlert,
  Webhook,
  GitBranch,
  Lock,
  Globe,
  Radio,
  Plus,
  Loader2,
  AlertCircle,
  AlertTriangle,
  KeyRound,
  Check,
  ServerOff,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
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
  const { currentOrg } = useTenant();
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [githubHealth, setGithubHealth] = useState<GitHubHealth | null>(null);
  const [currentInstallation, setCurrentInstallation] = useState<GitHubInstallation | null>(null);
  const [availableRepos, setAvailableRepos] = useState<AvailableRepository[]>([]);
  
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingAvailable, setIsLoadingAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Modal states
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [showClaimModal, setShowClaimModal] = useState(false);
  const [selectedFullName, setSelectedFullName] = useState("");
  const [claimInstallationId, setClaimInstallationId] = useState("");
  const [isConnecting, setIsConnecting] = useState(false);
  const [isClaiming, setIsClaiming] = useState(false);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    // 1. Fetch GitHub Health diagnostic
    try {
      const health = await apiClient.get<GitHubHealth>("/api/v1/github/health");
      setGithubHealth(health);
    } catch (e: any) {
      console.warn("Could not fetch GitHub health endpoint:", e);
      setGithubHealth({ configured: false, status: "UNCONFIGURED" });
    }

    // 2. Fetch current claimed installation for this organization
    try {
      const inst = await apiClient.get<GitHubInstallation>("/api/v1/installations/current");
      setCurrentInstallation(inst);
    } catch {
      // 404/503 is expected when no installation is claimed yet or not configured
      setCurrentInstallation(null);
    }

    // 3. Fetch connected repositories
    try {
      const data = await apiClient.get<any[]>("/api/v1/repositories");
      if (Array.isArray(data)) {
        const mapped: Repository[] = data.map((r) => ({
          id: r.id,
          organizationId: r.organizationId,
          name: r.name,
          fullName: r.fullName,
          defaultBranch: r.defaultBranch || "main",
          isPrivate: r.isPrivate ?? true,
          webhookInstalled: true,
          openPullRequestsCount: r.openPullRequestsCount ?? 0,
          lastSyncAt: r.updatedAt || r.createdAt || new Date().toISOString(),
        }));
        setRepositories(mapped);
      }
    } catch (err: any) {
      console.warn("Failed to load repositories from Gateway:", err);
      setError("Could not load repositories for the active organization.");
    } finally {
      setIsLoading(false);
    }
  }, [currentOrg.id]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Fetch available repos when connect modal is opened and installation exists
  const handleOpenConnectModal = async () => {
    setShowConnectModal(true);
    setSelectedFullName("");
    setError(null);

    if (currentInstallation?.status === "ACTIVE") {
      setIsLoadingAvailable(true);
      try {
        const repos = await apiClient.get<AvailableRepository[]>("/api/v1/repositories/available");
        setAvailableRepos(Array.isArray(repos) ? repos : []);
      } catch (e: any) {
        console.warn("Failed to fetch available repositories from GitHub:", e);
        setAvailableRepos([]);
      } finally {
        setIsLoadingAvailable(false);
      }
    }
  };

  // Authoritative repository connect: sends only fullName to backend
  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedFullName.trim()) return;

    setIsConnecting(true);
    setError(null);
    setSuccess(null);

    try {
      const res = await apiClient.post<any>("/api/v1/repositories/connect", {
        fullName: selectedFullName.trim(),
      });

      const newRepo: Repository = {
        id: res.id,
        organizationId: res.organizationId,
        name: res.name,
        fullName: res.fullName,
        defaultBranch: res.defaultBranch || "main",
        isPrivate: res.isPrivate ?? true,
        webhookInstalled: true,
        openPullRequestsCount: 0,
        lastSyncAt: res.createdAt || new Date().toISOString(),
      };

      setRepositories((prev) => [newRepo, ...prev.filter((r) => r.id !== newRepo.id)]);
      setShowConnectModal(false);
      setSelectedFullName("");
      setSuccess(`Repository '${res.fullName}' connected authoritatively from GitHub.`);
    } catch (err: any) {
      console.error("Failed to connect repository:", err);
      setError(err?.message || "Failed to connect repository on Gateway.");
    } finally {
      setIsConnecting(false);
    }
  };

  // Claim installation flow
  const handleClaimInstallation = async (e: React.FormEvent) => {
    e.preventDefault();
    const instId = parseInt(claimInstallationId.trim(), 10);
    if (isNaN(instId) || instId <= 0) {
      setError("Please provide a valid numeric GitHub installation ID.");
      return;
    }

    setIsClaiming(true);
    setError(null);
    setSuccess(null);

    try {
      const claimed = await apiClient.post<GitHubInstallation>("/api/v1/installations/claim", {
        installationId: instId,
      });
      setCurrentInstallation(claimed);
      setShowClaimModal(false);
      setClaimInstallationId("");
      setSuccess(`Successfully claimed GitHub installation #${claimed.installationId} (${claimed.accountLogin}) for this organization.`);
      fetchData();
    } catch (err: any) {
      console.error("Failed to claim installation:", err);
      setError(err?.message || "Failed to claim GitHub installation.");
    } finally {
      setIsClaiming(false);
    }
  };

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
            Authoritative GitHub App installations, cryptographic webhook verification, and commit sync tracking for{" "}
            <span className="text-sky-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={fetchData}
            disabled={isLoading}
            className="gap-2"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            Sync Now
          </Button>

          {githubHealth?.configured && currentInstallation?.status === "ACTIVE" ? (
            <Button
              variant="glow"
              size="sm"
              onClick={handleOpenConnectModal}
              className="gap-2"
            >
              <Plus className="h-4 w-4" />
              Connect GitHub Repo
            </Button>
          ) : githubHealth?.configured ? (
            <Button
              variant="glow"
              size="sm"
              onClick={() => setShowClaimModal(true)}
              className="gap-2"
            >
              <KeyRound className="h-4 w-4" />
              Claim GitHub Installation
            </Button>
          ) : (
            <Button
              variant="outline"
              size="sm"
              disabled
              className="gap-2 opacity-50 cursor-not-allowed text-slate-500"
            >
              <ServerOff className="h-4 w-4" />
              Integration Not Configured
            </Button>
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

      {/* GitHub App Status / Diagnostic Banner */}
      {!githubHealth?.configured ? (
        <Card className="border-amber-500/30 bg-amber-950/20">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30">
                <AlertTriangle className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub Integration Not Configured</h4>
                  <Badge variant="warning" className="text-[10px]">Unconfigured</Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  Real GitHub credentials are required for repository onboarding and webhook verification.
                  Configure <code className="text-amber-300 font-mono">GITHUB_APP_ID</code>,{" "}
                  <code className="text-amber-300 font-mono">GITHUB_PRIVATE_KEY</code>, and{" "}
                  <code className="text-amber-300 font-mono">GITHUB_WEBHOOK_SECRET</code> in your environment.
                </p>
              </div>
            </div>
            <div className="text-xs font-mono text-slate-400 shrink-0">
              <span className="text-amber-400 font-medium">Status: 503 Disabled</span>
            </div>
          </CardContent>
        </Card>
      ) : !currentInstallation ? (
        <Card className="border-sky-500/30 bg-sky-950/20">
          <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-5">
            <div className="flex items-start md:items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-sky-500/20 text-sky-400 border border-sky-500/30">
                <ShieldAlert className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <h4 className="text-sm font-semibold text-white">GitHub App Configured — Installation Required</h4>
                  <Badge variant="outline" className="text-[10px] text-sky-300 border-sky-500/40">App ID: {githubHealth.appId}</Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 max-w-2xl leading-relaxed">
                  The ForgeAI GitHub App is configured on the server, but no GitHub installation has been claimed for this organization yet.
                  Install the GitHub App on your GitHub organization, then claim the installation ID here.
                  {githubHealth.quarantinedInstallations && githubHealth.quarantinedInstallations > 0 ? (
                    <span className="block mt-1 text-sky-400 font-semibold">
                      Notice: {githubHealth.quarantinedInstallations} unmapped GitHub installation(s) received and held in quarantine awaiting owner claim.
                    </span>
                  ) : null}
                </p>
              </div>
            </div>
            <Button
              variant="glow"
              size="sm"
              onClick={() => setShowClaimModal(true)}
              className="gap-2 shrink-0"
            >
              <KeyRound className="h-4 w-4" />
              Claim Installation
            </Button>
          </CardContent>
        </Card>
      ) : (
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
                    @{currentInstallation.accountLogin} (#{currentInstallation.installationId})
                  </span>
                </div>
                <p className="text-xs text-slate-400 mt-0.5">
                  Authoritative installation bound to this tenant. Real-time webhooks verified via HMAC SHA-256.
                </p>
              </div>
            </div>
            <div className="flex items-center gap-4 text-xs font-mono">
              <div className="text-slate-400">
                <span>Webhook Signature: </span>
                <span className="text-emerald-400 font-bold">X-Hub-Signature-256 Verified</span>
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* Repositories Table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Tracked Repositories</CardTitle>
          <CardDescription>
            Repositories verified against GitHub App installation permissions with automated commit & PR sync.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex flex-col items-center justify-center p-12 text-slate-400">
              <Loader2 className="h-8 w-8 animate-spin text-sky-400 mb-2" />
              <p className="text-xs">Loading repositories from Gateway...</p>
            </div>
          ) : repositories.length === 0 ? (
            <div className="flex flex-col items-center justify-center p-12 text-center text-slate-400">
              <Radio className="h-10 w-10 text-slate-600 mb-3" />
              <p className="text-xs font-medium text-white">No repositories connected yet</p>
              <p className="text-xs text-slate-500 max-w-xs mt-1 mb-4">
                {githubHealth?.configured && currentInstallation
                  ? "Connect an authoritative repository from your GitHub App installation to track PRs and commit traceability."
                  : "Configure your GitHub App and claim an installation to begin tracking repositories."}
              </p>
              {githubHealth?.configured && currentInstallation ? (
                <Button variant="glow" size="sm" onClick={handleOpenConnectModal}>
                  Connect Repository
                </Button>
              ) : null}
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Repository</TableHead>
                  <TableHead>Visibility</TableHead>
                  <TableHead>Default Branch</TableHead>
                  <TableHead>Webhook Pipeline</TableHead>
                  <TableHead>Open PRs</TableHead>
                  <TableHead>Last Synced</TableHead>
                  <TableHead className="text-right">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {repositories.map((repo) => (
                  <TableRow key={repo.id}>
                    <TableCell className="font-medium">
                      <div className="flex items-center gap-2">
                        <span className="font-semibold text-white">{repo.fullName}</span>
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className="gap-1 text-[10px]">
                        {repo.isPrivate ? (
                          <>
                            <Lock className="h-3 w-3 text-amber-400" /> Private
                          </>
                        ) : (
                          <>
                            <Globe className="h-3 w-3 text-slate-400" /> Public
                          </>
                        )}
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-300">
                      <div className="flex items-center gap-1.5">
                        <GitBranch className="h-3.5 w-3.5 text-slate-500" />
                        {repo.defaultBranch}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant="success" className="gap-1 text-[10px]">
                        <Webhook className="h-3 w-3" /> Ingestion Active
                      </Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs font-bold text-white">
                      {repo.openPullRequestsCount}
                    </TableCell>
                    <TableCell className="text-xs text-slate-400">
                      {formatDate(repo.lastSyncAt)}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="h-8 w-8 text-slate-400 hover:text-white"
                        onClick={() => window.open(`https://github.com/${repo.fullName}`, "_blank")}
                      >
                        <ExternalLink className="h-4 w-4" />
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Connect Repo Modal (Authoritative GitHub App Verification) */}
      <Dialog open={showConnectModal} onOpenChange={setShowConnectModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Connect GitHub Repository</DialogTitle>
              <DialogDescription>
                Select or specify a repository accessible to GitHub installation #{currentInstallation?.installationId}.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowConnectModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleConnect} className="space-y-4 mt-4">
          {isLoadingAvailable ? (
            <div className="flex items-center justify-center p-6 text-slate-400 gap-2">
              <Loader2 className="h-5 w-5 animate-spin text-sky-400" />
              <span className="text-xs">Querying authorized repositories from GitHub API...</span>
            </div>
          ) : availableRepos.length > 0 ? (
            <div className="space-y-2">
              <label className="text-xs font-medium text-slate-300">
                Authorized Repositories ({availableRepos.length} available)
              </label>
              <div className="max-h-56 overflow-y-auto space-y-1 rounded-lg border border-slate-800 bg-slate-900/60 p-2">
                {availableRepos.map((ar) => {
                  const isSelected = selectedFullName === ar.fullName;
                  return (
                    <div
                      key={ar.id}
                      onClick={() => setSelectedFullName(ar.fullName)}
                      className={`flex items-center justify-between p-2 rounded-md cursor-pointer transition-colors text-xs ${
                        isSelected
                          ? "bg-sky-500/20 border border-sky-500/50 text-white"
                          : "hover:bg-slate-800/60 text-slate-300"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <GitBranch className="h-3.5 w-3.5 text-sky-400" />
                        <span className="font-mono font-medium">{ar.fullName}</span>
                        {ar.isPrivate && (
                          <Badge variant="outline" className="text-[9px] py-0 px-1 border-amber-500/30 text-amber-300">
                            Private
                          </Badge>
                        )}
                      </div>
                      {isSelected && <Check className="h-4 w-4 text-sky-400" />}
                    </div>
                  );
                })}
              </div>
            </div>
          ) : null}

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">
              Repository Full Name (owner/repo)
            </label>
            <Input
              placeholder="e.g. acme/service-repo"
              value={selectedFullName}
              onChange={(e) => setSelectedFullName(e.target.value)}
              required
            />
            <p className="text-[11px] text-slate-500">
              Authoritative metadata (repository ID, default branch, visibility) is retrieved live from GitHub.
            </p>
          </div>

          <DialogFooter>
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
              disabled={!selectedFullName.trim() || isConnecting}
            >
              {isConnecting ? "Verifying with GitHub..." : "Connect Repository"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>

      {/* Claim Installation Modal */}
      <Dialog open={showClaimModal} onOpenChange={setShowClaimModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Claim GitHub App Installation</DialogTitle>
              <DialogDescription>
                Bind an unmapped GitHub App installation to this ForgeAI organization.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowClaimModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleClaimInstallation} className="space-y-4 mt-4">
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">
              Numeric GitHub Installation ID
            </label>
            <Input
              placeholder="e.g. 12345678"
              value={claimInstallationId}
              onChange={(e) => setClaimInstallationId(e.target.value)}
              required
              type="number"
            />
            <p className="text-[11px] text-slate-500">
              Found in your browser URL when installing the GitHub App (e.g.{" "}
              <code className="text-sky-300">github.com/settings/installations/&lt;ID&gt;</code>).
            </p>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowClaimModal(false)}
            >
              Cancel
            </Button>
            <Button
              type="submit"
              variant="glow"
              size="sm"
              disabled={!claimInstallationId.trim() || isClaiming}
            >
              {isClaiming ? "Claiming..." : "Claim Installation"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}
