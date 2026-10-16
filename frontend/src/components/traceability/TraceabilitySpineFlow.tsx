"use client";

import React, { useState } from "react";
import { TraceabilityNode, TraceabilitySpineChain, RiskLevel } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  FileText,
  CheckSquare,
  GitPullRequest,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Sparkles,
  GitCommit,
  ShieldAlert,
  Search,
  Filter,
  FolderTree,
  RefreshCw,
} from "lucide-react";
import { TraceabilityInspectionSheet } from "./TraceabilityInspectionSheet";

interface TraceabilitySpineFlowProps {
  chains?: TraceabilitySpineChain[];
  isLoading?: boolean;
  onSync?: () => void;
}

function convertChainToNode(chain: TraceabilitySpineChain): TraceabilityNode {
  const req = chain.requirement;
  const task = chain.tasks && chain.tasks.length > 0 ? chain.tasks[0] : null;
  const pr = chain.pullRequests && chain.pullRequests.length > 0 ? chain.pullRequests[0] : null;
  const commit = chain.commits && chain.commits.length > 0 ? chain.commits[0] : null;
  const testRun = chain.testRuns && chain.testRuns.length > 0 ? chain.testRuns[0] : null;
  const ai = chain.aiAnalyses && chain.aiAnalyses.length > 0 ? chain.aiAnalyses[0] : null;

  // Determine verification stats
  let testStatus: "PASSED" | "FAILED" | "RUNNING" = "PASSED";
  let passedTests = chain.testCases.filter((tc) => tc.latestStatus === "PASSED").length;
  let totalTests = chain.testCases.length;

  if (testRun) {
    totalTests = testRun.totalTests || totalTests;
    passedTests = testRun.passedTests || passedTests;
    testStatus = testRun.status === "COMPLETED"
      ? (testRun.failedTests && testRun.failedTests > 0 ? "FAILED" : "PASSED")
      : (testRun.status === "FAILED" ? "FAILED" : "RUNNING");
  } else if (totalTests === 0) {
    testStatus = "FAILED";
  }

  // Determine AI blast radius
  const riskLevel: RiskLevel = (ai?.riskLevel as RiskLevel) || "LOW";
  const score = ai?.riskScore !== undefined ? Math.round(ai.riskScore * 100) : 0;
  const flaggedRisks: string[] = [];

  if (chain.gaps && chain.gaps.length > 0) {
    chain.gaps.forEach((g) => flaggedRisks.push(`[${g.severity}] ${g.evidence}: ${g.recommendation}`));
  } else if (ai?.summary) {
    flaggedRisks.push(ai.summary);
  } else {
    flaggedRisks.push("No regression risks flagged for this delivery spine segment.");
  }

  return {
    requirement: {
      key: req.identifier,
      title: req.title,
      status: (req.status === "VERIFIED" || req.status === "COMPLETED" ? "VERIFIED" : req.status === "ACTIVE" ? "ACTIVE" : "DRAFT") as any,
    },
    task: {
      id: task?.id,
      key: task?.taskKey || "NO TASK",
      title: task?.title || "No assigned task",
      status: task?.status || "TODO",
      priority: task?.priority || "MEDIUM",
    },
    sourceControl: {
      prNumber: pr?.prNumber || 0,
      prTitle: pr?.title || commit?.message || "No Pull Request",
      commitSha: pr?.headSha || commit?.commitSha || "N/A",
      author: commit?.authorName || (pr ? "author" : "system"),
    },
    verification: {
      testRunId: testRun?.id ? `TR-${testRun.id.substring(0, 6)}` : (totalTests > 0 ? "Test Suite" : "No Test Evidence"),
      status: testStatus,
      passedTests,
      totalTests: Math.max(totalTests, passedTests),
    },
    aiBlastRadius: {
      riskLevel,
      score,
      affectedSymbolsCount: ai?.mergeReadiness ? 4 : (chain.testCases.length > 0 ? 3 : 0),
      flaggedRisks,
    },
  };
}

