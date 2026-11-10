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
    ? "text-emerald-400 border-emerald-900 bg-emerald-950/20"
    : health?.status === "AMBER"
      ? "text-amber-400 border-amber-900 bg-amber-950/20"
      : "text-rose-400 border-rose-900 bg-rose-950/20";

  return (
    <div className="space-y-6">
      {/* Breadcrumb & Navigation */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1.5">
            <div className="flex items-center gap-2 text-xs text-zinc-400 font-mono">
              <Link
                href="/projects"
                className="hover:text-zinc-200 transition-colors flex items-center gap-1"
              >
                <ArrowLeft className="h-3 w-3" /> Projects
              </Link>
              <span>/</span>
              <span className="text-zinc-300">
                {projectId ? projectId.substring(0, 12) : "PROJECT"}
              </span>
              <span>/</span>
              <span className="text-zinc-100 font-semibold font-sans">Traceability Matrix</span>
            </div>
            <div className="flex items-center gap-2 pt-0.5">
              <div className="p-1.5 rounded-md bg-[#141418] text-zinc-300 border border-[#26262C]">
                <Layers className="h-4 w-4" />
              </div>
              <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
                Engineering Traceability & Lineage
              </h1>
            </div>
            <p className="text-xs text-zinc-400 max-w-2xl">
              Live authoritative spine: Requirement → Task → Code Artifact → Commit → Pull Request → Test Run → AI Risk → Merge Readiness.
            </p>
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={handleSync}
              disabled={isSyncing || isLoading || isTenantLoading}
              className="h-8 gap-1.5 text-xs border-[#26262C] bg-[#141418] text-zinc-300"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isSyncing ? "animate-spin" : ""}`} />
              {isSyncing ? "Syncing..." : "Sync Lineage"}
            </Button>

            <Button
              variant="outline"
              size="sm"
              onClick={() => setShowSnapshotDialog(true)}
              disabled={isLoading || isTenantLoading}
              className="h-8 gap-1.5 text-xs border-[#26262C] bg-[#141418] text-zinc-300"
            >
              <Camera className="h-3.5 w-3.5" />
              Snapshot State
            </Button>

            <Button
              size="sm"
              onClick={handleExportAudit}
              disabled={!traceData || isLoading || isTenantLoading}
              className="h-8 gap-1.5 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              <Download className="h-3.5 w-3.5" />
              Export Audit
            </Button>
          </div>
        </div>
      </div>

      {errorMessage && !isTenantLoading && (
        <div className="p-3.5 rounded-md border border-rose-900/60 bg-rose-950/20 text-rose-300 text-xs flex items-center gap-2.5">
          <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMessage}</span>
        </div>
      )}

      {/* Snapshot Creation Modal Dialog */}
      {showSnapshotDialog && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-xs p-4">
          <div className="w-full max-w-md rounded-lg border border-[#26262C] bg-[#0E0E11] p-5 shadow-2xl space-y-3.5">
            <div className="space-y-1">
              <h3 className="text-sm font-semibold text-zinc-100">Save Governance Snapshot</h3>
              <p className="text-xs text-zinc-400">
                Create an immutable governance snapshot of the full delivery spine, gaps, and health scores for compliance audits.
              </p>
            </div>
            <form onSubmit={handleCreateSnapshot} className="space-y-3">
              <input
                type="text"
                placeholder="e.g. Release v1.0 Production Audit"
                value={snapshotLabel}
                onChange={(e) => setSnapshotLabel(e.target.value)}
                required
                className="h-8 w-full rounded-md border border-[#26262C] bg-[#09090B] px-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
              />
              <div className="flex justify-end gap-2 pt-1">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setShowSnapshotDialog(false)}
                  className="h-8 text-xs border-[#26262C]"
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  size="sm"
                  disabled={isSnapshotting || !snapshotLabel.trim()}
                  className="h-8 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
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
        <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-zinc-400">Project Lineage</span>
              <Badge variant="outline" className={`font-mono text-[10px] uppercase font-semibold ${healthStatusColor}`}>
                {health.status}
              </Badge>
            </div>
            <div className="text-2xl font-semibold font-mono tabular-nums text-zinc-100">
              {health.healthScore} <span className="text-xs text-zinc-500 font-normal">/ 100</span>
            </div>
            <div className="w-full bg-[#1C1C21] rounded-full h-1 overflow-hidden">
              <div
                className={`h-full rounded-full ${health.status === "GREEN"
                  ? "bg-emerald-500"
                  : health.status === "AMBER"
                    ? "bg-amber-500"
                    : "bg-rose-500"
                  }`}
                style={{ width: `${health.healthScore}%` }}
              />
            </div>
          </div>

          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
            <div className="text-xs font-medium text-zinc-400">Requirements Complete</div>
            <div className="text-2xl font-semibold text-emerald-400 font-mono tabular-nums">
              {health.requirementsCompleted} <span className="text-xs text-zinc-500 font-normal">/ {health.totalRequirements}</span>
            </div>
            <div className="text-[11px] text-zinc-500 font-mono">
              {health.requirementsAtRisk > 0 ? (
                <span className="text-amber-400">{health.requirementsAtRisk} at risk</span>
              ) : (
                "Continuous verification active"
              )}
            </div>
          </div>

          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
            <div className="text-xs font-medium text-zinc-400">Tasks & Code Lineage</div>
            <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums">
              {health.openTasks} <span className="text-xs text-zinc-500 font-normal">active tasks</span>
            </div>
            <div className="text-[11px] text-zinc-500 font-mono">
              {health.activePrs} pull requests linked across repositories
            </div>
          </div>

          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-1">
            <div className="text-xs font-medium text-zinc-400">Delivery Gaps Detected</div>
            <div className="text-2xl font-semibold font-mono tabular-nums text-amber-400">
              {gaps.length}
            </div>
            <div className="text-[11px] text-zinc-500 font-mono">
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
        <div className="rounded-lg border border-[#1C1C21] bg-[#141418] p-3.5 space-y-2">
          <div className="text-xs font-semibold uppercase text-zinc-400 flex items-center gap-1.5 tracking-wider">
            <ShieldCheck className="h-4 w-4 text-zinc-300" />
            Deterministic Governance Audit Notes
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
            {health.reasons.map((r, i) => (
              <div key={i} className="flex items-center gap-2 text-xs text-zinc-300">
                <div className="h-1.5 w-1.5 rounded-full bg-zinc-500 shrink-0" />
                <span>{r}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Navigation Sub-Tabs */}
      <div className="flex border-b border-[#1C1C21] gap-6 text-xs font-medium text-zinc-400">
        <button
          onClick={() => setActiveTab("spine")}
          className={`pb-2.5 transition-colors ${activeTab === "spine"
            ? "text-zinc-100 border-b-2 border-zinc-100 font-semibold"
            : "hover:text-zinc-200"
            }`}
        >
          Delivery Spine Flow ({traceData?.chains?.length || 0})
        </button>
        <button
          onClick={() => setActiveTab("gaps")}
          className={`pb-2.5 transition-colors flex items-center gap-1.5 ${activeTab === "gaps"
            ? "text-zinc-100 border-b-2 border-zinc-100 font-semibold"
            : "hover:text-zinc-200"
            }`}
        >
          Gap Inspector
          {gaps.length > 0 && (
            <Badge variant="outline" className="text-[10px] px-1.5 py-0 font-mono text-amber-400 border-amber-900">
              {gaps.length}
            </Badge>
          )}
        </button>
        <button
          onClick={() => setActiveTab("snapshots")}
          className={`pb-2.5 transition-colors flex items-center gap-1.5 ${activeTab === "snapshots"
            ? "text-zinc-100 border-b-2 border-zinc-100 font-semibold"
            : "hover:text-zinc-200"
            }`}
        >
          Historical Snapshots ({snapshots.length})
        </button>
        <button
          onClick={() => setActiveTab("repositories")}
          className={`pb-2.5 transition-colors flex items-center gap-1.5 ${activeTab === "repositories"
            ? "text-zinc-100 border-b-2 border-zinc-100 font-semibold"
            : "hover:text-zinc-200"
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
          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-2">
            <RefreshCw className="h-5 w-5 text-zinc-400 animate-spin mx-auto" />
            <div className="text-xs font-mono text-zinc-300">Auditing Governance Gaps...</div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-between gap-4 rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5">
              <div className="text-xs text-zinc-400">
                Showing {filteredGaps.length} of {gaps.length} detected governance gaps across the delivery spine.
              </div>
              <div className="flex items-center gap-1.5">
                <span className="text-xs text-zinc-400">Filter Severity:</span>
                {(["ALL", "HIGH", "MEDIUM", "LOW"] as const).map((sev) => (
                  <button
                    key={sev}
                    onClick={() => setGapFilter(sev)}
                    className={`rounded px-2 py-0.5 text-xs font-medium font-mono transition-colors ${gapFilter === sev
                      ? "bg-zinc-100 text-zinc-900 font-semibold"
                      : "bg-[#141418] text-zinc-400 hover:text-zinc-200 border border-[#26262C]"
                      }`}
                  >
                    {sev}
                  </button>
                ))}
              </div>
            </div>

            {filteredGaps.length === 0 ? (
              <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-2">
                <CheckCircle2 className="h-6 w-6 text-emerald-400 mx-auto" />
                <h3 className="text-sm font-semibold text-zinc-200">Zero Governance Gaps Detected</h3>
                <p className="text-xs text-zinc-400">All requirements, tasks, PRs, and verification suites satisfy integrity invariants.</p>
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {filteredGaps.map((gap, idx) => (
                  <div
                    key={idx}
                    className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3.5 space-y-2.5"
                  >
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs font-semibold text-zinc-200">
                        {gap.gapType}
                      </span>
                      <Badge
                        variant={
                          gap.severity === "HIGH" || gap.severity === "CRITICAL"
                            ? "destructive"
                            : gap.severity === "MEDIUM"
                              ? "outline"
                              : "secondary"
                        }
                        className="text-[10px] font-mono"
                      >
                        {gap.severity}
                      </Badge>
                    </div>
                    <div>
                      <div className="text-xs font-semibold text-zinc-200">{gap.artifactName}</div>
                      <div className="text-xs text-zinc-400 mt-1">{gap.evidence}</div>
                    </div>
                    <div className="p-2.5 rounded bg-[#141418] border border-[#1C1C21] text-[11px] text-amber-300/90 flex items-start gap-2">
                      <ShieldAlert className="h-3.5 w-3.5 text-amber-400 shrink-0 mt-0.5" />
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
          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-2">
            <RefreshCw className="h-5 w-5 text-zinc-400 animate-spin mx-auto" />
            <div className="text-xs font-mono text-zinc-300">Loading Governance Snapshots...</div>
          </div>
        ) : (
          <div className="space-y-3.5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-zinc-200">Immutable Governance Snapshots</h3>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowSnapshotDialog(true)}
                className="gap-1.5 text-xs h-8 border-[#26262C]"
              >
                <Camera className="h-3.5 w-3.5" />
                New Snapshot
              </Button>
            </div>

            {snapshots.length === 0 ? (
              <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-2">
                <Clock className="h-6 w-6 text-zinc-500 mx-auto" />
                <h3 className="text-sm font-semibold text-zinc-200">No Snapshots Created Yet</h3>
                <p className="text-xs text-zinc-400">Save snapshots at major milestones or PR merges for immutable compliance records.</p>
              </div>
            ) : (
              <div className="space-y-2">
                {snapshots.map((snap) => (
                  <div
                    key={snap.id}
                    className="flex items-center justify-between p-3.5 rounded-lg border border-[#1C1C21] bg-[#0E0E11]"
                  >
                    <div className="space-y-1">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-zinc-200">{snap.snapshotName}</span>
                        {snap.prNumber && (
                          <Badge variant="outline" className="font-mono text-[10px] border-[#26262C]">
                            PR #{snap.prNumber}
                          </Badge>
                        )}
                        {snap.commitSha && (
                          <Badge variant="outline" className="font-mono text-[10px] border-[#26262C]">
                            {snap.commitSha.substring(0, 7)}
                          </Badge>
                        )}
                      </div>
                      <p className="text-[11px] text-zinc-500 font-mono">
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
                      className="text-xs h-7 border-[#26262C]"
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
          <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-2">
            <RefreshCw className="h-5 w-5 text-zinc-400 animate-spin mx-auto" />
            <div className="text-xs font-mono text-zinc-300">Loading Connected Repositories...</div>
          </div>
        ) : (
          <div className="space-y-3.5">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-zinc-200">Project Connected Repositories</h3>
              <Link href="/repositories">
                <Button variant="outline" size="sm" className="gap-1.5 text-xs h-8 border-[#26262C]">
                  <PlusCircle className="h-3.5 w-3.5" />
                  Manage Source Control
                </Button>
              </Link>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {(traceData?.repositories || repositories).map((repo: any) => (
                <div
                  key={repo.id || repo.repositoryId}
                  className="p-3.5 rounded-lg border border-[#1C1C21] bg-[#0E0E11] space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <GitBranch className="h-4 w-4 text-zinc-400" />
                      <span className="font-medium text-sm text-zinc-200">
                        {repo.repositoryName || repo.name || "Connected Repository"}
                      </span>
                    </div>
                    <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900">
                      SYNCED
                    </Badge>
                  </div>
                  <div className="text-[11px] text-zinc-500 font-mono">
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
        <div className="p-8 text-center text-zinc-500 text-xs font-mono">
          Loading traceability matrix...
        </div>
      }
    >
      <TraceabilityMatrixContent />
    </Suspense>
  );
}
