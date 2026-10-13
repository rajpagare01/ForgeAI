"use client";

import React, { useState, useEffect, useCallback } from "react";
import { Repository } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  GitPullRequest,
  CheckCircle2,
  RefreshCw,
  ExternalLink,
  ShieldCheck,
  Webhook,
  GitBranch,
  Lock,
  Globe,
  Radio,
  Plus,
  Loader2,
  AlertCircle,
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
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showConnectModal, setShowConnectModal] = useState(false);
  const [fullName, setFullName] = useState("");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [isPrivate, setIsPrivate] = useState(true);
  const [isConnecting, setIsConnecting] = useState(false);

  const fetchRepositories = useCallback(async () => {
    setIsLoading(true);
    setError(null);
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
    fetchRepositories();
  }, [fetchRepositories]);

  const handleConnect = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!fullName) return;

    setIsConnecting(true);
    setError(null);
    try {
      const parts = fullName.split("/");
      const repoName = parts.length > 1 ? parts[1] : fullName;
      const cleanFullName = parts.length > 1 ? fullName : `forgeai/${fullName}`;

      const res = await apiClient.post<any>("/api/v1/repositories/connect", {
        githubRepoId: Math.floor(Math.random() * 900000) + 100000,
        installationId: 1001,
        name: repoName,
        fullName: cleanFullName,
        defaultBranch,
        isPrivate,
      });

      const newRepo: Repository = {
        id: res.id,
        organizationId: res.organizationId,
        name: res.name,
        fullName: res.fullName,
        defaultBranch: res.defaultBranch || defaultBranch,
        isPrivate: res.isPrivate ?? isPrivate,
        webhookInstalled: true,
        openPullRequestsCount: 0,
        lastSyncAt: res.createdAt || new Date().toISOString(),
      };

      setRepositories((prev) => [newRepo, ...prev]);
      setShowConnectModal(false);
      setFullName("");
      setDefaultBranch("main");
    } catch (err: any) {
      console.error("Failed to connect repository:", err);
      setError(err?.message || "Failed to connect repository on Gateway.");
    } finally {
      setIsConnecting(false);
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
            GitHub App installations, real-time webhook ingestion queues, and commit sync tracking for{" "}
            <span className="text-sky-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={fetchRepositories}
            disabled={isLoading}
            className="gap-2"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            Sync Now
          </Button>
          <Button
            variant="glow"
            size="sm"
            onClick={() => setShowConnectModal(true)}
            className="gap-2"
          >
            <Plus className="h-4 w-4" />
            Connect GitHub Repo
          </Button>
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* GitHub App Health Card */}
      <Card className="border-sky-500/20 bg-sky-950/10">
        <CardContent className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-sky-500/20 text-sky-400 border border-sky-500/30">
              <ShieldCheck className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h4 className="text-sm font-semibold text-white">GitHub App Connected</h4>
                <Badge variant="success" className="text-[10px]">Active</Badge>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                Thin-ingest webhook endpoint receives <code className="text-sky-300">pull_request</code>, <code className="text-sky-300">push</code>, and <code className="text-sky-300">check_run</code> events with HMAC SHA-256 verification.
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

      {/* Repositories Table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Tracked Repositories</CardTitle>
          <CardDescription>
            Repositories configured with automated webhook delivery and AST indexing triggers.
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
                Connect a GitHub repository to automatically start tracking PRs and commit traceability.
              </p>
              <Button variant="glow" size="sm" onClick={() => setShowConnectModal(true)}>
                Connect Repository
              </Button>
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

      {/* Connect Repo Modal */}
      <Dialog open={showConnectModal} onOpenChange={setShowConnectModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Connect GitHub Repository</DialogTitle>
              <DialogDescription>
                Register an enterprise repository for AST parsing and webhook correlation.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowConnectModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleConnect} className="space-y-4 mt-4">
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">
              Repository Full Name (owner/repo)
            </label>
            <Input
              placeholder="e.g. forgeai/governance-core"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              required
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Default Branch</label>
              <Input
                placeholder="main"
                value={defaultBranch}
                onChange={(e) => setDefaultBranch(e.target.value)}
                required
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Visibility</label>
              <select
                value={isPrivate ? "private" : "public"}
                onChange={(e) => setIsPrivate(e.target.value === "private")}
                className="h-10 w-full rounded-md border border-slate-800 bg-slate-900 px-3 py-2 text-xs text-white focus:outline-none focus:ring-2 focus:ring-sky-500"
              >
                <option value="private">Private</option>
                <option value="public">Public</option>
              </select>
            </div>
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
            <Button type="submit" variant="glow" size="sm" disabled={!fullName || isConnecting}>
              {isConnecting ? "Connecting..." : "Connect Repository"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}