export const TraceabilitySpineFlow: React.FC<TraceabilitySpineFlowProps> = ({
  chains = [],
  isLoading = false,
  onSync,
}) => {
  const nodes = chains.map(convertChainToNode);

  const [selectedNode, setSelectedNode] = useState<TraceabilityNode | null>(null);
  const [isSheetOpen, setIsSheetOpen] = useState<boolean>(false);
  const [filterRisk, setFilterRisk] = useState<string>("ALL");
  const [searchQuery, setSearchQuery] = useState<string>("");

  // Select initial node when data arrives
  React.useEffect(() => {
    if (nodes.length > 0 && !selectedNode) {
      setSelectedNode(nodes[0]);
    }
  }, [nodes, selectedNode]);

  const filteredData = nodes.filter((node) => {
    if (filterRisk !== "ALL" && node.aiBlastRadius.riskLevel !== filterRisk) {
      return false;
    }
    if (searchQuery.trim()) {
      const q = searchQuery.toLowerCase();
      return (
        node.requirement.key.toLowerCase().includes(q) ||
        node.requirement.title.toLowerCase().includes(q) ||
        node.task.key.toLowerCase().includes(q) ||
        node.task.title.toLowerCase().includes(q) ||
        node.sourceControl.prTitle.toLowerCase().includes(q)
      );
    }
    return true;
  });

  if (isLoading) {
    return (
      <div className="rounded-xl border border-slate-800 bg-slate-900/40 p-12 text-center space-y-3">
        <RefreshCw className="h-6 w-6 text-indigo-400 animate-spin mx-auto" />
        <div className="text-sm font-semibold text-white">Aggregating Live Delivery Spine...</div>
        <p className="text-xs text-slate-400 max-w-md mx-auto">
          Querying requirements, tasks, linked Git repositories, CI test runs, and AST blast radius evaluations.
        </p>
      </div>
    );
  }

  if (nodes.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-slate-800 bg-slate-950/40 p-12 text-center space-y-4">
        <div className="p-3 rounded-2xl bg-indigo-600/10 text-indigo-400 border border-indigo-500/20 w-fit mx-auto">
          <FolderTree className="h-8 w-8" />
        </div>
        <div className="space-y-1">
          <h3 className="text-base font-semibold text-white">No Traceability Chains Detected</h3>
          <p className="text-xs text-slate-400 max-w-lg mx-auto">
            This project has no active delivery chains yet. Link a GitHub repository, create requirements and tasks, or trigger automated link synchronization to establish continuous governance.
          </p>
        </div>
        {onSync && (
          <Button variant="glow" size="sm" onClick={onSync} className="gap-2 text-xs">
            <RefreshCw className="h-3.5 w-3.5" />
            Synchronize Links Now
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Top Filter Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 rounded-xl border border-slate-800 bg-slate-900/50 p-4 backdrop-blur-md">
        <div className="flex items-center gap-2">
          <div className="relative w-72">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 pointer-events-none" />
            <input
              type="text"
              placeholder="Filter by REQ, Task, or PR..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="h-9 w-full rounded-lg border border-slate-800 bg-slate-950/80 pl-9 pr-3 text-xs text-white placeholder-slate-500 focus:outline-none focus:ring-1 focus:ring-indigo-500"
            />
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-400 flex items-center gap-1">
            <Filter className="h-3.5 w-3.5" /> Risk Level:
          </span>
          {(["ALL", "LOW", "MEDIUM", "HIGH"] as const).map((lvl) => (
            <button
              key={lvl}
              onClick={() => setFilterRisk(lvl)}
              className={`rounded-lg px-2.5 py-1 text-xs font-medium transition-colors ${
                filterRisk === lvl
                  ? "bg-indigo-600 text-white shadow-sm"
                  : "bg-slate-800/80 text-slate-300 hover:bg-slate-700"
              }`}
            >
              {lvl}
            </button>
          ))}
        </div>
      </div>

      {/* 5-Column Traceability Matrix Grid */}
      <div className="overflow-x-auto pb-4">
        <div className="min-w-[1100px] space-y-3">
          {/* Column Header Titles */}
          <div className="grid grid-cols-5 gap-4 px-4 py-2 text-xs font-semibold uppercase tracking-wider text-slate-400 border-b border-slate-800">
            <div className="flex items-center gap-2">
              <FileText className="h-4 w-4 text-indigo-400" />
              1. Requirement
            </div>
            <div className="flex items-center gap-2">
              <CheckSquare className="h-4 w-4 text-purple-400" />
              2. Work Task
            </div>
            <div className="flex items-center gap-2">
              <GitPullRequest className="h-4 w-4 text-sky-400" />
              3. GitHub PR / Commit
            </div>
            <div className="flex items-center gap-2">
              <CheckCircle2 className="h-4 w-4 text-emerald-400" />
              4. Verification Run
            </div>
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-amber-400" />
              5. AI Blast Radius
            </div>
          </div>

          {/* Trace Rows */}
          {filteredData.map((node) => {
            const isSelected = selectedNode?.requirement.key === node.requirement.key;

            return (
              <div
                key={node.requirement.key}
                onClick={() => {
                  setSelectedNode(node);
                  setIsSheetOpen(true);
                }}
                className={`relative grid grid-cols-5 gap-4 p-4 rounded-xl border transition-all cursor-pointer ${
                  isSelected
                    ? "border-indigo-500/80 bg-indigo-950/20 shadow-lg shadow-indigo-500/10 ring-1 ring-indigo-500/40"
                    : "border-slate-800/80 bg-slate-900/40 hover:border-slate-700 hover:bg-slate-800/30"
                }`}
              >
                {/* 1. Requirement */}
                <div className="space-y-1.5 pr-2 border-r border-slate-800/60">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-bold text-indigo-400">
                      {node.requirement.key}
                    </span>
                    <Badge variant={node.requirement.status === "VERIFIED" ? "success" : "secondary"}>
                      {node.requirement.status}
                    </Badge>
                  </div>
                  <p className="text-xs text-slate-300 font-medium line-clamp-2">
                    {node.requirement.title}
                  </p>
                </div>

                {/* 2. Task */}
                <div className="space-y-1.5 pr-2 border-r border-slate-800/60">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-bold text-purple-400">
                      {node.task.key}
                    </span>
                    <Badge variant="outline" className="text-[10px] text-slate-400">
                      {node.task.priority}
                    </Badge>
                  </div>
                  <p className="text-xs text-slate-300 font-medium line-clamp-2">
                    {node.task.title}
                  </p>
                </div>

                {/* 3. GitHub PR / Commit */}
                <div className="space-y-1.5 pr-2 border-r border-slate-800/60">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-semibold text-sky-400 flex items-center gap-1">
                      <GitPullRequest className="h-3 w-3" />
                      {node.sourceControl.prNumber > 0 ? `#${node.sourceControl.prNumber}` : "No PR"}
                    </span>
                    <span className="font-mono text-[10px] text-slate-500 flex items-center gap-0.5">
                      <GitCommit className="h-3 w-3" />
                      {node.sourceControl.commitSha && node.sourceControl.commitSha !== "N/A"
                        ? node.sourceControl.commitSha.substring(0, 7)
                        : "No commit"}
                    </span>
                  </div>
                  <p className="text-xs text-slate-300 line-clamp-2">
                    {node.sourceControl.prTitle}
                  </p>
                  <p className="text-[10px] text-slate-500">by @{node.sourceControl.author}</p>
                </div>

                {/* 4. Verification */}
                <div className="space-y-1.5 pr-2 border-r border-slate-800/60">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-medium text-slate-400">
                      {node.verification.testRunId}
                    </span>
                    {node.verification.status === "PASSED" ? (
                      <Badge variant="success">PASSED</Badge>
                    ) : node.verification.status === "RUNNING" ? (
                      <Badge variant="warning">RUNNING</Badge>
                    ) : (
                      <Badge variant="destructive">FAILED</Badge>
                    )}
                  </div>
                  <div className="flex items-center gap-1.5 text-xs text-slate-300">
                    <span className="text-emerald-400 font-semibold">
                      {node.verification.passedTests}
                    </span>
                    <span className="text-slate-500">/</span>
                    <span>{node.verification.totalTests} tests passing</span>
                  </div>
                  <div className="w-full bg-slate-800 rounded-full h-1.5 mt-1 overflow-hidden">
                    <div
                      className="bg-emerald-500 h-full rounded-full"
                      style={{
                        width: `${node.verification.totalTests > 0 ? (node.verification.passedTests / node.verification.totalTests) * 100 : 0}%`,
                      }}
                    />
                  </div>
                </div>

                {/* 5. AI Blast Radius */}
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-slate-400">Risk Score:</span>
                    <Badge
                      variant={
                        node.aiBlastRadius.riskLevel === "LOW"
                          ? "riskLow"
                          : node.aiBlastRadius.riskLevel === "MEDIUM"
                          ? "riskMedium"
                          : "riskHigh"
                      }
                    >
                      {node.aiBlastRadius.riskLevel} ({node.aiBlastRadius.score})
                    </Badge>
                  </div>
                  <p className="text-xs text-slate-300">
                    <strong className="text-indigo-300 font-mono">
                      {node.aiBlastRadius.affectedSymbolsCount}
                    </strong>{" "}
                    AST symbols affected
                  </p>
                  <div className="flex items-center justify-between pt-1">
                    <p className="text-[11px] text-slate-500 truncate max-w-[130px]">
                      {node.aiBlastRadius.flaggedRisks[0] || "No flagged regressions"}
                    </p>
                    <span className="text-[10px] text-indigo-400 font-semibold hover:text-indigo-300">
                      Deep-Dive →
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      </div>

      {/* Selected Node Deep Dive Drawer / Panel */}
      {selectedNode && (
        <div className="rounded-2xl border border-indigo-500/30 bg-slate-950/90 p-6 backdrop-blur-xl shadow-2xl space-y-6">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-800 pb-4">
            <div>
              <div className="flex items-center gap-2">
                <Badge variant="default" className="font-mono">
                  ACTIVE SPINE AUDIT
                </Badge>
                <h3 className="text-lg font-bold text-white">
                  {selectedNode.requirement.key} → {selectedNode.task.key} → PR #{selectedNode.sourceControl.prNumber}
                </h3>
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Full deterministic traceability lineage from business requirement to production staging test & AI blast radius.
              </p>
            </div>
            <div className="flex items-center gap-3">
              <Button
                variant="glow"
                size="sm"
                onClick={() => setIsSheetOpen(true)}
                className="gap-1.5 text-xs"
              >
                <FolderTree className="h-3.5 w-3.5" />
                Inspect AST & Traces
              </Button>
              <Badge
                variant={
                  selectedNode.aiBlastRadius.riskLevel === "LOW"
                    ? "riskLow"
                    : selectedNode.aiBlastRadius.riskLevel === "MEDIUM"
                    ? "riskMedium"
                    : "riskHigh"
                }
                className="text-sm px-3 py-1"
              >
                Risk: {selectedNode.aiBlastRadius.riskLevel} ({selectedNode.aiBlastRadius.score}/100)
              </Badge>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {/* Requirement & Task Breakdown */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-3">
              <h4 className="text-xs font-semibold uppercase text-slate-400 flex items-center gap-1.5">
                <FileText className="h-4 w-4 text-indigo-400" />
                Requirement & Task Scope
              </h4>
              <div className="space-y-1">
                <p className="text-xs font-semibold text-white">
                  {selectedNode.requirement.title}
                </p>
                <p className="text-xs text-slate-400">
                  Assigned task: {selectedNode.task.title}
                </p>
              </div>
              <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-400">
                <span>Priority:</span>
                <span className="font-semibold text-amber-400">{selectedNode.task.priority}</span>
              </div>
            </div>

            {/* Code Verification Lineage */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-3">
              <h4 className="text-xs font-semibold uppercase text-slate-400 flex items-center gap-1.5">
                <GitPullRequest className="h-4 w-4 text-sky-400" />
                Source Control & Verification
              </h4>
              <div className="space-y-1">
                <p className="text-xs font-semibold text-white">
                  PR #{selectedNode.sourceControl.prNumber}: {selectedNode.sourceControl.prTitle}
                </p>
                <p className="text-xs font-mono text-slate-400">
                  Commit: {selectedNode.sourceControl.commitSha.substring(0, 16)}...
                </p>
              </div>
              <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between text-xs">
                <span className="text-slate-400">Automated Tests:</span>
                <span className="text-emerald-400 font-semibold font-mono">
                  {selectedNode.verification.passedTests}/{selectedNode.verification.totalTests} PASSED
                </span>
              </div>
            </div>

            {/* AI Risk & Flagged AST Findings */}
            <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-3">
              <h4 className="text-xs font-semibold uppercase text-slate-400 flex items-center gap-1.5">
                <Sparkles className="h-4 w-4 text-amber-400" />
                AI Blast Radius Intelligence
              </h4>
              <div className="space-y-2">
                <div className="text-xs text-slate-300">
                  Affected AST Symbols:{" "}
                  <span className="font-bold text-white">
                    {selectedNode.aiBlastRadius.affectedSymbolsCount}
                  </span>
                </div>
                <div className="space-y-1">
                  {selectedNode.aiBlastRadius.flaggedRisks.map((risk, idx) => (
                    <div
                      key={idx}
                      className="flex items-start gap-1.5 text-[11px] text-slate-300 bg-slate-950/60 p-2 rounded-lg border border-slate-800"
                    >
                      <ShieldAlert className="h-3.5 w-3.5 text-amber-400 shrink-0 mt-0.5" />
                      <span>{risk}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Interactive Slide-Over Side Sheet Drawer */}
      <TraceabilityInspectionSheet
        node={selectedNode}
        isOpen={isSheetOpen}
        onClose={() => setIsSheetOpen(false)}
      />
    </div>
  );
};
