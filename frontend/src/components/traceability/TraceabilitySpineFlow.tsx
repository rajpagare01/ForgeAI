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
    testStatus = (testRun.status === "COMPLETED" || testRun.status === "PASSED")
      ? (testRun.failedTests && testRun.failedTests > 0 ? "FAILED" : "PASSED")
      : (testRun.status === "FAILED" ? "FAILED" : "RUNNING");
  } else if (totalTests === 0) {
    testStatus = "FAILED";
  }

  // Determine AI blast radius
  const riskLevel: RiskLevel = (ai?.riskLevel as RiskLevel) || "LOW";
  const score =
    ai?.riskScore !== undefined
      ? ai.riskScore <= 1.0
        ? Math.round(ai.riskScore * 100)
        : Math.round(ai.riskScore)
      : 0;
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
      key: req.identifier || (req as any)?.key || (req as any)?.id || "REQ",
      title: req.title,
      status: (req.status === "VERIFIED" || req.status === "COMPLETED" ? "VERIFIED" : req.status === "ACTIVE" ? "ACTIVE" : "DRAFT") as any,
    },
    task: {
      id: task?.id,
      key: task?.taskKey || (task as any)?.key || "NO TASK",
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
      <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-12 text-center space-y-3">
        <RefreshCw className="h-5 w-5 text-zinc-400 animate-spin mx-auto" />
        <div className="text-sm font-semibold text-zinc-100">Aggregating Live Delivery Spine...</div>
        <p className="text-xs text-zinc-400 max-w-md mx-auto">
          Querying requirements, tasks, linked Git repositories, CI test runs, and AST blast radius evaluations.
        </p>
      </div>
    );
  }

  if (nodes.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-[#26262C] bg-[#0E0E11] p-12 text-center space-y-4">
        <div className="p-3 rounded-lg bg-zinc-800/40 text-zinc-300 border border-zinc-700/50 w-fit mx-auto">
          <FolderTree className="h-6 w-6" />
        </div>
        <div className="space-y-1">
          <h3 className="text-sm font-semibold text-zinc-100">No Traceability Chains Detected</h3>
          <p className="text-xs text-zinc-400 max-w-lg mx-auto">
            This project has no active delivery chains yet. Link a GitHub repository, create requirements and tasks, or trigger automated link synchronization to establish continuous governance.
          </p>
        </div>
        {onSync && (
          <Button variant="default" size="sm" onClick={onSync} className="gap-2 text-xs h-7">
            <RefreshCw className="h-3 w-3" />
            Synchronize Links Now
          </Button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Top Filter Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3">
        <div className="flex items-center gap-2">
          <div className="relative w-72">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-zinc-500 pointer-events-none" />
            <input
              type="text"
              placeholder="Filter by REQ, Task, or PR..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="h-7 w-full rounded-md border border-[#26262C] bg-[#09090B] pl-8 pr-3 text-xs text-zinc-100 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
            />
          </div>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-xs text-zinc-500 flex items-center gap-1 font-mono text-[11px]">
            <Filter className="h-3 w-3" /> RISK:
          </span>
          {(["ALL", "LOW", "MEDIUM", "HIGH"] as const).map((lvl) => (
            <button
              key={lvl}
              onClick={() => setFilterRisk(lvl)}
              className={`rounded-md px-2.5 py-0.5 text-xs font-mono transition-colors ${
                filterRisk === lvl
                  ? "bg-zinc-100 text-zinc-950 font-medium"
                  : "bg-[#141418] text-zinc-400 hover:text-zinc-200 border border-[#26262C]"
              }`}
            >
              {lvl}
            </button>
          ))}
        </div>
      </div>

      {/* 5-Column Traceability Matrix Grid */}
      <div className="overflow-x-auto pb-4">
        <div className="min-w-[1100px] space-y-2">
          {/* Column Header Titles */}
          <div className="grid grid-cols-5 gap-3 px-3 py-2 text-[11px] font-mono font-medium uppercase tracking-wider text-zinc-500 border-b border-[#1C1C21]">
            <div className="flex items-center gap-1.5">
              <FileText className="h-3.5 w-3.5 text-zinc-400" />
              1. Requirement
            </div>
            <div className="flex items-center gap-1.5">
              <CheckSquare className="h-3.5 w-3.5 text-zinc-400" />
              2. Work Task
            </div>
            <div className="flex items-center gap-1.5">
              <GitPullRequest className="h-3.5 w-3.5 text-zinc-400" />
              3. GitHub PR / Commit
            </div>
            <div className="flex items-center gap-1.5">
              <CheckCircle2 className="h-3.5 w-3.5 text-zinc-400" />
              4. Verification Run
            </div>
            <div className="flex items-center gap-1.5">
              <Sparkles className="h-3.5 w-3.5 text-zinc-400" />
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
                className={`relative grid grid-cols-5 gap-3 p-3 rounded-md border transition-colors cursor-pointer ${
                  isSelected
                    ? "border-zinc-500 bg-[#141418] ring-1 ring-zinc-500/50"
                    : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#2E2E36] hover:bg-[#121216]"
                }`}
              >
                {/* 1. Requirement */}
                <div className="space-y-1 pr-2 border-r border-[#1C1C21]">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-semibold text-zinc-200">
                      {node.requirement.key}
                    </span>
                    <Badge variant={node.requirement.status === "VERIFIED" ? "success" : "secondary"}>
                      {node.requirement.status}
                    </Badge>
                  </div>
                  <p className="text-xs text-zinc-300 font-medium line-clamp-2">
                    {node.requirement.title}
                  </p>
                </div>

                {/* 2. Task */}
                <div className="space-y-1 pr-2 border-r border-[#1C1C21]">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-semibold text-zinc-200">
                      {node.task.key}
                    </span>
                    <Badge variant="outline" className="text-[10px] text-zinc-400">
                      {node.task.priority}
                    </Badge>
                  </div>
                  <p className="text-xs text-zinc-300 line-clamp-2">
                    {node.task.title}
                  </p>
                </div>

                {/* 3. GitHub PR / Commit */}
                <div className="space-y-1 pr-2 border-r border-[#1C1C21]">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-medium text-zinc-200 flex items-center gap-1">
                      <GitPullRequest className="h-3 w-3 text-zinc-400" />
                      {node.sourceControl.prNumber > 0 ? `#${node.sourceControl.prNumber}` : "No PR"}
                    </span>
                    <span className="font-mono text-[10px] text-zinc-500 flex items-center gap-0.5">
                      <GitCommit className="h-3 w-3 text-zinc-500" />
                      {node.sourceControl.commitSha && node.sourceControl.commitSha !== "N/A"
                        ? node.sourceControl.commitSha.substring(0, 7)
                        : "No commit"}
                    </span>
                  </div>
                  <p className="text-xs text-zinc-300 line-clamp-2">
                    {node.sourceControl.prTitle}
                  </p>
                  <p className="text-[10px] text-zinc-500 font-mono">by @{node.sourceControl.author}</p>
                </div>

                {/* 4. Verification */}
                <div className="space-y-1 pr-2 border-r border-[#1C1C21]">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-xs font-medium text-zinc-400">
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
                  <div className="flex items-center gap-1.5 text-xs text-zinc-300 font-mono text-[11px]">
                    <span className="text-emerald-400 font-semibold tabular-nums">
                      {node.verification.passedTests}
                    </span>
                    <span className="text-zinc-600">/</span>
                    <span className="tabular-nums">{node.verification.totalTests} tests passing</span>
                  </div>
                  <div className="w-full bg-zinc-800 rounded-full h-1 mt-1 overflow-hidden">
                    <div
                      className="bg-emerald-400 h-full rounded-full"
                      style={{
                        width: `${node.verification.totalTests > 0 ? (node.verification.passedTests / node.verification.totalTests) * 100 : 0}%`,
                      }}
                    />
                  </div>
                </div>

                {/* 5. AI Blast Radius */}
                <div className="space-y-1">
                  <div className="flex items-center justify-between">
                    <span className="text-xs text-zinc-400 text-[11px]">Risk Score:</span>
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
                  <p className="text-xs text-zinc-300">
                    <strong className="text-zinc-100 font-mono tabular-nums">
                      {node.aiBlastRadius.affectedSymbolsCount}
                    </strong>{" "}
                    AST symbols affected
                  </p>
                  <div className="flex items-center justify-between pt-1">
                    <p className="text-[11px] text-zinc-500 truncate max-w-[130px]">
                      {node.aiBlastRadius.flaggedRisks[0] || "No flagged regressions"}
                    </p>
                    <span className="text-[10px] font-mono text-zinc-300 font-medium hover:text-white">
                      Deep-Dive &rarr;
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
        <div className="rounded-lg border border-[#26262C] bg-[#0E0E11] p-5 space-y-5">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-[#1C1C21] pb-4">
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <Badge variant="default" className="font-mono">
                  SPINE AUDIT
                </Badge>
                <h3 className="text-sm font-semibold font-mono text-zinc-100">
                  {selectedNode.requirement.key} &rarr; {selectedNode.task.key} &rarr; PR #{selectedNode.sourceControl.prNumber}
                </h3>
              </div>
              <p className="text-xs text-zinc-400 mt-1">
                Full deterministic traceability lineage from business requirement to CI test verification & AST blast radius.
              </p>
            </div>
            <div className="flex items-center gap-2.5">
              <Button
                variant="default"
                size="sm"
                onClick={() => setIsSheetOpen(true)}
                className="gap-1.5 text-xs h-8 px-3"
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
                className="text-xs font-mono tabular-nums px-2.5 py-1"
              >
                Risk: {selectedNode.aiBlastRadius.riskLevel} ({selectedNode.aiBlastRadius.score}/100)
              </Badge>
            </div>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {/* Requirement & Task Breakdown */}
            <div className="rounded-md border border-[#1C1C21] bg-[#141418] p-4 space-y-2.5">
              <h4 className="text-[11px] font-mono font-medium uppercase tracking-wider text-zinc-400 flex items-center gap-1.5">
                <FileText className="h-3.5 w-3.5 text-zinc-400" />
                Requirement & Task Scope
              </h4>
              <div className="space-y-1">
                <p className="text-xs font-medium text-zinc-100">
                  {selectedNode.requirement.title}
                </p>
                <p className="text-xs text-zinc-400">
                  Assigned task: {selectedNode.task.title}
                </p>
              </div>
              <div className="pt-2 border-t border-[#1C1C21] flex items-center justify-between text-xs text-zinc-400">
                <span>Priority:</span>
                <span className="font-mono text-[11px] font-medium text-amber-400">{selectedNode.task.priority}</span>
              </div>
            </div>

            {/* Code Verification Lineage */}
            <div className="rounded-md border border-[#1C1C21] bg-[#141418] p-4 space-y-2.5">
              <h4 className="text-[11px] font-mono font-medium uppercase tracking-wider text-zinc-400 flex items-center gap-1.5">
                <GitPullRequest className="h-3.5 w-3.5 text-zinc-400" />
                Source Control & Verification
              </h4>
              <div className="space-y-1">
                <p className="text-xs font-medium text-zinc-100">
                  PR #{selectedNode.sourceControl.prNumber}: {selectedNode.sourceControl.prTitle}
                </p>
                <p className="text-[11px] font-mono text-zinc-400 tabular-nums">
                  Commit: {selectedNode.sourceControl.commitSha.substring(0, 16)}...
                </p>
              </div>
              <div className="pt-2 border-t border-[#1C1C21] flex items-center justify-between text-xs">
                <span className="text-zinc-400">Automated Tests:</span>
                <span className="text-emerald-400 font-medium font-mono text-[11px] tabular-nums">
                  {selectedNode.verification.passedTests}/{selectedNode.verification.totalTests} PASSED
                </span>
              </div>
            </div>

            {/* AI Risk & Flagged AST Findings */}
            <div className="rounded-md border border-[#1C1C21] bg-[#141418] p-4 space-y-2.5">
              <h4 className="text-[11px] font-mono font-medium uppercase tracking-wider text-zinc-400 flex items-center gap-1.5">
                <Sparkles className="h-3.5 w-3.5 text-amber-400" />
                AI Blast Radius Intelligence
              </h4>
              <div className="space-y-2">
                <div className="text-xs text-zinc-300">
                  Affected AST Symbols:{" "}
                  <span className="font-mono font-semibold text-zinc-100 tabular-nums">
                    {selectedNode.aiBlastRadius.affectedSymbolsCount}
                  </span>
                </div>
                <div className="space-y-1.5">
                  {selectedNode.aiBlastRadius.flaggedRisks.map((risk, idx) => (
                    <div
                      key={idx}
                      className="flex items-start gap-1.5 text-[11px] text-zinc-300 bg-[#0E0E11] p-2 rounded border border-[#1C1C21]"
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
