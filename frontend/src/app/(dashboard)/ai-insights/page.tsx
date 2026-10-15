"use client";

import React, { useState, useEffect, useCallback } from "react";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import { Repository, PullRequest } from "@/types";
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
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function AiInsightsPage() {
  const { currentOrg } = useTenant();
  const [activeTab, setActiveTab] = useState<"search" | "impact" | "pr">("search");

  // Authorized repositories belonging to current organization
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<string>("");
  const [isLoadingRepos, setIsLoadingRepos] = useState<boolean>(true);

  // Real Pull Requests for selected repository
  const [pullRequests, setPullRequests] = useState<any[]>([]);
  const [selectedPrNumber, setSelectedPrNumber] = useState<number | "">("");
  const [isLoadingPrs, setIsLoadingPrs] = useState<boolean>(false);

  // Semantic search state
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  // Impact analysis state
  const [symbolsInput, setSymbolsInput] = useState("");
  const [impactResults, setImpactResults] = useState<any[]>([]);
  const [isAnalyzingImpact, setIsAnalyzingImpact] = useState(false);
  const [impactError, setImpactError] = useState<string | null>(null);

  // PR analysis state
  const [prTitle, setPrTitle] = useState("");
  const [prDescription, setPrDescription] = useState("");
  const [prAnalysisResult, setPrAnalysisResult] = useState<any | null>(null);
  const [isAnalyzingPr, setIsAnalyzingPr] = useState(false);
  const [prError, setPrError] = useState<string | null>(null);

  // 1. Fetch authorized repositories for the authenticated organization
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

  // 2. Fetch real pull requests when selected repository changes
  useEffect(() => {
    if (!selectedRepoId) {
      setPullRequests([]);
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
            setSelectedPrNumber(first.prNumber || first.number || 1);
            setPrTitle(first.title || "");
            setPrDescription(first.body || first.description || "Production Pull Request Assessment");
          } else {
            setSelectedPrNumber("");
            setPrTitle("");
            setPrDescription("");
          }
        }
      } catch (err: any) {
        console.warn("Could not fetch pull requests for repo:", err);
        setPullRequests([]);
      } finally {
        setIsLoadingPrs(false);
      }
    };
    fetchPrs();
  }, [selectedRepoId]);

  // Handle PR select dropdown
  const handleSelectPr = (prNumStr: string) => {
    if (!prNumStr) {
      setSelectedPrNumber("");
      return;
    }
    const num = Number(prNumStr);
    setSelectedPrNumber(num);
    const found = pullRequests.find((p) => (p.prNumber || p.number) === num);
    if (found) {
      setPrTitle(found.title || "");
      setPrDescription(found.body || found.description || "");
    }
  };

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
      console.warn("Semantic search failed:", err);
      setSearchError(err?.message || "Semantic search request failed on AI Service.");
    } finally {
      setIsSearching(false);
    }
  };

  const handleImpact = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!symbolsInput.trim()) return;
    if (!selectedRepoId) {
      setImpactError("Please select an authorized repository.");
      return;
    }

    setIsAnalyzingImpact(true);
    setImpactError(null);
    const symbols = symbolsInput.split(",").map((s) => s.trim()).filter(Boolean);
    try {
      const results = await apiClient.post<any[]>("/api/v1/ai/impact", {
        repository_id: selectedRepoId,
        changed_symbols: symbols,
        max_depth: 3,
      });
      setImpactResults(Array.isArray(results) ? results : []);
    } catch (err: any) {
      console.warn("Impact analysis failed:", err);
      setImpactError(err?.message || "Impact analysis request failed on AI Service.");
    } finally {
      setIsAnalyzingImpact(false);
    }
  };

  const handlePrAnalysis = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedRepoId) {
      setPrError("Please select an authorized repository.");
      return;
    }
    setIsAnalyzingPr(true);
    setPrError(null);
    try {
      const result = await apiClient.post<any>("/api/v1/ai/pr-analysis", {
        repository_id: selectedRepoId,
        pr_number: Number(selectedPrNumber) || 1,
        title: prTitle || "Feature Pull Request",
        description: prDescription || "Automated impact assessment",
        changed_symbols: symbolsInput ? symbolsInput.split(",").map((s) => s.trim()) : [],
      });
      setPrAnalysisResult(result);
    } catch (err: any) {
      console.warn("PR analysis failed:", err);
      setPrError(err?.message || "PR Analysis request failed on AI Service.");
    } finally {
      setIsAnalyzingPr(false);
    }
  };

  const selectedRepo = repositories.find((r) => r.id === selectedRepoId);

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
            Hybrid RAG retrieval, AST call graph impact, and grounded LLM reasoning (Gemini Primary + Groq Fallback) for{" "}
            <span className="text-purple-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        {/* Authorized Repository Selector */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5">
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

      {/* Grounding & Fallibility Advisory */}
      <div className="flex items-start gap-3 rounded-xl border border-indigo-500/30 bg-indigo-950/20 p-4 text-xs text-indigo-200">
        <Info className="h-5 w-5 shrink-0 text-indigo-400 mt-0.5" />
        <div>
          <span className="font-semibold text-white">Grounded Intelligence Contract: </span>
          All semantic insights and blast-radius summaries are strictly grounded in verified repository chunks and AST call trees.
          If primary Google Gemini inference is degraded, Groq Cloud failover engages transparently with honest provider provenance recorded in audit logs.
        </div>
      </div>

      {/* Navigation Tabs */}
      <div className="flex items-center gap-2 border-b border-slate-800 pb-2">
        <button
          onClick={() => setActiveTab("search")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-colors ${
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
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-colors ${
            activeTab === "impact"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Network className="h-4 w-4" />
          AST Call Graph Blast Radius
        </button>
        <button
          onClick={() => setActiveTab("pr")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-colors ${
            activeTab === "pr"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <GitPullRequest className="h-4 w-4" />
          PR Impact Review (Hybrid RAG)
        </button>
      </div>

      {/* Tab 1: Semantic Vector Search */}
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

      {/* Tab 2: Graph Impact Analysis */}
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
                    Changed Symbols (comma separated)
                  </label>
                  <Input
                    placeholder="e.g. JwtRevocationGatewayFilter, RedisRevocationStore, WebhookIngestionService"
                    value={symbolsInput}
                    onChange={(e) => setSymbolsInput(e.target.value)}
                  />
                </div>
                <Button type="submit" variant="glow" disabled={isAnalyzingImpact || !symbolsInput.trim()}>
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

      {/* Tab 3: PR Impact Review (Hybrid RAG) */}
      {activeTab === "pr" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Grounded PR Impact Assessment</CardTitle>
              <CardDescription>
                Audited against authoritative Source Control data for{" "}
                <span className="text-purple-300 font-semibold">{selectedRepo?.fullName || "Selected Repository"}</span>.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handlePrAnalysis} className="space-y-4">
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  {/* Select Real Pull Request */}
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-slate-300">
                      Authoritative Pull Request
                    </label>
                    {isLoadingPrs ? (
                      <div className="flex items-center gap-2 p-2 border border-slate-800 rounded-lg text-xs text-slate-400">
                        <Loader2 className="h-3 w-3 animate-spin text-purple-400" />
                        Fetching repository PRs...
                      </div>
                    ) : pullRequests.length > 0 ? (
                      <select
                        aria-label="Authoritative Pull Request Selection"
                        value={selectedPrNumber}
                        onChange={(e) => handleSelectPr(e.target.value)}
                        className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none focus:ring-1 focus:ring-purple-500"
                      >
                        {pullRequests.map((pr) => (
                          <option key={pr.id || pr.number} value={pr.prNumber || pr.number}>
                            #{pr.prNumber || pr.number}: {pr.title}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <Input
                        type="number"
                        placeholder="Pull Request #"
                        value={selectedPrNumber}
                        onChange={(e) => setSelectedPrNumber(Number(e.target.value) || "")}
                      />
                    )}
                  </div>

                  {/* PR Title */}
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-slate-300">PR Title</label>
                    <Input
                      placeholder="e.g. feat(gateway): token revocation filter"
                      value={prTitle}
                      onChange={(e) => setPrTitle(e.target.value)}
                      required
                    />
                  </div>
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">PR Description / Commit Log</label>
                  <textarea
                    placeholder="PR summary or commit log..."
                    value={prDescription}
                    onChange={(e) => setPrDescription(e.target.value)}
                    className="h-20 w-full rounded-lg border border-slate-800 bg-slate-950 p-3 text-xs text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-purple-500"
                  />
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">Changed Symbols (Optional)</label>
                  <Input
                    placeholder="e.g. OutboxPublisherRelay, TestRunApplicationService"
                    value={symbolsInput}
                    onChange={(e) => setSymbolsInput(e.target.value)}
                  />
                </div>

                <Button type="submit" variant="glow" disabled={isAnalyzingPr || !selectedRepoId || !prTitle}>
                  {isAnalyzingPr ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin mr-2" />
                      Evaluating with Gemini Primary / Groq Fallback...
                    </>
                  ) : (
                    "Generate Grounded AI Assessment"
                  )}
                </Button>
              </form>

              {prError && (
                <div className="mt-4 p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {prError}
                </div>
              )}
            </CardContent>
          </Card>

          {/* Analysis Result Display */}
          {prAnalysisResult && (
            <Card className="border-purple-500/30 bg-purple-950/10">
              <CardHeader>
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                  <div className="flex items-center gap-2">
                    <CardTitle className="text-base text-white">AI Analysis & Risk Audit</CardTitle>
                    <Badge
                      variant={
                        prAnalysisResult.risk_level === "LOW"
                          ? "success"
                          : prAnalysisResult.risk_level === "MEDIUM"
                          ? "warning"
                          : "destructive"
                      }
                    >
                      Risk: {prAnalysisResult.risk_level}
                    </Badge>
                  </div>

                  {/* Provider & Fallback Transparency Badges */}
                  <div className="flex items-center gap-2">
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

                <CardDescription className="text-xs font-mono pt-1">
                  Model: {prAnalysisResult.model_version} | Duration: {prAnalysisResult.execution_duration_ms}ms
                  {prAnalysisResult.trace_id && ` | Trace: ${prAnalysisResult.trace_id.substring(0, 16)}...`}
                </CardDescription>
              </CardHeader>

              <CardContent className="space-y-4">
                {/* Summary Body */}
                <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-200 leading-relaxed whitespace-pre-line">
                  {prAnalysisResult.summary}
                </div>

                {/* Grounded Provenance References */}
                {Array.isArray(prAnalysisResult.retrieved_chunks) && prAnalysisResult.retrieved_chunks.length > 0 && (
                  <div className="space-y-2 pt-2">
                    <h4 className="text-xs font-bold text-slate-400 uppercase tracking-wider flex items-center gap-1.5">
                      <FileCode className="h-3.5 w-3.5 text-purple-400" />
                      Supporting Provenance & Evidence ({prAnalysisResult.retrieved_chunks.length} chunks)
                    </h4>
                    <div className="grid grid-cols-1 gap-2">
                      {prAnalysisResult.retrieved_chunks.map((chunk: any, i: number) => (
                        <div
                          key={i}
                          className="p-2.5 rounded-lg border border-slate-800/80 bg-slate-950 text-xs font-mono flex items-center justify-between"
                        >
                          <span className="text-indigo-300 truncate max-w-md">{chunk.file_path}</span>
                          <span className="text-slate-500 text-[10px]">
                            {chunk.symbol_name ? `symbol: ${chunk.symbol_name}` : `commit: ${chunk.commit_sha?.substring(0, 8)}`}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
