"use client";

import React, { Suspense, useEffect, useState, useCallback } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { TraceabilitySpineFlow } from "@/components/traceability/TraceabilitySpineFlow";
import {
  Layers,
  ArrowLeft,
  ShieldCheck,
  ShieldAlert,
  Download,
  RefreshCw,
  GitBranch,
  AlertCircle,
  Clock,
  Camera,
  CheckCircle2,
  ExternalLink,
  ChevronRight,
  PlusCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  ProjectTraceabilityResponse,
  TraceabilitySnapshot,
  TraceabilityGap,
} from "@/types";

function TraceabilityMatrixContent() {
  const params = useParams();
  const projectId = (params?.projectId as string) || "";
  const { currentOrg, isLoading: isTenantLoading, activeWorkspace } = useTenant();

  const [traceData, setTraceData] = useState<ProjectTraceabilityResponse | null>(null);
  const [snapshots, setSnapshots] = useState<TraceabilitySnapshot[]>([]);
  const [repositories, setRepositories] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  const [isSnapshotting, setIsSnapshotting] = useState<boolean>(false);
  const [snapshotLabel, setSnapshotLabel] = useState<string>("");
  const [showSnapshotDialog, setShowSnapshotDialog] = useState<boolean>(false);
  const [gapFilter, setGapFilter] = useState<string>("ALL");
  const [activeTab, setActiveTab] = useState<"spine" | "gaps" | "snapshots" | "repositories">("spine");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (isTenantLoading) return;
    if (!projectId || (!currentOrg?.id && !activeWorkspace?.id)) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setErrorMessage(null);
    try {
      const [traceResp, snapResp, repoResp] = await Promise.allSettled([
        apiClient.getProjectTraceability<ProjectTraceabilityResponse>(projectId),
        apiClient.listTraceabilitySnapshots<TraceabilitySnapshot[]>(projectId),
        apiClient.listProjectRepositories<any[]>(projectId),
      ]);

      if (traceResp.status === "fulfilled") {
        setTraceData(traceResp.value);
      } else {
        setTraceData(null);
        if (process.env.NODE_ENV !== "production") {
          console.error("Failed to load project traceability", {
            projectId,
            orgId: currentOrg?.id,
            workspaceId: activeWorkspace?.id,
            error: traceResp.reason,
          });
        }
        setErrorMessage("Failed to load traceability data. The project might not exist or the service is unreachable.");
      }

      if (snapResp.status === "fulfilled" && Array.isArray(snapResp.value)) {
        setSnapshots(snapResp.value);
      } else {
        setSnapshots([]);
      }

      if (repoResp.status === "fulfilled" && Array.isArray(repoResp.value)) {
        setRepositories(repoResp.value);
      } else {
        setRepositories([]);
      }
    } catch (err: any) {
      setTraceData(null);
      if (process.env.NODE_ENV !== "production") {
        console.error("An error occurred while loading traceability", {
          projectId,
          orgId: currentOrg?.id,
          workspaceId: activeWorkspace?.id,
          error: err,
        });
      }
      setErrorMessage(err.message || "An error occurred while loading traceability.");
    } finally {
      setIsLoading(false);
    }
  }, [projectId, currentOrg?.id, activeWorkspace?.id, isTenantLoading]);

  useEffect(() => {
    if (projectId && !isTenantLoading && (currentOrg?.id || activeWorkspace?.id)) {
      loadData();
    }
  }, [loadData, projectId, isTenantLoading, currentOrg?.id, activeWorkspace?.id]);

  const handleSync = async () => {
    setIsSyncing(true);
    try {
      await apiClient.syncTraceability(projectId);
      await loadData();
    } catch (err: any) {
      alert("Failed to synchronize traceability links: " + err.message);
    } finally {
      setIsSyncing(false);
    }
  };

  const handleCreateSnapshot = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!snapshotLabel.trim()) return;
    setIsSnapshotting(true);
    try {
      await apiClient.createTraceabilitySnapshot(projectId, { snapshotName: snapshotLabel });
      setSnapshotLabel("");
      setShowSnapshotDialog(false);
      await loadData();
    } catch (err: any) {
      alert("Failed to create snapshot: " + err.message);
    } finally {
      setIsSnapshotting(false);
    }
  };

  const handleExportAudit = () => {
    if (!traceData) return;
    const blob = new Blob([JSON.stringify(traceData, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `forgeai-traceability-audit-${projectId}-${new Date().toISOString().split("T")[0]}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const health = traceData?.health;
  const gaps = traceData?.gaps || [];
  const filteredGaps = gaps.filter((g) => gapFilter === "ALL" || g.severity === gapFilter);

  const healthStatusColor = health?.status === "GREEN"
    ? "text-emerald-400 border-emerald-500/30 bg-emerald-500/10"
    : health?.status === "AMBER"
    ? "text-amber-400 border-amber-500/30 bg-amber-500/10"
    : "text-rose-400 border-rose-500/30 bg-rose-500/10";

  return (
    <div className="space-y-8">
      {/* Breadcrumb & Navigation */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-xs text-slate-400">
            <Link
              href="/projects"
              className="hover:text-indigo-400 transition-colors flex items-center gap-1"
            >
              <ArrowLeft className="h-3.5 w-3.5" /> Projects
            </Link>
            <span>/</span>
            <Link
              href={`/projects/${projectId}`}
              className="hover:text-indigo-400 transition-colors font-mono"
            >
              {projectId ? projectId.substring(0, 12) : "PROJECT"}
            </Link>
            <span>/</span>
            <span className="text-white font-semibold">Unified Traceability Matrix</span>
          </div>
          <div className="flex items-center gap-2 pt-1">
            <div className="p-2 rounded-xl bg-indigo-600/20 text-indigo-400 border border-indigo-500/30">
              <Layers className="h-5 w-5" />
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-white">
              Unified Engineering Traceability & Intelligence
            </h1>
          </div>
          <p className="text-xs text-slate-400 max-w-2xl">
            Live authoritative spine: Requirement → Task → Code Artifact → Commit → Pull Request → Test Run → AI Analysis → Risk → Merge Readiness → Remediation.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={handleSync}
            disabled={isSyncing || isLoading || isTenantLoading}
            className="gap-1.5 text-xs"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isSyncing ? "animate-spin" : ""}`} />
            {isSyncing ? "Syncing..." : "Sync Traceability"}
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowSnapshotDialog(true)}
            disabled={isLoading || isTenantLoading}
            className="gap-1.5 text-xs"
          >
            <Camera className="h-3.5 w-3.5" />
            Snapshot State
          </Button>

          <Button
            variant="glow"
            size="sm"
            onClick={handleExportAudit}
            disabled={!traceData || isLoading || isTenantLoading}
            className="gap-1.5 text-xs"
          >
            <Download className="h-3.5 w-3.5" />
            Export Audit Matrix
          </Button>
        </div>
      </div>

      {errorMessage && !isTenantLoading && (
        <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-500/10 text-rose-300 text-xs flex items-center gap-3">
          <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* Snapshot Creation Modal Dialog */}
      {showSnapshotDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
          <div className="w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900 p-6 shadow-2xl space-y-4">
            <div className="space-y-1">
              <h3 className="text-base font-semibold text-white">Save Governance Snapshot</h3>
              <p className="text-xs text-slate-400">
                Create an immutable governance snapshot of the full delivery spine, gaps, and health scores for compliance audits.
              </p>
            </div>
            <form onSubmit={handleCreateSnapshot} className="space-y-4">
              <input
                type="text"
                placeholder="e.g. Release v1.0 Production Audit"
                value={snapshotLabel}
                onChange={(e) => setSnapshotLabel(e.target.value)}
                required
                className="h-10 w-full rounded-lg border border-slate-700 bg-slate-950 px-3 text-xs text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
              />
              <div className="flex justify-end gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowSnapshotDialog(false)}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="glow"
                  size="sm"
                  disabled={isSnapshotting || !snapshotLabel.trim()}
                >
                  {isSnapshotting ? "Saving..." : "Save Snapshot"}
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Health & Governance KPI Card Strip */}
      {health && (
        <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs text-slate-400">Project Health</span>
              <Badge className={`border text-[10px] uppercase font-bold ${healthStatusColor}`}>
                {health.status}
              </Badge>
            </div>
            <div className="text-2xl font-bold font-mono text-white">
              {health.healthScore} <span className="text-xs text-slate-500">/ 100</span>
            </div>
            <div className="w-full bg-slate-800 rounded-full h-1.5 overflow-hidden">
              <div
                className={`h-full rounded-full ${
                  health.status === "GREEN"
                    ? "bg-emerald-500"
                    : health.status === "AMBER"
                    ? "bg-amber-500"
                    : "bg-rose-500"
                }`}
                style={{ width: `${health.healthScore}%` }}
              />
            </div>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
            <div className="text-xs text-slate-400">Requirements Complete</div>
            <div className="text-2xl font-bold text-emerald-400 font-mono">
              {health.requirementsCompleted} <span className="text-xs text-slate-500">/ {health.totalRequirements}</span>
            </div>
            <div className="text-[10px] text-slate-500">
              {health.requirementsAtRisk > 0 ? (
                <span className="text-amber-400">{health.requirementsAtRisk} at risk</span>
              ) : (
                "Continuous verification active"
              )}
            </div>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
            <div className="text-xs text-slate-400">Tasks & Code Lineage</div>
            <div className="text-2xl font-bold text-indigo-400 font-mono">
              {health.openTasks} <span className="text-xs text-slate-500">active tasks</span>
            </div>
            <div className="text-[10px] text-slate-500">
              {health.activePrs} pull requests linked across repositories
            </div>
          </div>

          <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
            <div className="text-xs text-slate-400">Delivery Gaps Detected</div>
            <div className="text-2xl font-bold font-mono text-amber-400">
              {gaps.length}
            </div>
            <div className="text-[10px] text-slate-500">
              {health.blockedTasks > 0 ? (
                <span className="text-rose-400 font-semibold">{health.blockedTasks} blocker(s) present</span>
              ) : (
                "Automated gap mitigation engine active"
              )}
            </div>
          </div>
        </div>
      )}

      {/* Health Reasons Alerts */}
      {health?.reasons && health.reasons.length > 0 && (
        <div className="rounded-xl border border-slate-800/80 bg-slate-950/60 p-4 space-y-2">
          <div className="text-xs font-semibold uppercase text-slate-400 flex items-center gap-1.5">
            <ShieldCheck className="h-4 w-4 text-indigo-400" />
            Deterministic Governance Audit Notes
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {health.reasons.map((r, i) => (
              <div key={i} className="flex items-center gap-2 text-xs text-slate-300">
                <div className="h-1.5 w-1.5 rounded-full bg-indigo-400 shrink-0" />
                <span>{r}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Navigation Sub-Tabs */}
      <div className="flex border-b border-slate-800 gap-6 text-xs font-medium text-slate-400">
        <button
          onClick={() => setActiveTab("spine")}
          className={`pb-3 transition-colors ${
            activeTab === "spine"
              ? "text-white border-b-2 border-indigo-500 font-semibold"
              : "hover:text-slate-200"
          }`}
        >
          Delivery Spine Flow ({traceData?.chains?.length || 0})
        </button>
        <button
          onClick={() => setActiveTab("gaps")}
          className={`pb-3 transition-colors flex items-center gap-1.5 ${
            activeTab === "gaps"
              ? "text-white border-b-2 border-indigo-500 font-semibold"
              : "hover:text-slate-200"
          }`}
        >
          Gap Inspector
          {gaps.length > 0 && (
            <Badge variant="warning" className="text-[10px] px-1.5 py-0">
              {gaps.length}
            </Badge>
          )}
        </button>
        <button
          onClick={() => setActiveTab("snapshots")}
          className={`pb-3 transition-colors flex items-center gap-1.5 ${
            activeTab === "snapshots"
              ? "text-white border-b-2 border-indigo-500 font-semibold"
              : "hover:text-slate-200"
          }`}
        >
          Historical Snapshots ({snapshots.length})
        </button>
        <button
          onClick={() => setActiveTab("repositories")}
          className={`pb-3 transition-colors flex items-center gap-1.5 ${
            activeTab === "repositories"
              ? "text-white border-b-2 border-indigo-500 font-semibold"
              : "hover:text-slate-200"
          }`}
        >
          Connected Repositories ({traceData?.repositories?.length || repositories.length})
        </button>
      </div>

      {/* Tab 1: Delivery Spine Flow */}
      {activeTab === "spine" && (
        <TraceabilitySpineFlow
          chains={traceData?.chains || []}
          isLoading={isLoading || isTenantLoading}
          onSync={handleSync}
        />
      )}

      {/* Tab 2: Gap Inspector */}
      {activeTab === "gaps" && (
        (isLoading || isTenantLoading) ? (
          <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-12 text-center space-y-3">
            <RefreshCw className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
            <div className="text-sm font-semibold text-white">Auditing Governance Gaps...</div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-900/50 p-4">
              <div className="text-xs text-slate-400">
                Showing {filteredGaps.length} of {gaps.length} detected governance gaps across the delivery spine.
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-slate-400">Filter Severity:</span>
                {(["ALL", "HIGH", "MEDIUM", "LOW"] as const).map((sev) => (
                  <button
                    key={sev}
                    onClick={() => setGapFilter(sev)}
                    className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                      gapFilter === sev
                        ? "bg-indigo-600 text-white"
                        : "bg-slate-800/80 text-slate-300 hover:bg-slate-700"
                    }`}
                  >
                    {sev}
                  </button>
                ))}
              </div>
            </div>

            {filteredGaps.length === 0 ? (
              <div className="rounded-xl border border-slate-800 bg-slate-900/30 p-12 text-center space-y-2">
                <CheckCircle2 className="h-8 w-8 text-emerald-400 mx-auto" />
                <h3 className="text-sm font-semibold text-white">Zero Governance Gaps Detected</h3>
                <p className="text-xs text-slate-400">All requirements, tasks, PRs, and verification suites satisfy integrity invariants.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                {filteredGaps.map((gap, idx) => (
                  <div
                    key={idx}
                    className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 space-y-3"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs font-bold text-indigo-400">
                        {gap.gapType}
                      </span>
                      <Badge
                        variant={
                          gap.severity === "HIGH" || gap.severity === "CRITICAL"
                            ? "destructive"
                            : gap.severity === "MEDIUM"
                            ? "warning"
                            : "secondary"
                        }
                      >
                        {gap.severity}
                      </Badge>
                    </div>
                    <div>
                      <div className="text-xs font-semibold text-white">{gap.artifactName}</div>
                      <div className="text-xs text-slate-400 mt-1">{gap.evidence}</div>
                    </div>
                    <div className="p-2.5 rounded-lg bg-slate-950/70 border border-slate-800 text-[11px] text-amber-300/90 flex items-start gap-2">
                      <ShieldAlert className="h-4 w-4 text-amber-400 shrink-0 mt-0.5" />
                      <span>{gap.recommendation}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )
      )}

      {/* Tab 3: Historical Snapshots */}
      {activeTab === "snapshots" && (
        (isLoading || isTenantLoading) ? (
          <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-12 text-center space-y-3">
            <RefreshCw className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
            <div className="text-sm font-semibold text-white">Loading Governance Snapshots...</div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-white">Immutable Governance Snapshots</h3>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowSnapshotDialog(true)}
                className="gap-1.5 text-xs"
              >
                <Camera className="h-3.5 w-3.5" />
                New Snapshot
              </Button>
            </div>

            {snapshots.length === 0 ? (
              <div className="rounded-xl border border-slate-800 bg-slate-900/30 p-12 text-center space-y-2">
                <Clock className="h-8 w-8 text-slate-500 mx-auto" />
                <h3 className="text-sm font-semibold text-white">No Snapshots Created Yet</h3>
                <p className="text-xs text-slate-400">Save snapshots at major milestones or PR merges for immutable compliance records.</p>
              </div>
            ) : (
              <div className="space-y-3">
                {snapshots.map((snap) => (
                  <div
                    key={snap.id}
                    className="flex items-center justify-between p-4 rounded-xl border border-slate-800 bg-slate-900/40"
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-white">{snap.snapshotName}</span>
                        {snap.prNumber && (
                          <Badge variant="outline" className="font-mono text-[10px]">
                            PR #{snap.prNumber}
                          </Badge>
                        )}
                        {snap.commitSha && (
                          <Badge variant="outline" className="font-mono text-[10px]">
                            {snap.commitSha.substring(0, 7)}
                          </Badge>
                        )}
                      </div>
                      <p className="text-[11px] text-slate-500">
                        Captured on {new Date(snap.createdAt).toLocaleString()}
                      </p>
                    </div>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => {
                        try {
                          const parsed = JSON.parse(snap.snapshotData);
                          setTraceData(parsed);
                          setActiveTab("spine");
                        } catch {
                          alert("Failed to parse snapshot data.");
                        }
                      }}
                      className="text-xs"
                    >
                      View Snapshot
                    </Button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )
      )}

      {/* Tab 4: Connected Repositories */}
      {activeTab === "repositories" && (
        (isLoading || isTenantLoading) ? (
          <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-12 text-center space-y-3">
            <RefreshCw className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
            <div className="text-sm font-semibold text-white">Loading Connected Repositories...</div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-white">Project Connected Repositories</h3>
              <Link href="/repositories">
                <Button variant="outline" size="sm" className="gap-1.5 text-xs">
                  <PlusCircle className="h-3.5 w-3.5" />
                  Manage Source Control
                </Button>
              </Link>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {(traceData?.repositories || repositories).map((repo: any) => (
                <div
                  key={repo.id || repo.repositoryId}
                  className="p-4 rounded-xl border border-slate-800 bg-slate-900/40 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <GitBranch className="h-4 w-4 text-sky-400" />
                      <span className="font-semibold text-sm text-white">
                        {repo.repositoryName || repo.name || "Connected Repository"}
                      </span>
                    </div>
                    <Badge variant="success">SYNCED</Badge>
                  </div>
                  <div className="text-[11px] text-slate-500 font-mono">
                    Repository ID: {repo.repositoryId || repo.id}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )
      )}
    </div>
  );
}

export default function TraceabilityMatrixPage() {
  return (
    <Suspense
      fallback={
        <div className="p-8 text-center text-slate-500 text-xs">
          Loading traceability matrix...
        </div>
      }
    >
      <TraceabilityMatrixContent />
    </Suspense>
  );
}
