"use client";

import React, { useState, useEffect } from "react";
import { TraceabilityNode, TaskTraceabilityResponse } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { apiClient } from "@/lib/api-client";
import {
  X,
  FileText,
  CheckSquare,
  GitPullRequest,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Sparkles,
  GitCommit,
  ShieldAlert,
  ArrowRight,
  Copy,
  Check,
  FolderTree,
  Terminal,
  Link2,
  Activity,
  Layers,
  ShieldCheck,
  ExternalLink,
  RefreshCw,
  Info,
} from "lucide-react";

interface TraceabilityInspectionSheetProps {
  node: TraceabilityNode | null;
  isOpen: boolean;
  onClose: () => void;
}

export const TraceabilityInspectionSheet: React.FC<TraceabilityInspectionSheetProps> = ({
  node,
  isOpen,
  onClose,
}) => {
  const [activeTab, setActiveTab] = useState<"ast" | "junit" | "git" | "taskTrace">("ast");
  const [copiedTrace, setCopiedTrace] = useState<boolean>(false);
  const [taskTrace, setTaskTrace] = useState<TaskTraceabilityResponse | null>(null);
  const [loadingTaskTrace, setLoadingTaskTrace] = useState<boolean>(false);
  const [taskTraceError, setTaskTraceError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen && node?.task?.id) {
      setLoadingTaskTrace(true);
      setTaskTraceError(null);
      apiClient.getTaskTraceability<TaskTraceabilityResponse>(node.task.id)
        .then((data) => {
          setTaskTrace(data);
          setLoadingTaskTrace(false);
        })
        .catch((err) => {
          setTaskTraceError(err.message || "Failed to fetch task traceability");
          setLoadingTaskTrace(false);
        });
    } else {
      setTaskTrace(null);
      setLoadingTaskTrace(false);
      setTaskTraceError(null);
    }
  }, [isOpen, node?.task?.id]);

  if (!isOpen || !node) return null;

  const hasPr = node.sourceControl.prNumber > 0;
  const hasTests = node.verification.totalTests > 0;
  const hasCommit = node.sourceControl.commitSha && node.sourceControl.commitSha !== "N/A";

  const handleCopyTrace = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedTrace(true);
    setTimeout(() => setCopiedTrace(false), 2000);
  };

  const verificationConsoleLog = hasTests
    ? `[INFO] Verification Test Suite: ${node.verification.testRunId}
[INFO] Total Tests Executed: ${node.verification.totalTests}
[INFO] Passed: ${node.verification.passedTests}
[INFO] Failures: ${node.verification.totalTests - node.verification.passedTests}
[INFO] Status: ${node.verification.status}
[INFO] Trace Correlation: REQ-${node.requirement.key} -> TASK-${node.task.key}`
    : "No test execution runs or JUnit results registered for this requirement spine.";

  return (
    <div className="fixed inset-0 z-50 overflow-hidden">
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/80 backdrop-blur-xs transition-opacity"
        onClick={onClose}
      />

      {/* Slide-over Drawer Panel */}
      <div className="fixed inset-y-0 right-0 flex max-w-full pl-10">
        <div className="relative w-screen max-w-2xl bg-[#0E0E11] border-l border-[#1C1C21] shadow-2xl flex flex-col">
          {/* Header */}
          <div className="p-4 border-b border-[#1C1C21] bg-[#09090B]">
            <div className="flex items-start justify-between">
              <div className="space-y-1">
                <div className="flex items-center gap-1.5">
                  <Badge variant="default" className="font-mono text-[11px]">
                    {node.requirement.key}
                  </Badge>
                  <ArrowRight className="h-3 w-3 text-zinc-600" />
                  <Badge variant="outline" className="font-mono text-[11px] text-zinc-300 border-[#26262C]">
                    {node.task.key}
                  </Badge>
                  <ArrowRight className="h-3 w-3 text-zinc-600" />
                  <span className="font-mono text-[11px] text-zinc-400">
                    {hasPr ? `PR #${node.sourceControl.prNumber}` : "No PR Linked"}
                  </span>
                </div>
                <h2 className="text-sm font-semibold text-zinc-100 tracking-tight mt-1">
                  {node.requirement.title}
                </h2>
                <p className="text-xs text-zinc-400">
                  Continuous lineage verification for requirement: {node.requirement.key}
                </p>
              </div>

              <Button
                variant="ghost"
                size="icon"
                onClick={onClose}
                className="h-7 w-7 text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800"
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            {/* Quick Status Bar */}
            <div className="grid grid-cols-4 gap-3 mt-3 pt-3 border-t border-[#1C1C21]">
              <div>
                <span className="text-[10px] text-zinc-500 uppercase tracking-wider block font-medium">Requirement</span>
                <span className="text-xs font-mono font-medium text-zinc-200">{node.requirement.status}</span>
              </div>
              <div>
                <span className="text-[10px] text-zinc-500 uppercase tracking-wider block font-medium">Task Status</span>
                <span className="text-xs font-mono font-medium text-zinc-200">{node.task.status}</span>
              </div>
              <div>
                <span className="text-[10px] text-zinc-500 uppercase tracking-wider block font-medium">Verification</span>
                <span
                  className={`text-xs font-mono font-medium ${node.verification.status === "PASSED"
                    ? "text-emerald-400"
                    : node.verification.status === "RUNNING"
                      ? "text-amber-400"
                      : "text-rose-400"
                    }`}
                >
                  {node.verification.status}
                </span>
              </div>
              <div>
                <span className="text-[10px] text-zinc-500 uppercase tracking-wider block font-medium">Risk Score</span>
                <span
                  className={`text-xs font-mono font-medium ${node.aiBlastRadius.riskLevel === "LOW"
                    ? "text-emerald-400"
                    : node.aiBlastRadius.riskLevel === "MEDIUM"
                      ? "text-amber-400"
                      : "text-rose-400"
                    }`}
                >
                  {node.aiBlastRadius.riskLevel} ({node.aiBlastRadius.score})
                </span>
              </div>
            </div>

            {/* Drawer Tabs */}
            <div className="flex gap-1.5 mt-3 pt-3 border-t border-[#1C1C21] flex-wrap">
              <button
                onClick={() => setActiveTab("ast")}
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${activeTab === "ast"
                  ? "bg-zinc-100 text-zinc-900 font-semibold"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/60"
                  }`}
              >
                <FolderTree className="h-3.5 w-3.5" />
                AST & Risk Analysis
              </button>
              <button
                onClick={() => setActiveTab("junit")}
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${activeTab === "junit"
                  ? "bg-zinc-100 text-zinc-900 font-semibold"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/60"
                  }`}
              >
                <Terminal className="h-3.5 w-3.5" />
                Verification Output
              </button>
              <button
                onClick={() => setActiveTab("git")}
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${activeTab === "git"
                  ? "bg-zinc-100 text-zinc-900 font-semibold"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/60"
                  }`}
              >
                <GitPullRequest className="h-3.5 w-3.5" />
                PR & Commit Lineage
              </button>
              <button
                onClick={() => setActiveTab("taskTrace")}
                className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium transition-colors ${activeTab === "taskTrace"
                  ? "bg-zinc-100 text-zinc-900 font-semibold"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800/60"
                  }`}
              >
                <Link2 className="h-3.5 w-3.5" />
                Task Continuous Traceability
              </button>
            </div>
          </div>

          {/* Drawer Body */}
          <div className="flex-1 overflow-y-auto p-5 space-y-5">
            {/* Tab 1: AST & Risk Analysis */}
            {activeTab === "ast" && (
              <div className="space-y-5">
                <div>
                  <h3 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider mb-2 flex items-center gap-2">
                    <FolderTree className="h-4 w-4 text-zinc-300" />
                    Tree-Sitter Semantic AST Analysis
                  </h3>
                  <p className="text-xs text-zinc-400 mb-3">
                    Semantic blast radius evaluation for {node.requirement.key} implementation.
                  </p>

                  <div className="rounded-lg border border-[#1C1C21] bg-[#141418] p-4 font-mono text-xs space-y-2.5">
                    <div className="flex items-center justify-between border-b border-[#1C1C21] pb-2">
                      <span className="text-zinc-400">Affected AST Symbols:</span>
                      <span className="text-zinc-100 font-bold">{node.aiBlastRadius.affectedSymbolsCount}</span>
                    </div>
                    <div className="flex items-center justify-between border-b border-[#1C1C21] pb-2">
                      <span className="text-zinc-400">Risk Assessment:</span>
                      <Badge
                        variant={
                          node.aiBlastRadius.riskLevel === "LOW"
                            ? "riskLow"
                            : node.aiBlastRadius.riskLevel === "MEDIUM"
                              ? "riskMedium"
                              : "riskHigh"
                        }
                      >
                        {node.aiBlastRadius.riskLevel} ({node.aiBlastRadius.score}/100)
                      </Badge>
                    </div>
                    <div className="flex items-center justify-between">
                      <span className="text-zinc-400">Target Commit:</span>
                      <span className="text-zinc-300">
                        {hasCommit ? node.sourceControl.commitSha.substring(0, 12) : "No commit associated"}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Flagged Blast Radius Invariants */}
                <div className="space-y-2.5">
                  <h4 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider flex items-center gap-2">
                    <ShieldAlert className="h-4 w-4 text-amber-400" />
                    Automated Invariant & Risk Safeguards
                  </h4>
                  <div className="space-y-2">
                    {node.aiBlastRadius.flaggedRisks.map((risk, idx) => (
                      <div
                        key={idx}
                        className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418] text-xs text-zinc-300 flex items-start gap-2.5"
                      >
                        <Sparkles className="h-4 w-4 text-zinc-400 shrink-0 mt-0.5" />
                        <span className="leading-relaxed">{risk}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Tab 2: JUnit & Verification Log */}
            {activeTab === "junit" && (
              <div className="space-y-4">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider flex items-center gap-2">
                    <Terminal className="h-4 w-4 text-emerald-400" />
                    Verification Console Logs
                  </h3>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="text-xs text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800 h-7 gap-1"
                    onClick={() => handleCopyTrace(verificationConsoleLog)}
                  >
                    {copiedTrace ? (
                      <>
                        <Check className="h-3.5 w-3.5 text-emerald-400" />
                        <span>Copied</span>
                      </>
                    ) : (
                      <>
                        <Copy className="h-3.5 w-3.5" />
                        <span>Copy Logs</span>
                      </>
                    )}
                  </Button>
                </div>

                {/* Terminal Console View */}
                <div className="rounded-lg border border-[#1C1C21] bg-[#09090B] p-4 font-mono text-xs overflow-x-auto">
                  <div className="flex items-center gap-1.5 pb-2.5 mb-2.5 border-b border-[#1C1C21] text-zinc-500">
                    <div className="h-2 w-2 rounded-full bg-zinc-600" />
                    <div className="h-2 w-2 rounded-full bg-zinc-600" />
                    <div className="h-2 w-2 rounded-full bg-zinc-600" />
                    <span className="ml-2 text-[10px] text-zinc-500">junit-trace-stream.log</span>
                  </div>
                  <pre className="text-zinc-300 whitespace-pre leading-relaxed">
                    {verificationConsoleLog}
                  </pre>
                </div>

                {/* Metrics Breakdown */}
                <div className="grid grid-cols-3 gap-3">
                  <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418] text-center">
                    <div className="text-xs text-zinc-400">Total Executed</div>
                    <div className="text-lg font-bold font-mono text-zinc-100 mt-0.5">
                      {node.verification.totalTests}
                    </div>
                  </div>
                  <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418] text-center">
                    <div className="text-xs text-zinc-400">Passed</div>
                    <div className="text-lg font-bold font-mono text-emerald-400 mt-0.5">
                      {node.verification.passedTests}
                    </div>
                  </div>
                  <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418] text-center">
                    <div className="text-xs text-zinc-400">Failures / Errors</div>
                    <div
                      className={`text-lg font-bold font-mono mt-0.5 ${node.verification.totalTests - node.verification.passedTests > 0
                        ? "text-rose-400"
                        : "text-zinc-400"
                        }`}
                    >
                      {node.verification.totalTests - node.verification.passedTests}
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* Tab 3: PR & Commit Lineage */}
            {activeTab === "git" && (
              <div className="space-y-5">
                <div>
                  <h3 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider mb-2.5 flex items-center gap-2">
                    <GitPullRequest className="h-4 w-4 text-zinc-300" />
                    GitHub Source Control Lineage
                  </h3>

                  <div className="rounded-lg border border-[#1C1C21] bg-[#141418] p-4 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-sm font-bold text-zinc-100">
                        {hasPr ? `PR #${node.sourceControl.prNumber}` : "No Pull Request"}
                      </span>
                      <Badge variant="outline" className="font-mono text-[10px]">
                        {hasPr ? "LINKED" : "UNLINKED"}
                      </Badge>
                    </div>
                    <p className="text-sm font-medium text-zinc-200">
                      {node.sourceControl.prTitle}
                    </p>
                    <div className="flex items-center justify-between text-xs text-zinc-400 pt-2 border-t border-[#1C1C21]">
                      <span>Author: @{node.sourceControl.author}</span>
                      <span className="font-mono flex items-center gap-1 text-zinc-300">
                        <GitCommit className="h-3.5 w-3.5 text-zinc-500" />
                        {hasCommit ? node.sourceControl.commitSha.substring(0, 10) : "N/A"}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="rounded-lg border border-[#1C1C21] bg-[#09090B] p-4 space-y-2">
                  <div className="text-xs font-semibold text-zinc-300">Trace Correlation Signature</div>
                  <div className="font-mono text-[11px] text-zinc-300 break-all bg-[#141418] p-2.5 rounded border border-[#1C1C21]">
                    {hasCommit
                      ? `00-${node.sourceControl.commitSha.padEnd(32, "0").substring(0, 32)}-${node.sourceControl.commitSha.padEnd(16, "0").substring(0, 16)}-01`
                      : "00-00000000000000000000000000000000-0000000000000000-00"}
                  </div>
                  <p className="text-[11px] text-zinc-500">
                    W3C traceparent header injected across distributed microservices.
                  </p>
                </div>
              </div>
            )}

            {/* Tab 4: Task Continuous Traceability */}
            {activeTab === "taskTrace" && (
              <div className="space-y-5">
                {/* Degradation / Outage Notification */}
                {taskTrace && taskTrace.evidenceStatus !== "AVAILABLE" && (
                  <div className={`p-3 rounded-lg border text-xs flex items-start gap-2.5 ${taskTrace.evidenceStatus === "UNAVAILABLE"
                    ? "border-rose-900/60 bg-rose-950/20 text-rose-300"
                    : "border-amber-900/60 bg-amber-950/20 text-amber-300"
                    }`}>
                    <AlertTriangle className="h-4 w-4 shrink-0 mt-0.5" />
                    <div>
                      <div className="font-semibold uppercase tracking-wider text-[11px]">
                        Downstream Evidence Status: {taskTrace.evidenceStatus}
                      </div>
                      <p className="mt-0.5 text-zinc-300">
                        {taskTrace.evidenceStatus === "UNAVAILABLE"
                          ? "Downstream services are currently unavailable (circuit open). Real-time verification evidence cannot be collected."
                          : "Downstream evidence is degraded or partially available; displaying all known trace links and historical records."}
                      </p>
                    </div>
                  </div>
                )}

                {loadingTaskTrace && (
                  <div className="p-8 text-center space-y-2.5">
                    <RefreshCw className="h-5 w-5 text-zinc-400 animate-spin mx-auto" />
                    <p className="text-xs text-zinc-400 font-mono">
                      Querying task-level continuous traceability spine...
                    </p>
                  </div>
                )}

                {taskTraceError && (
                  <div className="p-3.5 rounded-lg border border-rose-900/60 bg-rose-950/20 text-rose-300 text-xs flex items-center justify-between">
                    <span>{taskTraceError}</span>
                    <Button
                      variant="outline"
                      size="sm"
                      className="text-xs border-rose-800 hover:bg-rose-900/40 h-7"
                      onClick={() => {
                        if (node.task.id) {
                          setLoadingTaskTrace(true);
                          setTaskTraceError(null);
                          apiClient.getTaskTraceability<TaskTraceabilityResponse>(node.task.id)
                            .then((d) => { setTaskTrace(d); setLoadingTaskTrace(false); })
                            .catch((e) => { setTaskTraceError(e.message); setLoadingTaskTrace(false); });
                        }
                      }}
                    >
                      Retry
                    </Button>
                  </div>
                )}

                {!loadingTaskTrace && !taskTrace && !taskTraceError && (
                  <div className="p-6 text-center text-zinc-500 text-xs border border-dashed border-[#26262C] rounded-lg">
                    No task ID linked to this node, or task traceability record not available.
                  </div>
                )}

                {taskTrace && (
                  <>
                    {/* Task & Parent Requirement Summary */}
                    <div className="rounded-lg border border-[#1C1C21] bg-[#141418] p-4 space-y-2.5">
                      <div className="flex items-center justify-between">
                        <div className="flex items-center gap-2">
                          <Badge variant="outline" className="font-mono text-xs text-zinc-200 border-[#26262C]">
                            {taskTrace.task.taskKey}
                          </Badge>
                          <span className="font-semibold text-sm text-zinc-100">
                            {taskTrace.task.title}
                          </span>
                        </div>
                        <Badge variant="secondary" className="font-mono text-[10px]">
                          {taskTrace.task.status}
                        </Badge>
                      </div>

                      {taskTrace.requirement && (
                        <div className="pt-2 border-t border-[#1C1C21] flex items-center justify-between text-xs text-zinc-400">
                          <span className="flex items-center gap-1.5">
                            <Layers className="h-3.5 w-3.5 text-zinc-400" />
                            Parent Requirement:
                          </span>
                          <span className="font-mono text-zinc-300">
                            {taskTrace.requirement.identifier || taskTrace.requirement.reqKey} - {taskTrace.requirement.title}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Granular Link Provenance with Origin Badges */}
                    <div>
                      <h4 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider mb-2.5 flex items-center gap-2">
                        <Link2 className="h-4 w-4 text-zinc-300" />
                        Granular Traceability Link Provenance ({taskTrace.links.length})
                      </h4>
                      {taskTrace.links.length === 0 ? (
                        <p className="text-xs text-zinc-500 italic">No explicit traceability links recorded.</p>
                      ) : (
                        <div className="space-y-1.5">
                          {taskTrace.links.map((link) => (
                            <div
                              key={link.id}
                              className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418] text-xs space-y-1"
                            >
                              <div className="flex items-center justify-between">
                                <div className="flex items-center gap-1.5 font-mono text-[11px] text-zinc-300">
                                  <span>{link.sourceType}</span>
                                  <ArrowRight className="h-3 w-3 text-zinc-600" />
                                  <span>{link.targetType}</span>
                                </div>
                                <div className="flex items-center gap-1.5">
                                  <Badge
                                    variant="outline"
                                    className={`font-mono text-[10px] ${link.linkOrigin === "REQUIREMENT_TASK_RELATION"
                                      ? "text-zinc-300 border-zinc-700"
                                      : link.linkOrigin === "EXPLICIT_USER_LINK"
                                        ? "text-emerald-400 border-emerald-800"
                                        : "text-amber-400 border-amber-800"
                                      }`}
                                  >
                                    {link.linkOrigin}
                                  </Badge>
                                  <Badge variant="secondary" className="font-mono text-[10px]">
                                    {link.confidence}
                                  </Badge>
                                </div>
                              </div>
                              {link.evidence && (
                                <p className="text-[11px] text-zinc-400 font-sans">
                                  Evidence: {link.evidence}
                                </p>
                              )}
                            </div>
                          ))}
                        </div>
                      )}
                    </div>

                    {/* Code Links */}
                    {taskTrace.codeLinks && taskTrace.codeLinks.length > 0 && (
                      <div>
                        <h4 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider mb-2 flex items-center gap-2">
                          <GitCommit className="h-4 w-4 text-zinc-300" />
                          Associated Code Links ({taskTrace.codeLinks.length})
                        </h4>
                        <div className="space-y-1.5">
                          {taskTrace.codeLinks.map((cl) => (
                            <div
                              key={cl.id}
                              className="p-2.5 rounded-lg border border-[#1C1C21] bg-[#141418] flex items-center justify-between text-xs font-mono"
                            >
                              <span className="text-zinc-200 font-bold">{cl.linkType}</span>
                              <span className="text-zinc-400 truncate max-w-[280px]">{cl.externalRef}</span>
                              <Badge variant="outline" className="text-[9px] text-zinc-400 border-[#26262C]">
                                {cl.origin || "EXPLICIT"}
                              </Badge>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}

                    {/* Related Commits & Pull Requests */}
                    <div className="grid grid-cols-2 gap-3">
                      <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418]">
                        <div className="text-xs font-semibold text-zinc-400 mb-2 flex items-center gap-1.5">
                          <GitCommit className="h-3.5 w-3.5 text-zinc-400" />
                          Commits ({taskTrace.commits.length})
                        </div>
                        {taskTrace.commits.length === 0 ? (
                          <p className="text-[11px] text-zinc-500 italic">No commits matched</p>
                        ) : (
                          <div className="space-y-1">
                            {taskTrace.commits.slice(0, 3).map((c, i) => (
                              <div key={i} className="text-[11px] font-mono text-zinc-300 truncate">
                                {c.commitSha.substring(0, 8)} - {c.message}
                              </div>
                            ))}
                          </div>
                        )}
                      </div>

                      <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#141418]">
                        <div className="text-xs font-semibold text-zinc-400 mb-2 flex items-center gap-1.5">
                          <GitPullRequest className="h-3.5 w-3.5 text-zinc-400" />
                          Pull Requests ({taskTrace.pullRequests.length})
                        </div>
                        {taskTrace.pullRequests.length === 0 ? (
                          <p className="text-[11px] text-zinc-500 italic">No PRs matched</p>
                        ) : (
                          <div className="space-y-1">
                            {taskTrace.pullRequests.slice(0, 3).map((pr, i) => (
                              <div key={i} className="text-[11px] font-mono text-zinc-300 truncate flex items-center justify-between">
                                <span>PR #{pr.prNumber}</span>
                                <Badge variant="outline" className="text-[9px] border-[#26262C]">
                                  {pr.state}
                                </Badge>
                              </div>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>

                    {/* Active Gaps Applicable to Task */}
                    {taskTrace.gaps && taskTrace.gaps.length > 0 && (
                      <div>
                        <h4 className="text-xs font-semibold uppercase text-zinc-400 tracking-wider mb-2 flex items-center gap-2">
                          <ShieldAlert className="h-4 w-4 text-amber-400" />
                          Active Traceability Gaps ({taskTrace.gaps.length})
                        </h4>
                        <div className="space-y-2">
                          {taskTrace.gaps.map((gap, i) => (
                            <div
                              key={i}
                              className="p-3 rounded-lg border border-amber-900/50 bg-amber-950/20 text-xs space-y-1"
                            >
                              <div className="flex items-center justify-between">
                                <span className="font-semibold text-amber-300">{gap.gapType}</span>
                                <Badge
                                  variant={gap.severity === "HIGH" ? "destructive" : "outline"}
                                  className="text-[10px]"
                                >
                                  {gap.severity}
                                </Badge>
                              </div>
                              <p className="text-zinc-300 text-[11px]">{gap.evidence}</p>
                              <p className="text-amber-300/80 text-[11px] italic">
                                Action: {gap.recommendation}
                              </p>
                            </div>
                          ))}
                        </div>
                      </div>
                    )}
                  </>
                )}
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="p-3.5 border-t border-[#1C1C21] bg-[#09090B] flex items-center justify-between">
            <span className="text-[11px] text-zinc-500 font-mono">
              ForgeAI Traceability Spine v1.0
            </span>
            <Button variant="outline" size="sm" onClick={onClose} className="h-7 text-xs border-[#26262C]">
              Close Inspector
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
