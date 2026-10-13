"use client";

import React, { useState } from "react";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  Sparkles,
  Search,
  Network,
  Cpu,
  Layers,
  AlertTriangle,
  CheckCircle2,
  GitPullRequest,
  Loader2,
  Info,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function AiInsightsPage() {
  const { currentOrg } = useTenant();
  const [activeTab, setActiveTab] = useState<"search" | "impact" | "pr">("search");

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
  const [prRepoId, setPrRepoId] = useState("");
  const [prNumber, setPrNumber] = useState(1);
  const [prTitle, setPrTitle] = useState("");
  const [prDescription, setPrDescription] = useState("");
  const [prAnalysisResult, setPrAnalysisResult] = useState<any | null>(null);
  const [isAnalyzingPr, setIsAnalyzingPr] = useState(false);
  const [prError, setPrError] = useState<string | null>(null);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchQuery.trim()) return;

    setIsSearching(true);
    setSearchError(null);
    try {
      const results = await apiClient.post<any[]>("/api/v1/ai/search", {
        query: searchQuery.trim(),
        limit: 10,
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

    setIsAnalyzingImpact(true);
    setImpactError(null);
    const symbols = symbolsInput.split(",").map((s) => s.trim()).filter(Boolean);
    try {
      const results = await apiClient.post<any[]>("/api/v1/ai/impact", {
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
    setIsAnalyzingPr(true);
    setPrError(null);
    try {
      const repoId = prRepoId.trim() || "00000000-0000-0000-0000-000000000001";
      const result = await apiClient.post<any>("/api/v1/ai/pr-analysis", {
        repository_id: repoId,
        pr_number: Number(prNumber),
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

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="h-6 w-6 text-purple-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              AI Intelligence & AST Call Graph
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Hybrid RAG retrieval, vector code chunk search, and recursive call graph blast radius for{" "}
            <span className="text-purple-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <Badge variant="outline" className="text-xs font-mono">
            GATEWAY ROUTE: /api/v1/ai/*
          </Badge>
        </div>
      </div>

      {/* Mandatory Fallibility Notice (Requirements 5 & 20) */}
      <div className="flex items-start gap-3 rounded-xl border border-amber-500/30 bg-amber-950/20 p-4 text-xs text-amber-200">
        <Info className="h-5 w-5 shrink-0 text-amber-400 mt-0.5" />
        <div>
          <span className="font-semibold text-white">AI-Generated Intelligence Advisory: </span>
          All semantic scores, impact trees, and PR summaries are algorithmically generated by AST parsers and Google Gemini LLM inference. Models are fallible and recommendations must be validated by human engineers before merging.
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
          Semantic Vector Search
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
          Graph Impact Analysis
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
          PR Impact Review (Gemini)
        </button>
      </div>

      {/* Tab 1: Semantic Vector Search */}
      {activeTab === "search" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Natural Language Code Search</CardTitle>
              <CardDescription>
                Search across indexed repositories using pgvector cosine embeddings. Tenant isolation strictly enforced.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSearch} className="flex gap-2">
                <Input
                  placeholder="e.g. rate limiter token bucket redis filter"
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
              <h3 className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                Matching Code Chunks ({searchResults.length})
              </h3>
              {searchResults.map((chunk, idx) => (
                <div
                  key={chunk.id || idx}
                  className="rounded-xl border border-slate-800 bg-slate-900/80 p-4 space-y-2"
                >
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-mono text-indigo-300 font-semibold">{chunk.file_path}</span>
                    <Badge variant="outline" className="font-mono text-[10px]">
                      Lines {chunk.start_line}-{chunk.end_line}
                    </Badge>
                  </div>
                  <pre className="text-xs font-mono bg-slate-950 p-3 rounded-lg text-slate-300 overflow-x-auto border border-slate-800/60">
                    {chunk.content}
                  </pre>
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
                    placeholder="e.g. JwtRevocationGatewayFilter, RedisRevocationStore"
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
                      {item.relationship_type || "DEPENDS_ON"}
                    </Badge>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Tab 3: PR Impact Review (Gemini) */}
      {activeTab === "pr" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Gemini PR Impact Assessment</CardTitle>
              <CardDescription>
                Orchestrate Gemini 1.5 Pro to generate semantic executive risk summaries for active pull requests.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handlePrAnalysis} className="space-y-4">
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-slate-300">Repository UUID</label>
                    <Input
                      placeholder="e.g. 00000000-0000-0000-0000-000000000001"
                      value={prRepoId}
                      onChange={(e) => setPrRepoId(e.target.value)}
                    />
                  </div>
                  <div className="space-y-1">
                    <label className="text-xs font-medium text-slate-300">Pull Request #</label>
                    <Input
                      type="number"
                      value={prNumber}
                      onChange={(e) => setPrNumber(Number(e.target.value))}
                    />
                  </div>
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">PR Title</label>
                  <Input
                    placeholder="e.g. feat(gateway): add token revocation filter"
                    value={prTitle}
                    onChange={(e) => setPrTitle(e.target.value)}
                    required
                  />
                </div>

                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">PR Description</label>
                  <textarea
                    placeholder="PR summary or commit log..."
                    value={prDescription}
                    onChange={(e) => setPrDescription(e.target.value)}
                    className="h-20 w-full rounded-lg border border-slate-800 bg-slate-950 p-3 text-xs text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-purple-500"
                  />
                </div>

                <Button type="submit" variant="glow" disabled={isAnalyzingPr || !prTitle}>
                  {isAnalyzingPr ? (
                    <>
                      <Loader2 className="h-4 w-4 animate-spin mr-2" />
                      Synthesizing Gemini Analysis...
                    </>
                  ) : (
                    "Generate AI Risk Assessment"
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

          {prAnalysisResult && (
            <Card className="border-purple-500/30 bg-purple-950/10">
              <CardHeader>
                <div className="flex items-center justify-between">
                  <CardTitle className="text-base text-white">AI Analysis Result</CardTitle>
                  <Badge
                    variant={
                      prAnalysisResult.risk_level === "LOW"
                        ? "success"
                        : prAnalysisResult.risk_level === "MEDIUM"
                        ? "warning"
                        : "destructive"
                    }
                  >
                    Risk Level: {prAnalysisResult.risk_level}
                  </Badge>
                </div>
                <CardDescription className="text-xs font-mono">
                  Model: {prAnalysisResult.model_version} | Duration: {prAnalysisResult.execution_duration_ms}ms
                </CardDescription>
              </CardHeader>
              <CardContent className="space-y-4">
                <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-200 leading-relaxed">
                  {prAnalysisResult.summary}
                </div>
              </CardContent>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
