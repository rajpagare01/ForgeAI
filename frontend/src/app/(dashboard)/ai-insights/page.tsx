"use client";

import React, { useState, useEffect, useCallback } from "react";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import { Repository } from "@/types";
import {
  Sparkles,
  Search,
  Network,
  GitPullRequest,
  Loader2,
  Info,
  GitBranch,
  ShieldCheck,
  ShieldAlert,
  FileCode,
  CheckCircle2,
  AlertTriangle,
  FolderGit2,
  ExternalLink,
  ChevronDown,
  ChevronRight,
  GitCommit,
  Layers,
  Database,
  Cpu,
  ArrowRight,
  FileDiff,
  Code2,
  RefreshCw,
  Activity,
  Sliders,
  TestTube,
  History,
  Boxes,
  FileCheck,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

type LifecycleStep = "idle" | "fetching" | "deriving" | "indexing" | "graph" | "reasoning" | "complete";

export interface RepositorySyncStatus {
  repositoryId: string;
  organizationId: string;
  syncStatus: "SYNCED" | "SYNCING" | "STALE" | "FAILED";
  latestObservedSha?: string;
  latestIndexedSha?: string;
  lastSyncedAt?: string;
  lastError?: string;
  lastDeliveryId?: string;
}

interface PullRequestFile {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  changes: number;
  patch?: string;
  content?: string;
}

interface PullRequestChangeSet {
  repositoryId: string;
  repositoryFullName: string;
  number: number;
  title: string;
  body: string;
  state: string;
  sourceBranch: string;
  targetBranch: string;
  baseSha: string;
  headSha: string;
  author: string;
  additions: number;
  deletions: number;
  changedFilesCount: number;
  files: PullRequestFile[];
}

export interface AnalysisFactor {
  factor: string;
  points: number;
  weight: number;
  raw_value: number;
  detail: string;
}

export interface TestImpactItem {
  test_name: string;
  test_file: string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  reason: string;
}

export interface HistoricalSignalItem {
  file_path: string;
  commit_count: number;
  recent_commits?: string[];
  last_modified?: string;
  authors?: string[];
}

export interface ComponentItem {
  type: string;
  name: string;
  file_path?: string;
}

export interface BlastRadiusItem {
  source_symbol: string;
  target_symbol?: string;
  edge_type: string;
  depth: number;
  file_path?: string;
}

export interface PrAnalysisResponse {
  analysis_id?: string;
  risk?: string;
  risk_level: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  risk_score: number;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  summary: string;
  changed_components?: ComponentItem[];
  direct_impact?: BlastRadiusItem[];
  transitive_impact?: BlastRadiusItem[];
  affected_modules?: string[];
  affected_tests?: TestImpactItem[];
  historical_signals?: HistoricalSignalItem[];
  architectural_concerns?: string[];
  recommendations?: string[];
  risk_factors?: AnalysisFactor[];
  impact_metadata?: {
    deterministic_risk_score?: number;
    risk_level?: string;
    confidence?: string;
    confidence_reason?: string;
    direct_impact_count?: number;
    transitive_impact_count?: number;
    affected_modules_count?: number;
    affected_tests_count?: number;
    historical_signals_count?: number;
    cross_module_impact?: boolean;
  };
  retrieved_chunks?: Array<{
    chunk_id?: string;
    file_path?: string;
    symbol_name?: string;
    symbol_type?: string;
    source: "PR_DIFF" | "GRAPH" | "VECTOR" | "TEST" | "HISTORY" | "SYNC_STATE";
    depth?: number;
    similarity?: number;
    edge_type?: string;
    commit_sha?: string;
    content?: string;
    organization_id?: string;
  }>;
  provider: string;
  model_version: string;
  prompt_version?: string;
  execution_duration_ms: number;
  fallback_used: boolean;
  trace_id?: string;
  created_at?: string;
}

export default function AiInsightsPage() {
  const { currentOrg } = useTenant();
  const [activeTab, setActiveTab] = useState<"pr" | "search" | "impact">("pr");

  // Authorized repositories
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<string>("");
  const [isLoadingRepos, setIsLoadingRepos] = useState<boolean>(true);

  // Repository Continuous Synchronization state
  const [syncStatus, setSyncStatus] = useState<RepositorySyncStatus | null>(null);
  const [isLoadingSyncStatus, setIsLoadingSyncStatus] = useState<boolean>(false);
  const [isReconciling, setIsReconciling] = useState<boolean>(false);

  // Real Pull Requests
  const [pullRequests, setPullRequests] = useState<any[]>([]);
  const [selectedPrNumber, setSelectedPrNumber] = useState<number | "">("");
  const [isLoadingPrs, setIsLoadingPrs] = useState<boolean>(false);

  // Authoritative PR Change Set
  const [prChangeSet, setPrChangeSet] = useState<PullRequestChangeSet | null>(null);
  const [isLoadingChangeSet, setIsLoadingChangeSet] = useState<boolean>(false);

  // PR Analysis & Lifecycle state
  const [lifecycleStep, setLifecycleStep] = useState<LifecycleStep>("idle");
  const [isAnalyzingPr, setIsAnalyzingPr] = useState<boolean>(false);
  const [prAnalysisResult, setPrAnalysisResult] = useState<PrAnalysisResponse | null>(null);
  const [prError, setPrError] = useState<string | null>(null);

  // Advanced override
  const [showAdvancedOverride, setShowAdvancedOverride] = useState<boolean>(false);
  const [manualSymbolsInput, setManualSymbolsInput] = useState<string>("");

  // Semantic search tab state
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  // Impact analysis tab state
  const [impactSymbolsInput, setImpactSymbolsInput] = useState("");
  const [impactResults, setImpactResults] = useState<any[]>([]);
  const [isAnalyzingImpact, setIsAnalyzingImpact] = useState(false);
  const [impactError, setImpactError] = useState<string | null>(null);

  // Provenance filter
  const [provenanceFilter, setProvenanceFilter] = useState<
    "ALL" | "PR_DIFF" | "GRAPH" | "VECTOR" | "TEST" | "HISTORY" | "SYNC_STATE"
  >("ALL");

  // 1. Fetch authorized repositories
  const fetchRepositories = useCallback(async () => {
    setIsLoadingRepos(true);
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
          webhookInstalled: r.webhookInstalled ?? true,
          openPullRequestsCount: r.openPullRequestsCount ?? 0,
          lastSyncAt: r.lastSyncAt || r.updatedAt || new Date().toISOString(),
        }));
        setRepositories(mapped);
        if (mapped.length > 0 && !selectedRepoId) {
          setSelectedRepoId(mapped[0].id);
        }
      }
    } catch (err: any) {
      console.warn("Could not fetch authorized repositories:", err);
    } finally {
      setIsLoadingRepos(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
    fetchRepositories();
  }, [currentOrg.id, fetchRepositories]);

  // Fetch repository sync status
  const fetchSyncStatus = useCallback(async () => {
    if (!selectedRepoId) {
      setSyncStatus(null);
      return;
    }
    setIsLoadingSyncStatus(true);
    try {
      const data = await apiClient.get<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/sync-status`);
      if (data && typeof data === "object") {
        setSyncStatus(data);
      }
    } catch (err: any) {
      console.warn("Could not fetch repository sync status:", err);
      setSyncStatus(null);
    } finally {
      setIsLoadingSyncStatus(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
    fetchSyncStatus();
  }, [selectedRepoId, fetchSyncStatus]);

  const handleReconcile = async () => {
    if (!selectedRepoId) return;
    setIsReconciling(true);
    try {
      const res = await apiClient.post<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/reconcile`, {});
      if (res && typeof res === "object") {
        setSyncStatus(res);
      }
      setTimeout(() => fetchSyncStatus(), 2000);
    } catch (err: any) {
      console.warn("Reconciliation failed:", err);
    } finally {
      setIsReconciling(false);
    }
  };

  // 2. Fetch real pull requests when selected repository changes
  useEffect(() => {
    if (!selectedRepoId) {
      setPullRequests([]);
      setPrChangeSet(null);
      setSelectedPrNumber("");
      return;
    }
    const fetchPrs = async () => {
      setIsLoadingPrs(true);
      try {
        const prs = await apiClient.get<any[]>(`/api/v1/pull-requests?repositoryId=${selectedRepoId}`);
        if (Array.isArray(prs)) {
          setPullRequests(prs);
          if (prs.length > 0) {
            const first = prs[0];
            const num = first.prNumber || first.number || 1;
            setSelectedPrNumber(num);
          } else {
            setSelectedPrNumber("");
            setPrChangeSet(null);
          }
        }
      } catch (err: any) {
        console.warn("Could not fetch pull requests for repo:", err);
        setPullRequests([]);
        setPrChangeSet(null);
      } finally {
        setIsLoadingPrs(false);
      }
    };
    fetchPrs();
  }, [selectedRepoId]);

  // 3. Fetch authoritative change set when selected PR changes
  useEffect(() => {
    if (!selectedRepoId || !selectedPrNumber) {
      setPrChangeSet(null);
      return;
    }
    const fetchChangeSet = async () => {
      setIsLoadingChangeSet(true);
      try {
        const data = await apiClient.get<PullRequestChangeSet>(
          `/api/v1/pull-requests/${selectedPrNumber}/changes?repositoryId=${selectedRepoId}`
        );
        if (data && typeof data === "object") {
          setPrChangeSet(data);
        }
      } catch (err: any) {
        console.warn("Could not fetch authoritative PR change set:", err);
        setPrChangeSet(null);
      } finally {
        setIsLoadingChangeSet(false);
      }
    };
    fetchChangeSet();
  }, [selectedRepoId, selectedPrNumber]);

  // Handle Automatic Real PR Analysis
  const handleAnalyzePr = async () => {
    if (!selectedRepoId || !selectedPrNumber) {
      setPrError("Please select an authorized repository and pull request.");
      return;
    }

    setIsAnalyzingPr(true);
    setPrError(null);
    setPrAnalysisResult(null);

    // Lifecycle animation sequence
    setLifecycleStep("fetching");
    const t1 = setTimeout(() => setLifecycleStep("deriving"), 600);
    const t2 = setTimeout(() => setLifecycleStep("indexing"), 1200);
    const t3 = setTimeout(() => setLifecycleStep("graph"), 1800);
    const t4 = setTimeout(() => setLifecycleStep("reasoning"), 2400);

    try {
      const payload: any = {
        repository_id: selectedRepoId,
        pr_number: Number(selectedPrNumber),
      };

      if (manualSymbolsInput.trim()) {
        payload.changed_symbols = manualSymbolsInput
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
      }

      const result = await apiClient.post<any>("/api/v1/ai/pr-analysis", payload);
      setPrAnalysisResult(result);
      setLifecycleStep("complete");
    } catch (err: any) {
      console.warn("PR analysis failed:", err);
      setPrError(err?.message || "PR Analysis request failed on AI Service.");
      setLifecycleStep("idle");
    } finally {
      clearTimeout(t1);
      clearTimeout(t2);
      clearTimeout(t3);
      clearTimeout(t4);
      setIsAnalyzingPr(false);
    }
  };

  // Search handler
  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchQuery.trim()) return;
    setIsSearching(true);
    setSearchError(null);
    try {
      const results = await apiClient.post<any[]>("/api/v1/ai/search", {
        query: searchQuery.trim(),
        limit: 10,
        repository_id: selectedRepoId || undefined,
      });
      setSearchResults(Array.isArray(results) ? results : []);
    } catch (err: any) {
      setSearchError(err?.message || "Semantic search request failed on AI Service.");
    } finally {
      setIsSearching(false);
    }
  };

  // Impact handler
  const handleImpact = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!impactSymbolsInput.trim() || !selectedRepoId) return;
    setIsAnalyzingImpact(true);
    setImpactError(null);
    try {
      const symbols = impactSymbolsInput.split(",").map((s) => s.trim()).filter(Boolean);
      const results = await apiClient.post<any[]>("/api/v1/ai/impact", {
        repository_id: selectedRepoId,
        changed_symbols: symbols,
        max_depth: 3,
      });
      setImpactResults(Array.isArray(results) ? results : []);
    } catch (err: any) {
      setImpactError(err?.message || "Impact analysis request failed on AI Service.");
    } finally {
      setIsAnalyzingImpact(false);
    }
  };

  const selectedRepo = repositories.find((r) => r.id === selectedRepoId);

  // Filtered provenance chunks
  const filteredChunks = React.useMemo(() => {
    if (!prAnalysisResult || !Array.isArray(prAnalysisResult.retrieved_chunks)) return [];
    if (provenanceFilter === "ALL") return prAnalysisResult.retrieved_chunks;
    return prAnalysisResult.retrieved_chunks.filter((c: any) => c.source === provenanceFilter);
  }, [prAnalysisResult, provenanceFilter]);

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="h-6 w-6 text-purple-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              AI Engineering Intelligence Layer
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Automatic real GitHub PR intelligence, AST static analysis, repository-scoped blast radius, and grounded LLM reasoning for{" "}
            <span className="text-purple-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        {/* Authorized Repository Selector */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 shadow-sm">
            <FolderGit2 className="h-4 w-4 text-purple-400" />
            <span className="text-xs text-slate-400">Repository:</span>
            {isLoadingRepos ? (
              <Loader2 className="h-3 w-3 animate-spin text-purple-400" />
            ) : (
              <select
                aria-label="Authorized Repository Selection"
                value={selectedRepoId}
                onChange={(e) => setSelectedRepoId(e.target.value)}
                className="bg-transparent text-xs text-white font-medium focus:outline-none cursor-pointer"
              >
                {repositories.length === 0 ? (
                  <option value="" disabled className="bg-slate-900 text-slate-400">
                    No repositories connected
                  </option>
                ) : (
                  repositories.map((repo) => (
                    <option key={repo.id} value={repo.id} className="bg-slate-900 text-white">
                      {repo.fullName}
                    </option>
                  ))
                )}
              </select>
            )}
          </div>
          {selectedRepo && (
            <Badge variant="outline" className="text-xs font-mono flex items-center gap-1">
              <GitBranch className="h-3 w-3 text-indigo-400" />
              {selectedRepo.defaultBranch}
            </Badge>
          )}
        </div>
      </div>

      {/* Continuous Repository Synchronization Indicator */}
      {selectedRepo && (
        <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-slate-800 bg-slate-900/50 backdrop-blur-sm text-xs">
          <div className="flex items-center gap-4 flex-wrap">
            <div className="flex items-center gap-2">
              <span className="text-slate-400 font-medium">Sync State:</span>
              {isLoadingSyncStatus && !syncStatus ? (
                <div className="flex items-center gap-1.5 text-xs text-slate-400">
                  <Loader2 className="h-3.5 w-3.5 animate-spin text-purple-400" />
                  <span>Checking...</span>
                </div>
              ) : (
                <span
                  className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold border ${
                    syncStatus?.syncStatus === "SYNCED"
                      ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/30"
                      : syncStatus?.syncStatus === "SYNCING"
                      ? "bg-blue-500/10 text-blue-400 border-blue-500/30 animate-pulse"
                      : syncStatus?.syncStatus === "STALE"
                      ? "bg-amber-500/10 text-amber-400 border-amber-500/30"
                      : syncStatus?.syncStatus === "FAILED"
                      ? "bg-rose-500/10 text-rose-400 border-rose-500/30"
                      : "bg-slate-800 text-slate-400 border-slate-700"
                  }`}
                >
                  {syncStatus?.syncStatus === "SYNCED" && <CheckCircle2 className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus === "SYNCING" && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                  {syncStatus?.syncStatus === "STALE" && <AlertTriangle className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus === "FAILED" && <ShieldAlert className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus || "SYNCED"}
                </span>
              )}
            </div>

            <div className="flex items-center gap-1.5 font-mono text-[11px] bg-slate-950/70 border border-slate-800/80 px-2.5 py-1 rounded-md">
              <span className="text-slate-500">GitHub HEAD:</span>
              <span className="text-slate-300 font-semibold">
                {syncStatus?.latestObservedSha ? syncStatus.latestObservedSha.substring(0, 7) : "None"}
              </span>
            </div>

            <div className="flex items-center gap-1.5 font-mono text-[11px] bg-slate-950/70 border border-slate-800/80 px-2.5 py-1 rounded-md">
              <span className="text-slate-500">Indexed HEAD:</span>
              <span className="text-indigo-400 font-semibold">
                {syncStatus?.latestIndexedSha ? syncStatus.latestIndexedSha.substring(0, 7) : "None"}
              </span>
            </div>

            {syncStatus?.lastSyncedAt && (
              <div className="text-[11px] text-slate-400">
                Last Synced:{" "}
                <span className="text-slate-200 font-medium">
                  {new Date(syncStatus.lastSyncedAt).toLocaleTimeString()}
                </span>
              </div>
            )}

            {syncStatus?.lastError && (
              <span className="text-[11px] text-rose-400 font-medium truncate max-w-sm" title={syncStatus.lastError}>
                Error: {syncStatus.lastError}
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={handleReconcile}
              disabled={isReconciling || isLoadingSyncStatus}
              className="h-7 text-xs px-3 border-indigo-500/30 bg-indigo-950/30 hover:bg-indigo-900/50 text-indigo-200 transition-colors"
            >
              {isReconciling ? (
                <>
                  <Loader2 className="h-3 w-3 animate-spin mr-1.5 text-indigo-400" />
                  Reconciling...
                </>
              ) : (
                <>
                  <RefreshCw className="h-3 w-3 mr-1.5 text-indigo-400" />
                  Reconcile
                </>
              )}
            </Button>
          </div>
        </div>
      )}

      {/* Navigation Tabs */}
      <div className="flex items-center gap-2 border-b border-slate-800 pb-2">
        <button
          onClick={() => setActiveTab("pr")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "pr"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <GitPullRequest className="h-4 w-4" />
          Automatic PR Intelligence
        </button>
        <button
          onClick={() => setActiveTab("search")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "search"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Search className="h-4 w-4" />
          Semantic Vector Search (pgvector)
        </button>
        <button
          onClick={() => setActiveTab("impact")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "impact"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Network className="h-4 w-4" />
          AST Call Graph Blast Radius
        </button>
      </div>

      {/* ========================================================================= */}
      {/* TAB 1: AUTOMATIC REAL GITHUB PULL REQUEST INTELLIGENCE                    */}
      {/* ========================================================================= */}
      {activeTab === "pr" && (
        <div className="space-y-6">
          {/* PR Selector & Metadata Header */}
          <Card className="border-indigo-500/20 bg-slate-900/60">
            <CardHeader className="pb-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <CardTitle className="text-base text-white flex items-center gap-2">
                    <GitPullRequest className="h-5 w-5 text-indigo-400" />
                    Authoritative GitHub Pull Request
                  </CardTitle>
                  <CardDescription className="text-xs">
                    Select a real GitHub pull request to automatically obtain change sets, statically derive symbols, and compute blast radius.
                  </CardDescription>
                </div>

                {/* PR Dropdown */}
                <div className="flex items-center gap-2">
                  {isLoadingPrs ? (
                    <div className="flex items-center gap-2 text-xs text-slate-400 py-1.5 px-3 bg-slate-950 border border-slate-800 rounded-lg">
                      <Loader2 className="h-3 w-3 animate-spin text-purple-400" />
                      Loading PRs...
                    </div>
                  ) : pullRequests.length > 0 ? (
                    <select
                      aria-label="Authoritative Pull Request Selection"
                      value={selectedPrNumber}
                      onChange={(e) => setSelectedPrNumber(Number(e.target.value) || "")}
                      className="bg-slate-950 border border-slate-800 text-xs text-white rounded-lg px-3 py-2 focus:ring-1 focus:ring-indigo-500 focus:outline-none cursor-pointer max-w-xs truncate"
                    >
                      {pullRequests.map((pr) => (
                        <option key={pr.id || pr.number} value={pr.prNumber || pr.number}>
                          #{pr.prNumber || pr.number}: {pr.title}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <div className="text-xs text-slate-500 italic bg-slate-950 px-3 py-1.5 rounded-lg border border-slate-800">
                      No pull requests synchronized for this repository
                    </div>
                  )}
                </div>
              </div>
            </CardHeader>

            {/* Authoritative Changeset Details Card */}
            <CardContent className="space-y-4 pt-0">
              {isLoadingChangeSet ? (
                <div className="flex items-center justify-center p-6 text-xs text-slate-400">
                  <Loader2 className="h-4 w-4 animate-spin text-indigo-400 mr-2" />
                  Fetching authoritative change set from GitHub / Source Control...
                </div>
              ) : prChangeSet ? (
                <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-4 space-y-4">
                  <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-white">
                          #{prChangeSet.number}: {prChangeSet.title}
                        </span>
                        <Badge
                          variant={prChangeSet.state === "OPEN" ? "success" : "outline"}
                          className="text-[10px]"
                        >
                          {prChangeSet.state}
                        </Badge>
                      </div>
                      <div className="flex items-center gap-2 text-xs text-slate-400 mt-1 font-mono">
                        <GitBranch className="h-3 w-3 text-indigo-400" />
                        <span>{prChangeSet.sourceBranch || "head"}</span>
                        <ArrowRight className="h-3 w-3 text-slate-600" />
                        <span>{prChangeSet.targetBranch || "main"}</span>
                        {prChangeSet.headSha && (
                          <span className="text-[10px] text-slate-500 ml-2">
                            (head: {prChangeSet.headSha.substring(0, 7)})
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Stats pills */}
                    <div className="flex items-center gap-2 text-xs font-mono">
                      <span className="px-2.5 py-1 rounded-md bg-slate-900 border border-slate-800 text-slate-300">
                        {prChangeSet.changedFilesCount || prChangeSet.files?.length || 0} files changed
                      </span>
                      <span className="px-2.5 py-1 rounded-md bg-emerald-950/50 border border-emerald-800/40 text-emerald-400">
                        +{prChangeSet.additions || 0}
                      </span>
                      <span className="px-2.5 py-1 rounded-md bg-rose-950/50 border border-rose-800/40 text-rose-400">
                        -{prChangeSet.deletions || 0}
                      </span>
                    </div>
                  </div>

                  {/* Changed Files Chips */}
                  {prChangeSet.files && prChangeSet.files.length > 0 && (
                    <div className="space-y-1.5">
                      <div className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
                        Authoritative Changed Files ({prChangeSet.files.length})
                      </div>
                      <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto">
                        {prChangeSet.files.map((file, idx) => (
                          <div
                            key={idx}
                            className="flex items-center gap-1.5 px-2 py-1 rounded border border-slate-800 bg-slate-900 text-[11px] font-mono text-slate-300"
                          >
                            <FileCode className="h-3 w-3 text-indigo-400" />
                            <span className="truncate max-w-xs">{file.filename}</span>
                            <span
                              className={`text-[9px] px-1 rounded uppercase font-bold ${
                                file.status === "added"
                                  ? "text-emerald-400 bg-emerald-950/80"
                                  : file.status === "removed"
                                  ? "text-rose-400 bg-rose-950/80"
                                  : "text-amber-400 bg-amber-950/80"
                              }`}
                            >
                              {file.status}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Primary Action Button */}
                  <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-2">
                    <Button
                      onClick={handleAnalyzePr}
                      disabled={isAnalyzingPr || !selectedRepoId || !selectedPrNumber}
                      variant="glow"
                      className="w-full sm:w-auto px-6 py-2.5 text-xs font-semibold flex items-center gap-2"
                    >
                      {isAnalyzingPr ? (
                        <>
                          <Loader2 className="h-4 w-4 animate-spin text-white" />
                          Running Real PR Intelligence Pipeline...
                        </>
                      ) : (
                        <>
                          <Sparkles className="h-4 w-4 text-purple-200" />
                          Analyze Pull Request
                        </>
                      )}
                    </Button>

                    {/* Advanced toggle */}
                    <button
                      type="button"
                      onClick={() => setShowAdvancedOverride(!showAdvancedOverride)}
                      className="text-xs text-slate-400 hover:text-white flex items-center gap-1"
                    >
                      {showAdvancedOverride ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                      Advanced Override (Manual Symbols)
                    </button>
                  </div>

                  {/* Collapsible Advanced Override Section */}
                  {showAdvancedOverride && (
                    <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/60 space-y-2 mt-2">
                      <div className="text-[11px] text-slate-400">
                        Optional: Override static AST symbol derivation with manual symbols for debugging.
                      </div>
                      <Input
                        placeholder="e.g. RepositoryApplicationService#getPullRequestChanges, TokenRotationService"
                        value={manualSymbolsInput}
                        onChange={(e) => setManualSymbolsInput(e.target.value)}
                        className="text-xs font-mono"
                      />
                    </div>
                  )}
                </div>
              ) : null}

              {prError && (
                <div className="p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-rose-400 shrink-0" />
                  <span>{prError}</span>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Lifecycle Execution Pipeline Indicator */}
          {isAnalyzingPr && (
            <Card className="border-indigo-500/30 bg-slate-900/80 p-5">
              <div className="text-xs font-semibold text-white mb-3 flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin text-purple-400" />
                Live PR Intelligence Pipeline Progression:
              </div>
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2 text-[11px]">
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "fetching"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <GitPullRequest className="h-3 w-3 shrink-0" />
                  <span>1. Authoritative PR</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "deriving"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Code2 className="h-3 w-3 shrink-0" />
                  <span>2. Tree-sitter AST</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "indexing"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Database className="h-3 w-3 shrink-0" />
                  <span>3. Durable Indexing</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "graph"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Network className="h-3 w-3 shrink-0" />
                  <span>4. Graph CTE</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "reasoning"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Cpu className="h-3 w-3 shrink-0" />
                  <span>5. Gemini Reasoning</span>
                </div>
              </div>
            </Card>
          )}

          {/* Analysis Results */}
          {prAnalysisResult && (
            <div className="space-y-6">
              <Card className="border-purple-500/30 bg-purple-950/10 shadow-xl">
                <CardHeader className="pb-4">
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div className="flex items-center gap-3 flex-wrap">
                      <div className="flex items-center gap-2">
                        <ShieldCheck className="h-6 w-6 text-purple-400" />
                        <CardTitle className="text-lg text-white">
                          Engineering Impact & Risk Intelligence
                        </CardTitle>
                      </div>

                      {/* Overall Risk Badge */}
                      <Badge
                        variant="outline"
                        className={`text-xs font-bold px-3 py-1 uppercase tracking-wider border ${
                          prAnalysisResult.risk_level === "LOW"
                            ? "bg-emerald-500/15 text-emerald-400 border-emerald-500/40"
                            : prAnalysisResult.risk_level === "MEDIUM"
                            ? "bg-amber-500/15 text-amber-400 border-amber-500/40"
                            : prAnalysisResult.risk_level === "HIGH"
                            ? "bg-orange-500/15 text-orange-400 border-orange-500/40"
                            : "bg-rose-500/15 text-rose-400 border-rose-500/40 animate-pulse"
                        }`}
                      >
                        Risk: {prAnalysisResult.risk_level}
                      </Badge>

                      {/* Deterministic Risk Score */}
                      <div className="flex items-center gap-1.5 px-3 py-1 rounded-md bg-slate-900 border border-slate-800 text-xs font-mono">
                        <Activity className="h-3.5 w-3.5 text-indigo-400" />
                        <span className="text-slate-400">Score:</span>
                        <span className="font-bold text-white">
                          {typeof prAnalysisResult.risk_score === "number"
                            ? prAnalysisResult.risk_score.toFixed(1)
                            : "0.0"}
                        </span>
                        <span className="text-slate-500">/ 100</span>
                      </div>

                      {/* Evidence Confidence */}
                      <Badge
                        variant="outline"
                        className={`text-xs font-mono px-2.5 py-1 ${
                          prAnalysisResult.confidence === "HIGH"
                            ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                            : prAnalysisResult.confidence === "MEDIUM"
                            ? "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                            : "text-amber-400 border-amber-500/40 bg-amber-950/20"
                        }`}
                        title={prAnalysisResult.impact_metadata?.confidence_reason}
                      >
                        Confidence: {prAnalysisResult.confidence}
                      </Badge>
                    </div>

                    {/* Transparency Badges */}
                    <div className="flex items-center gap-2 flex-wrap">
                      <Badge variant="outline" className="font-mono text-xs">
                        Provider:{" "}
                        <span className="text-purple-300 font-bold ml-1">
                          {prAnalysisResult.provider === "GROQ"
                            ? "Groq Cloud"
                            : prAnalysisResult.provider === "GEMINI"
                            ? "Google Gemini"
                            : prAnalysisResult.provider}
                        </span>
                      </Badge>
                      {prAnalysisResult.fallback_used ? (
                        <Badge variant="destructive" className="font-mono text-xs animate-pulse">
                          Fallback: Yes (Groq)
                        </Badge>
                      ) : (
                        <Badge variant="outline" className="font-mono text-xs text-emerald-400 border-emerald-500/40">
                          Primary: Gemini
                        </Badge>
                      )}
                    </div>
                  </div>

                  <CardDescription className="text-xs font-mono pt-2 flex flex-wrap items-center gap-x-4 gap-y-1">
                    <span>Model: {prAnalysisResult.model_version}</span>
                    <span>Duration: {prAnalysisResult.execution_duration_ms}ms</span>
                    {prAnalysisResult.impact_metadata?.confidence_reason && (
                      <span className="text-slate-400 font-sans italic">
                        Evidence: {prAnalysisResult.impact_metadata.confidence_reason}
                      </span>
                    )}
                    {prAnalysisResult.trace_id && (
                      <span>Trace: {prAnalysisResult.trace_id.substring(0, 16)}...</span>
                    )}
                  </CardDescription>
                </CardHeader>

                <CardContent className="space-y-6 pt-2">
                  {/* Insufficient context warning callout if applicable */}
                  {prAnalysisResult.summary?.includes("INSUFFICIENT_CONTEXT") && (
                    <div className="p-4 rounded-xl border border-amber-500/30 bg-amber-950/30 text-xs text-amber-200 flex items-start gap-3">
                      <AlertTriangle className="h-5 w-5 text-amber-400 shrink-0 mt-0.5" />
                      <div>
                        <div className="font-bold text-amber-100">Grounded Evidence Warning: Insufficient Context</div>
                        <div>
                          No indexed code chunks or knowledge graph edges matched this repository. ForgeAI does not fabricate facts.
                          Please ensure the repository has been indexed into ForgeAI before performing deep impact analysis.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* 1. Deterministic Risk Factors Breakdown */}
                  {Array.isArray(prAnalysisResult.risk_factors) && prAnalysisResult.risk_factors.length > 0 && (
                    <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Sliders className="h-4 w-4 text-purple-400" />
                          Deterministic Risk Factors Breakdown
                        </span>
                        <span className="text-[11px] font-mono text-slate-400">
                          Score: {prAnalysisResult.risk_score.toFixed(1)} / 100
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
                        {prAnalysisResult.risk_factors.map((rf, idx) => (
                          <div
                            key={idx}
                            className="p-3 rounded-lg border border-slate-800/80 bg-slate-900/60 text-xs flex flex-col justify-between gap-1.5"
                          >
                            <div className="flex items-center justify-between">
                              <span className="font-semibold text-slate-200">{rf.factor}</span>
                              <Badge
                                variant="outline"
                                className={`text-[10px] font-mono ${
                                  rf.points > 10
                                    ? "text-rose-400 border-rose-500/40 bg-rose-950/20"
                                    : rf.points > 0
                                    ? "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                    : "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                }`}
                              >
                                +{rf.points} pts
                              </Badge>
                            </div>
                            <p className="text-[11px] text-slate-400 leading-snug">{rf.detail}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 2. Changed Components & Affected Modules */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Changed Components */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                        <FileCode className="h-4 w-4 text-indigo-400" />
                        Directly Changed Components
                      </div>
                      {prAnalysisResult.changed_components && prAnalysisResult.changed_components.length > 0 ? (
                        <div className="flex flex-wrap gap-1.5 max-h-40 overflow-y-auto">
                          {prAnalysisResult.changed_components.map((comp, idx) => (
                            <div
                              key={idx}
                              className="px-2 py-1 rounded border border-slate-800 bg-slate-900 text-[11px] font-mono text-slate-300 flex items-center gap-1.5"
                            >
                              <Badge variant="outline" className="text-[9px] font-mono text-indigo-400 px-1 py-0">
                                {comp.type}
                              </Badge>
                              <span className="truncate max-w-xs">{comp.name}</span>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No specific symbols identified</div>
                      )}
                    </div>

                    {/* Affected Modules */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Boxes className="h-4 w-4 text-purple-400" />
                          Architectural Areas & Modules
                        </span>
                        {prAnalysisResult.impact_metadata?.cross_module_impact && (
                          <Badge variant="destructive" className="text-[10px] uppercase font-mono">
                            Cross-Module Impact
                          </Badge>
                        )}
                      </div>
                      {prAnalysisResult.affected_modules && prAnalysisResult.affected_modules.length > 0 ? (
                        <div className="flex flex-wrap gap-2">
                          {prAnalysisResult.affected_modules.map((mod, idx) => (
                            <Badge
                              key={idx}
                              variant="outline"
                              className="text-xs font-mono px-2.5 py-1 bg-purple-950/20 text-purple-300 border-purple-500/30"
                            >
                              {mod}
                            </Badge>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No specific architectural modules isolated</div>
                      )}
                    </div>
                  </div>

                  {/* 3. Multi-Hop Blast Radius (Direct vs Transitive) */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Direct Impact (Depth = 1) */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-4 w-4 text-emerald-400" />
                          Direct Blast Radius (Depth 1)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {prAnalysisResult.direct_impact?.length || 0} callers
                        </Badge>
                      </div>
                      {prAnalysisResult.direct_impact && prAnalysisResult.direct_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.direct_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-slate-800/80 bg-slate-900/60 text-xs font-mono flex items-center justify-between"
                            >
                              <span className="text-emerald-300 font-medium truncate max-w-xs">
                                {item.source_symbol}
                              </span>
                              <Badge variant="outline" className="text-[9px] text-slate-400">
                                {item.edge_type || "DEPENDS_ON"}
                              </Badge>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No direct downstream dependents found</div>
                      )}
                    </div>

                    {/* Transitive Impact (Depth = 2, 3) */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-4 w-4 text-indigo-400" />
                          Transitive Blast Radius (Depth 2-3)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {prAnalysisResult.transitive_impact?.length || 0} callers
                        </Badge>
                      </div>
                      {prAnalysisResult.transitive_impact && prAnalysisResult.transitive_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.transitive_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-slate-800/80 bg-slate-900/60 text-xs font-mono flex items-center justify-between"
                            >
                              <div className="flex items-center gap-2 truncate">
                                <Badge variant="outline" className="text-[9px] text-indigo-400 border-indigo-500/40">
                                  {item.depth} hops
                                </Badge>
                                <span className="text-slate-300 truncate max-w-xs">{item.source_symbol}</span>
                              </div>
                              <span className="text-[10px] text-slate-500">{item.edge_type}</span>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No multi-hop transitive dependents found</div>
                      )}
                    </div>
                  </div>

                  {/* 4. Evidence-Backed Test Impact Intelligence */}
                  {Array.isArray(prAnalysisResult.affected_tests) && prAnalysisResult.affected_tests.length > 0 && (
                    <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <TestTube className="h-4 w-4 text-blue-400" />
                          Recommended Tests (Evidence-Backed Impact)
                        </span>
                        <span className="text-[11px] font-mono text-slate-400">
                          {prAnalysisResult.affected_tests.length} tests identified
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 gap-2.5">
                        {prAnalysisResult.affected_tests.map((test, idx) => (
                          <div
                            key={idx}
                            className="p-3 rounded-lg border border-slate-800 bg-slate-900/70 text-xs flex flex-col gap-1.5"
                          >
                            <div className="flex items-center justify-between">
                              <span className="font-mono text-white font-semibold truncate max-w-xs">
                                {test.test_name}
                              </span>
                              <Badge
                                variant="outline"
                                className={`text-[9px] font-bold ${
                                  test.confidence === "HIGH"
                                    ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                    : test.confidence === "MEDIUM"
                                    ? "text-blue-400 border-blue-500/40 bg-blue-950/20"
                                    : "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                }`}
                              >
                                {test.confidence} CONFIDENCE
                              </Badge>
                            </div>
                            <div className="text-[11px] text-slate-400 font-mono truncate">{test.test_file}</div>
                            <div className="text-[10px] text-slate-500 italic border-t border-slate-800/80 pt-1">
                              Evidence: {test.reason}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 5. Historical Change Signals */}
                  {Array.isArray(prAnalysisResult.historical_signals) && prAnalysisResult.historical_signals.length > 0 && (
                    <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <History className="h-4 w-4 text-amber-400" />
                          Historical Change Signals (Commit Churn Evidence)
                        </span>
                        <span className="text-[11px] font-mono text-slate-400">
                          {prAnalysisResult.historical_signals.length} files tracked
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
                        {prAnalysisResult.historical_signals.map((sig, idx) => (
                          <div
                            key={idx}
                            className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60 text-xs font-mono flex flex-col gap-1"
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-slate-300 truncate max-w-[180px]">{sig.file_path}</span>
                              <Badge variant="outline" className="text-[10px] text-amber-400 border-amber-500/30">
                                {sig.commit_count} prior commits
                              </Badge>
                            </div>
                            {sig.recent_commits && sig.recent_commits.length > 0 && (
                              <div className="text-[10px] text-slate-500 truncate">
                                Latest: {sig.recent_commits[0]}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 6. Architectural Concerns Alert */}
                  {Array.isArray(prAnalysisResult.architectural_concerns) && prAnalysisResult.architectural_concerns.length > 0 && (
                    <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-950/20 text-xs text-rose-200 space-y-2">
                      <div className="font-bold text-rose-100 flex items-center gap-2">
                        <AlertTriangle className="h-4 w-4 text-rose-400" />
                        Architectural Concerns Flagged
                      </div>
                      <ul className="list-disc list-inside space-y-1 text-rose-300">
                        {prAnalysisResult.architectural_concerns.map((concern, idx) => (
                          <li key={idx}>{concern}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {/* 7. Grounded AI Reasoning Review Body */}
                  <div className="space-y-2">
                    <div className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                      <Cpu className="h-4 w-4 text-purple-400" />
                      Grounded LLM Reasoning (Facts, Inferences & Recommendations)
                    </div>
                    <div className="p-5 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-200 leading-relaxed whitespace-pre-line font-sans shadow-inner">
                      {prAnalysisResult.summary}
                    </div>
                  </div>

                  {/* 8. Grounded Provenance Explorer (Multi-Category) */}
                  {Array.isArray(prAnalysisResult.retrieved_chunks) && prAnalysisResult.retrieved_chunks.length > 0 && (
                    <div className="space-y-3 pt-2 border-t border-slate-800/80">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                        <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Layers className="h-3.5 w-3.5 text-purple-400" />
                          Evidence & Provenance Explorer ({prAnalysisResult.retrieved_chunks.length} records)
                        </h4>

                        {/* Filter pills */}
                        <div className="flex items-center gap-1 text-[11px] flex-wrap">
                          {(
                            [
                              "ALL",
                              "PR_DIFF",
                              "GRAPH",
                              "VECTOR",
                              "TEST",
                              "HISTORY",
                              "SYNC_STATE",
                            ] as const
                          ).map((filter) => (
                            <button
                              key={filter}
                              onClick={() => setProvenanceFilter(filter)}
                              className={`px-2.5 py-1 rounded text-[10px] font-mono transition-colors ${
                                provenanceFilter === filter
                                  ? "bg-purple-600 text-white font-bold"
                                  : "bg-slate-900 text-slate-400 hover:text-white"
                              }`}
                            >
                              {filter}
                            </button>
                          ))}
                        </div>
                      </div>

                      <div className="grid grid-cols-1 gap-2 max-h-80 overflow-y-auto">
                        {filteredChunks.map((chunk: any, i: number) => (
                          <div
                            key={i}
                            className="p-3 rounded-lg border border-slate-800 bg-slate-950 text-xs font-mono flex flex-col gap-1.5"
                          >
                            <div className="flex items-center justify-between flex-wrap gap-2">
                              <div className="flex items-center gap-2">
                                <Badge
                                  variant="outline"
                                  className={`text-[9px] font-bold ${
                                    chunk.source === "PR_DIFF"
                                      ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                      : chunk.source === "GRAPH"
                                      ? "text-purple-400 border-purple-500/40 bg-purple-950/20"
                                      : chunk.source === "TEST"
                                      ? "text-blue-400 border-blue-500/40 bg-blue-950/20"
                                      : chunk.source === "HISTORY"
                                      ? "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                      : chunk.source === "SYNC_STATE"
                                      ? "text-cyan-400 border-cyan-500/40 bg-cyan-950/20"
                                      : "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                                  }`}
                                >
                                  {chunk.source || "VECTOR"}
                                </Badge>
                                <span className="text-white font-semibold truncate max-w-sm">
                                  {chunk.file_path || chunk.symbol_name || "Repository Artifact"}
                                </span>
                              </div>

                              {chunk.similarity !== undefined && (
                                <span className="text-[10px] text-slate-400">
                                  Similarity: {(chunk.similarity * 100).toFixed(1)}%
                                </span>
                              )}
                              {chunk.depth !== undefined && (
                                <span className="text-[10px] text-slate-400">
                                  Depth: {chunk.depth} hops ({chunk.edge_type})
                                </span>
                              )}
                            </div>

                            {chunk.content && (
                              <div className="text-[11px] text-slate-400 bg-slate-900/80 p-2 rounded truncate">
                                {chunk.content}
                              </div>
                            )}

                            <div className="flex items-center justify-between text-[10px] text-slate-500 pt-1 border-t border-slate-900">
                              <span>
                                Symbol:{" "}
                                <code className="text-slate-400">
                                  {chunk.symbol_name || chunk.file_path}
                                </code>
                              </span>
                              {chunk.commit_sha && (
                                <span>
                                  Commit:{" "}
                                  <code className="text-slate-400">
                                    {chunk.commit_sha.substring(0, 7)}
                                  </code>
                                </span>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 2: SEMANTIC VECTOR SEARCH                                             */}
      {/* ========================================================================= */}
      {activeTab === "search" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Natural Language Code Search</CardTitle>
              <CardDescription>
                Search across indexed repositories using pgvector cosine embeddings. Strict tenant isolation enforced for{" "}
                <span className="text-purple-300">{currentOrg.name}</span>.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSearch} className="flex gap-2">
                <Input
                  placeholder="e.g. rate limiter token bucket redis filter, webhook signature verification"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="flex-1"
                />
                <Button type="submit" variant="glow" disabled={isSearching || !searchQuery.trim()}>
                  {isSearching ? <Loader2 className="h-4 w-4 animate-spin" /> : "Search Code"}
                </Button>
              </form>

              {searchError && (
                <div className="mt-4 p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {searchError}
                </div>
              )}
            </CardContent>
          </Card>

          {searchResults.length > 0 && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                  Matching Code Chunks ({searchResults.length})
                </h3>
                {selectedRepo && (
                  <span className="text-[11px] text-slate-500 font-mono">
                    Scoped to {selectedRepo.fullName}
                  </span>
                )}
              </div>
              {searchResults.map((chunk, idx) => (
                <div
                  key={chunk.id || idx}
                  className="rounded-xl border border-slate-800 bg-slate-900/80 p-4 space-y-2"
                >
                  <div className="flex items-center justify-between text-xs">
                    <div className="flex items-center gap-2">
                      <FileCode className="h-4 w-4 text-indigo-400" />
                      <span className="font-mono text-indigo-300 font-semibold">{chunk.file_path}</span>
                      {chunk.symbol_name && (
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {chunk.symbol_type}: {chunk.symbol_name}
                        </Badge>
                      )}
                    </div>
                    <Badge variant="outline" className="font-mono text-[10px]">
                      Similarity: {((chunk.similarity || (1 - (chunk.distance || 0))) * 100).toFixed(1)}%
                    </Badge>
                  </div>
                  <pre className="text-xs font-mono bg-slate-950 p-3 rounded-lg text-slate-300 overflow-x-auto border border-slate-800/60 max-h-60">
                    {chunk.content}
                  </pre>
                  <div className="flex items-center justify-between text-[10px] text-slate-500 pt-1">
                    <span>Commit: <code className="text-slate-400 font-mono">{chunk.commit_sha?.substring(0, 10)}</code></span>
                    <span>Tenant ID: <code className="text-slate-400 font-mono">{chunk.organization_id}</code></span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 3: GRAPH IMPACT ANALYSIS                                              */}
      {/* ========================================================================= */}
      {activeTab === "impact" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Recursive AST Blast Radius</CardTitle>
              <CardDescription>
                Compute caller/callee trees using recursive Common Table Expressions over the knowledge graph.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleImpact} className="space-y-4">
                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">
                    Target Symbols (comma separated)
                  </label>
                  <Input
                    placeholder="e.g. JwtRevocationGatewayFilter, RedisRevocationStore, WebhookIngestionService"
                    value={impactSymbolsInput}
                    onChange={(e) => setImpactSymbolsInput(e.target.value)}
                  />
                </div>
                <Button type="submit" variant="glow" disabled={isAnalyzingImpact || !impactSymbolsInput.trim()}>
                  {isAnalyzingImpact ? <Loader2 className="h-4 w-4 animate-spin" /> : "Compute Blast Radius"}
                </Button>
              </form>

              {impactError && (
                <div className="mt-4 p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {impactError}
                </div>
              )}
            </CardContent>
          </Card>

          {impactResults.length > 0 && (
            <div className="space-y-3">
              <h3 className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                Impacted Upstream Dependencies ({impactResults.length})
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {impactResults.map((item, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-xs"
                  >
                    <div>
                      <div className="font-mono text-white font-semibold">{item.source_symbol}</div>
                      <div className="text-[10px] text-slate-500">Depth: {item.depth ?? 1} hops</div>
                    </div>
                    <Badge variant="outline" className="text-[10px] font-mono">
                      {item.edge_type || "DEPENDS_ON"}
                    </Badge>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
