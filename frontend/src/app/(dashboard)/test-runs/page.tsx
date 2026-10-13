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
  const { currentOrg } = useTenant();
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<TestRun | null>(null);
  const [testResults, setTestResults] = useState<any[]>([]);
  const [loadingResults, setLoadingResults] = useState(false);

  const fetchRuns = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await apiClient.get<any[]>("/api/v1/test-runs");
      if (Array.isArray(data)) {
        const mapped: TestRun[] = data.map((r) => ({
          id: r.id,
          repositoryFullName: r.repositoryId ? `repo-${r.repositoryId.substring(0, 8)}` : "governance-core",
          commitSha: r.commitSha || "main",
          branch: r.branch || "main",
          prNumber: r.prNumber ?? undefined,
          status: (r.status === "COMPLETED" || r.status === "PASSED")
            ? (r.failedCount > 0 ? "FAILED" : "PASSED")
            : r.status === "FAILED"
            ? "FAILED"
            : r.status === "RUNNING"
            ? "RUNNING"
            : "QUEUED",
          passedCount: r.passedCount ?? 0,
          failedCount: r.failedCount ?? 0,
          skippedCount: r.skippedCount ?? 0,
          totalCount: (r.passedCount ?? 0) + (r.failedCount ?? 0) + (r.skippedCount ?? 0),
          durationMs: r.durationMs ?? 1500,
          createdAt: r.createdAt || new Date().toISOString(),
        }));
        setRuns(mapped);
      }
    } catch (err: any) {
      console.warn("Failed to load test runs from Gateway:", err);
      setError("Could not load test runs for the active organization.");
    } finally {
      setIsLoading(false);
    }
  }, [currentOrg.id]);

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

  const totalRuns = runs.length;
  const passedRuns = runs.filter((r) => r.status === "PASSED").length;
  const passRate = totalRuns > 0 ? ((passedRuns / totalRuns) * 100).toFixed(1) : "100.0";

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
            <span className="text-emerald-300 font-medium">{currentOrg.name}</span>.
          </p>
        </div>

        <div className="flex items-center gap-2">
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
          <div className="text-xl font-bold text-sky-400 font-mono">2.8s</div>
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
          ) : runs.length === 0 ? (
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
                {runs.map((run) => (
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
                        {t.failureMessage && (
                          <div className="text-[11px] text-rose-400 mt-0.5">{t.failureMessage}</div>
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
