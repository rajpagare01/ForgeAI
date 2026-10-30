"use client";

import React, { useState, useEffect, useCallback } from "react";
import { TestRun, TestCase } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  CheckCircle2,
  XCircle,
  Clock,
  PlayCircle,
  FileCode,
  AlertTriangle,
  Terminal,
  Search,
  Filter,
  RefreshCw,
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
import { formatDate } from "@/lib/utils";

export default function TestRunsPage() {
  const { currentOrg, activeWorkspace, isPersonalWorkspace } = useTenant();
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [repositories, setRepositories] = useState<{ id: string; fullName: string; name: string }[]>([]);
  const [selectedRepoFilter, setSelectedRepoFilter] = useState<string>(() => {
    if (typeof window !== "undefined") {
      return localStorage.getItem("forgeai_selected_repo_id") || "ALL";
    }
    return "ALL";
  });
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<TestRun | null>(null);
  const [testResults, setTestResults] = useState<any[]>([]);
  const [loadingResults, setLoadingResults] = useState(false);

  const fetchRuns = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      // 1. Fetch repositories concurrently to map real repository names
      let repoMap: Record<string, string> = {};
      try {
        const repoData = await apiClient.get<any[]>("/api/v1/repositories");
        if (Array.isArray(repoData)) {
          setRepositories(repoData.map((r) => ({ id: r.id, fullName: r.fullName || r.name, name: r.name })));
          repoData.forEach((r) => {
            if (r.id) repoMap[r.id] = r.fullName || r.name;
          });
        }
      } catch (rErr) {
        console.warn("Could not load repositories for test runs name mapping:", rErr);
      }

      // 2. Fetch test runs
      const data = await apiClient.get<any[]>("/api/v1/test-runs");
      if (Array.isArray(data)) {
        const mapped: TestRun[] = data.map((r) => {
          let passed = r.passedTests ?? r.passedCount ?? 0;
          let failed = r.failedTests ?? r.failedCount ?? 0;
          let skipped = r.skippedTests ?? r.skippedCount ?? 0;
          let total = r.totalTests ?? r.totalCount ?? 0;

          const isSuccess = r.conclusion === "success" || r.status === "PASSED" || r.status === "COMPLETED";
          const isFailed = r.conclusion === "failure" || r.status === "FAILED";

          if (total === 0) {
            if (isSuccess) {
              passed = 1;
              total = 1;
            } else if (isFailed) {
              failed = 1;
              total = 1;
            }
          }

          const status = (isFailed || failed > 0)
            ? "FAILED"
            : (isSuccess)
            ? "PASSED"
            : r.status === "RUNNING" || r.status === "IN_PROGRESS" || r.githubRunStatus === "in_progress"
            ? "RUNNING"
            : "QUEUED";

          let durationMs = r.durationMs;
          if (!durationMs || durationMs === 0) {
            const start = r.runStartedAt || r.startedAt || r.createdAt;
            const end = r.runCompletedAt || r.completedAt || r.updatedAt;
            if (start && end) {
              const diff = new Date(end).getTime() - new Date(start).getTime();
              if (diff > 0) durationMs = diff;
            }
          }
          if (!durationMs || durationMs === 0) {
            durationMs = 1500;
          }

          const repoName = (r.repositoryId && repoMap[r.repositoryId])
            ? repoMap[r.repositoryId]
            : r.repositoryFullName
            ? r.repositoryFullName
            : r.repositoryId
            ? `repo-${r.repositoryId.substring(0, 8)}`
            : "governance-core";

          return {
            id: r.id,
            repositoryId: r.repositoryId,
            repositoryFullName: repoName,
            commitSha: r.headSha || r.commitSha || "main",
            branch: r.headBranch || r.branch || "main",
            prNumber: r.pullRequestNumber ?? r.prNumber ?? undefined,
            status,
            passedCount: passed,
            failedCount: failed,
            skippedCount: skipped,
            totalCount: total,
            durationMs,
            createdAt: r.runStartedAt || r.createdAt || new Date().toISOString(),
          } as any;
        });
        setRuns(mapped);
      }
    } catch (err: any) {
      console.warn("Failed to load test runs from Gateway:", err);
      setError("Could not load test runs for the active organization.");
    } finally {
      setIsLoading(false);
    }
  }, [currentOrg?.id, activeWorkspace?.id]);

  useEffect(() => {
    fetchRuns();
  }, [fetchRuns]);

  const openInspector = async (run: TestRun) => {
    setSelectedRun(run);
    setLoadingResults(true);
    try {
      const results = await apiClient.get<any[]>(`/api/v1/test-runs/${run.id}/results`);
      setTestResults(Array.isArray(results) ? results : []);
    } catch (err) {
      console.warn("Could not fetch test results for run:", err);
      setTestResults([]);
    } finally {
      setLoadingResults(false);
    }
  };

  const filteredRuns = selectedRepoFilter === "ALL"
    ? runs
    : runs.filter((r) => (r as any).repositoryId === selectedRepoFilter);

  const totalRuns = filteredRuns.length;
  const passedRuns = filteredRuns.filter((r) => r.status === "PASSED").length;
  const passRate = totalRuns > 0 ? ((passedRuns / totalRuns) * 100).toFixed(1) : "100.0";
  const meanDurationMs = totalRuns > 0
    ? filteredRuns.reduce((acc, r) => acc + (r.durationMs || 0), 0) / totalRuns
    : 0;
  const meanDurationFormatted = (meanDurationMs / 1000).toFixed(1) + "s";

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <CheckCircle2 className="h-6 w-6 text-emerald-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              CI Test Runs & Suite Ingestion
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Deterministic staging verification reports received from GitHub Actions via Testing Service for{" "}
            <span className="text-emerald-300 font-medium">
              {isPersonalWorkspace ? "Personal Workspace" : currentOrg?.name || "Workspace"}
            </span>.
          </p>
        </div>

        <div className="flex items-center gap-2">
          {repositories.length > 0 && (
            <div className="flex items-center gap-1.5 bg-slate-900 border border-slate-800 rounded-lg px-2.5 py-1 text-xs">
              <Filter className="h-3 w-3 text-slate-400" />
              <select
                aria-label="Filter Test Runs by Repository"
                value={selectedRepoFilter}
                onChange={(e) => {
                  const val = e.target.value;
                  setSelectedRepoFilter(val);
                  if (typeof window !== "undefined" && val !== "ALL") {
                    localStorage.setItem("forgeai_selected_repo_id", val);
                  }
                }}
                className="bg-transparent text-xs text-white font-medium focus:outline-none cursor-pointer"
              >
                <option value="ALL" className="bg-slate-900 text-white">
                  All Repositories ({repositories.length})
                </option>
                {repositories.map((repo) => (
                  <option key={repo.id} value={repo.id} className="bg-slate-900 text-white">
                    {repo.fullName}
                  </option>
                ))}
              </select>
            </div>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={fetchRuns}
            disabled={isLoading}
            className="gap-2"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            Refresh Runs
          </Button>
          <Badge variant="outline" className="text-xs font-mono">
            GATEWAY ROUTE: /api/v1/test-runs
          </Badge>
        </div>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Metrics Row */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Total Runs Ingested</div>
          <div className="text-xl font-bold text-white font-mono">{totalRuns}</div>
          <div className="text-[10px] text-slate-500">Live staging reports</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Pass Rate</div>
          <div className="text-xl font-bold text-emerald-400 font-mono">{passRate}%</div>
          <div className="text-[10px] text-emerald-400">{passedRuns} passed / {totalRuns - passedRuns} failed</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Mean Duration</div>
          <div className="text-xl font-bold text-sky-400 font-mono">{meanDurationFormatted}</div>
          <div className="text-[10px] text-slate-500">Containerized JVM suites</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Security Model</div>
          <div className="text-xl font-bold text-purple-400 font-mono">Zero Code Exec</div>
          <div className="text-[10px] text-purple-400">JUnit Ingestion Only</div>
        </div>
      </div>

      {/* Ingested Test Runs Table */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Recent Verification Runs</CardTitle>
          <CardDescription>
            Click any run to inspect test suites, execution metrics, and failure traces.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex flex-col items-center justify-center p-12 text-slate-400">
              <Loader2 className="h-8 w-8 animate-spin text-emerald-400 mb-2" />
              <p className="text-xs">Fetching CI test runs from Gateway...</p>
            </div>
          ) : filteredRuns.length === 0 ? (
            <div className="flex flex-col items-center justify-center p-12 text-center text-slate-400">
              <CheckCircle2 className="h-10 w-10 text-slate-600 mb-3" />
              <p className="text-xs font-medium text-white">No test runs ingested yet</p>
              <p className="text-xs text-slate-500 max-w-sm mt-1">
                Upload JUnit XML reports to <code className="text-emerald-400">/api/v1/test-runs/&#123;runId&#125;/reports/junit</code> from CI to view automated test telemetry.
              </p>
            </div>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Run ID</TableHead>
                  <TableHead>Repository</TableHead>
                  <TableHead>Branch / PR</TableHead>
                  <TableHead>Commit SHA</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Passed / Total</TableHead>
                  <TableHead>Duration</TableHead>
                  <TableHead>Ingested At</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filteredRuns.map((run) => (
                  <TableRow
                    key={run.id}
                    className="cursor-pointer hover:bg-slate-800/40 transition-colors"
                    onClick={() => openInspector(run)}
                  >
                    <TableCell className="font-mono text-xs font-bold text-indigo-300">
                      {run.id.length > 12 ? `${run.id.substring(0, 10)}...` : run.id}
                    </TableCell>
                    <TableCell className="text-xs font-medium text-slate-200">
                      {run.repositoryFullName}
                    </TableCell>
                    <TableCell className="text-xs text-slate-400">
                      {run.prNumber ? `PR #${run.prNumber}` : run.branch}
                    </TableCell>
                    <TableCell className="font-mono text-xs text-slate-400">
                      {run.commitSha.substring(0, 7)}
                    </TableCell>
                    <TableCell>
                      <Badge
                        variant={
                          run.status === "PASSED"
                            ? "success"
                            : run.status === "FAILED"
                            ? "destructive"
                            : "secondary"
                        }
                        className="text-[10px]"
                      >
                        {run.status}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-xs font-mono">
                      <span className="text-emerald-400 font-bold">{run.passedCount}</span>
                      <span className="text-slate-500"> / {run.totalCount}</span>
                    </TableCell>
                    <TableCell className="text-xs text-slate-400 font-mono">
                      {(run.durationMs / 1000).toFixed(2)}s
                    </TableCell>
                    <TableCell className="text-xs text-slate-400">
                      {formatDate(run.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {/* Inspector Modal */}
      {selectedRun && (
        <Dialog open={!!selectedRun} onOpenChange={(open) => !open && setSelectedRun(null)}>
          <DialogHeader>
            <div className="flex items-center justify-between w-full">
              <div>
                <DialogTitle>Verification Run: {selectedRun.id}</DialogTitle>
                <DialogDescription>
                  Detailed test results ingested from JUnit XML report.
                </DialogDescription>
              </div>
              <DialogClose onClose={() => setSelectedRun(null)} />
            </div>
          </DialogHeader>

          <div className="space-y-4 my-4 max-h-[60vh] overflow-y-auto">
            <div className="grid grid-cols-3 gap-2 text-xs bg-slate-900 p-3 rounded-lg border border-slate-800">
              <div>
                <span className="text-slate-500">Repository: </span>
                <span className="text-white font-mono">{selectedRun.repositoryFullName}</span>
              </div>
              <div>
                <span className="text-slate-500">Commit: </span>
                <span className="text-white font-mono">{selectedRun.commitSha.substring(0, 8)}</span>
              </div>
              <div>
                <span className="text-slate-500">Duration: </span>
                <span className="text-white font-mono">{(selectedRun.durationMs / 1000).toFixed(2)}s</span>
              </div>
            </div>

            {loadingResults ? (
              <div className="flex items-center justify-center p-8 text-slate-400">
                <Loader2 className="h-6 w-6 animate-spin text-emerald-400 mr-2" />
                <span className="text-xs">Loading test cases...</span>
              </div>
            ) : testResults.length === 0 ? (
              <div className="p-6 text-center text-xs text-slate-400 border border-slate-800 rounded-lg">
                No individual test case results stored for this run.
              </div>
            ) : (
              <div className="space-y-2">
                <div className="text-xs font-semibold text-slate-300">
                  Individual Test Results ({testResults.length})
                </div>
                {testResults.map((t, idx) => (
                  <div
                    key={t.id || idx}
                    className="flex items-center justify-between p-2.5 rounded-md bg-slate-950 border border-slate-800/80 text-xs"
                  >
                    <div className="flex items-center gap-2">
                      {t.status === "PASSED" ? (
                        <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                      ) : (
                        <XCircle className="h-4 w-4 text-rose-400 shrink-0" />
                      )}
                      <div>
                        <div className="font-mono text-white font-medium">{t.testCaseName || t.name || `Test Case ${idx + 1}`}</div>
                        {(t.failureMessage || t.errorMessage) && (
                          <div className="text-[11px] text-rose-400 mt-0.5">{t.failureMessage || t.errorMessage}</div>
                        )}
                      </div>
                    </div>
                    <Badge variant={t.status === "PASSED" ? "success" : "destructive"} className="text-[10px]">
                      {t.status}
                    </Badge>
                  </div>
                ))}
              </div>
            )}
          </div>

          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setSelectedRun(null)}>
              Close
            </Button>
          </DialogFooter>
        </Dialog>
      )}
    </div>
  );
}
