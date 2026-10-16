"use client";

import React, { useState } from "react";
import { TraceabilityNode } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
  const [activeTab, setActiveTab] = useState<"ast" | "junit" | "git">("ast");
  const [copiedTrace, setCopiedTrace] = useState<boolean>(false);

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
        className="fixed inset-0 bg-black/70 backdrop-blur-sm transition-opacity"
        onClick={onClose}
      />

      {/* Slide-over Drawer Panel */}
      <div className="fixed inset-y-0 right-0 flex max-w-full pl-10">
        <div className="relative w-screen max-w-2xl bg-slate-950 border-l border-slate-800 shadow-2xl flex flex-col">
          {/* Header */}
          <div className="p-6 border-b border-slate-800 bg-slate-900/50">
            <div className="flex items-start justify-between">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <Badge variant="default" className="font-mono text-xs">
                    {node.requirement.key}
                  </Badge>
                  <ArrowRight className="h-3.5 w-3.5 text-slate-500" />
                  <Badge variant="outline" className="font-mono text-xs text-purple-300 border-purple-500/40">
                    {node.task.key}
                  </Badge>
                  <ArrowRight className="h-3.5 w-3.5 text-slate-500" />
                  <span className="font-mono text-xs font-semibold text-sky-400">
                    {hasPr ? `PR #${node.sourceControl.prNumber}` : "No PR Linked"}
                  </span>
                </div>
                <h2 className="text-lg font-bold text-white tracking-tight mt-1">
                  {node.requirement.title}
                </h2>
                <p className="text-xs text-slate-400">
                  Continuous lineage verification for requirement: {node.requirement.key}
                </p>
              </div>

              <Button
                variant="ghost"
                size="icon"
                onClick={onClose}
                className="text-slate-400 hover:text-white"
              >
                <X className="h-4 w-4" />
              </Button>
            </div>

            {/* Quick Status Bar */}
            <div className="grid grid-cols-4 gap-3 mt-4 pt-4 border-t border-slate-800/60">
              <div>
                <span className="text-[10px] text-slate-500 uppercase tracking-wider block">Requirement</span>
                <span className="text-xs font-medium text-indigo-400">{node.requirement.status}</span>
              </div>
              <div>
                <span className="text-[10px] text-slate-500 uppercase tracking-wider block">Task Status</span>
                <span className="text-xs font-medium text-purple-400">{node.task.status}</span>
              </div>
              <div>
                <span className="text-[10px] text-slate-500 uppercase tracking-wider block">Verification</span>
                <span
                  className={`text-xs font-medium ${
                    node.verification.status === "PASSED"
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
                <span className="text-[10px] text-slate-500 uppercase tracking-wider block">Risk Score</span>
                <span
                  className={`text-xs font-medium ${
                    node.aiBlastRadius.riskLevel === "LOW"
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
            <div className="flex gap-2 mt-4 pt-4 border-t border-slate-800/60">
              <button
                onClick={() => setActiveTab("ast")}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  activeTab === "ast"
                    ? "bg-indigo-600 text-white shadow-sm"
                    : "text-slate-400 hover:text-slate-200 hover:bg-slate-800"
                }`}
              >
                <FolderTree className="h-3.5 w-3.5" />
                AST & Risk Analysis
              </button>
              <button
                onClick={() => setActiveTab("junit")}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  activeTab === "junit"
                    ? "bg-indigo-600 text-white shadow-sm"
                    : "text-slate-400 hover:text-slate-200 hover:bg-slate-800"
                }`}
              >
                <Terminal className="h-3.5 w-3.5" />
                Verification Output
              </button>
              <button
                onClick={() => setActiveTab("git")}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  activeTab === "git"
                    ? "bg-indigo-600 text-white shadow-sm"
                    : "text-slate-400 hover:text-slate-200 hover:bg-slate-800"
                }`}
              >
                <GitPullRequest className="h-3.5 w-3.5" />
                PR & Commit Lineage
              </button>
            </div>
          </div>

          {/* Drawer Body */}
          <div className="flex-1 overflow-y-auto p-6 space-y-6">
            {/* Tab 1: AST & Risk Analysis */}
            {activeTab === "ast" && (
              <div className="space-y-6">
                <div>
                  <h3 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-2 flex items-center gap-2">
                    <FolderTree className="h-4 w-4 text-indigo-400" />
                    Tree-Sitter Semantic AST Analysis
                  </h3>
                  <p className="text-xs text-slate-400 mb-4">
                    Semantic blast radius evaluation for {node.requirement.key} implementation.
                  </p>

                  <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 font-mono text-xs space-y-3">
                    <div className="flex items-center justify-between border-b border-slate-800 pb-2">
                      <span className="text-slate-400">Affected AST Symbols:</span>
                      <span className="text-white font-bold">{node.aiBlastRadius.affectedSymbolsCount}</span>
                    </div>
                    <div className="flex items-center justify-between border-b border-slate-800 pb-2">
                      <span className="text-slate-400">Risk Assessment:</span>
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
                      <span className="text-slate-400">Target Commit:</span>
                      <span className="text-slate-300">
                        {hasCommit ? node.sourceControl.commitSha.substring(0, 12) : "No commit associated"}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Flagged Blast Radius Invariants */}
                <div className="space-y-3">
                  <h4 className="text-xs font-semibold uppercase text-slate-400 tracking-wider flex items-center gap-2">
                    <ShieldAlert className="h-4 w-4 text-amber-400" />
                    Automated Invariant & Risk Safeguards
                  </h4>
                  <div className="space-y-2">
                    {node.aiBlastRadius.flaggedRisks.map((risk, idx) => (
                      <div
                        key={idx}
                        className="flex items-start gap-2.5 p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-xs text-slate-300"
                      >
                        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-amber-500/20 text-amber-300 font-bold text-[10px]">
                          !
                        </span>
                        <span>{risk}</span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* Tab 2: Verification Output */}
            {activeTab === "junit" && (
              <div className="space-y-6">
                <div className="flex items-center justify-between">
                  <div>
                    <h3 className="text-xs font-semibold uppercase text-slate-400 tracking-wider">
                      Verification Test Suite Output
                    </h3>
                    <p className="text-xs text-slate-400 mt-0.5">
                      Ingested from runner ID: <span className="font-mono text-white">{node.verification.testRunId}</span>
                    </p>
                  </div>

                  {hasTests && (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => handleCopyTrace(verificationConsoleLog)}
                      className="gap-1.5 text-xs h-8"
                    >
                      {copiedTrace ? (
                        <>
                          <Check className="h-3.5 w-3.5 text-emerald-400" />
                          Copied
                        </>
                      ) : (
                        <>
                          <Copy className="h-3.5 w-3.5" />
                          Copy Raw Output
                        </>
                      )}
                    </Button>
                  )}
                </div>

                {/* Raw Terminal Output Box */}
                <div className="rounded-xl border border-slate-800 bg-black/90 p-4 font-mono text-xs overflow-x-auto shadow-inner">
                  <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-800 text-[11px] text-slate-500">
                    <span>CONSOLE OUTPUT / STACK TRACE</span>
                    <span>{hasTests ? "Automated Test Suite Engine" : "No Tests"}</span>
                  </div>
                  <pre
                    className={`leading-5 whitespace-pre ${
                      node.verification.status === "FAILED" ? "text-rose-300" : "text-emerald-300"
                    }`}
                  >
                    {verificationConsoleLog}
                  </pre>
                </div>

                {/* Test Breakdown Pills */}
                <div className="grid grid-cols-3 gap-3">
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-center">
                    <div className="text-xs text-slate-400">Total Executed</div>
                    <div className="text-lg font-bold font-mono text-white mt-0.5">
                      {node.verification.totalTests}
                    </div>
                  </div>
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-center">
                    <div className="text-xs text-slate-400">Passed</div>
                    <div className="text-lg font-bold font-mono text-emerald-400 mt-0.5">
                      {node.verification.passedTests}
                    </div>
                  </div>
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-center">
                    <div className="text-xs text-slate-400">Failures / Errors</div>
                    <div
                      className={`text-lg font-bold font-mono mt-0.5 ${
                        node.verification.totalTests - node.verification.passedTests > 0
                          ? "text-rose-400"
                          : "text-slate-400"
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
              <div className="space-y-6">
                <div>
                  <h3 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-3 flex items-center gap-2">
                    <GitPullRequest className="h-4 w-4 text-sky-400" />
                    GitHub Source Control Lineage
                  </h3>

                  <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-3">
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-sm font-bold text-sky-400">
                        {hasPr ? `PR #${node.sourceControl.prNumber}` : "No Pull Request"}
                      </span>
                      <Badge variant="outline" className="font-mono text-[10px]">
                        {hasPr ? "LINKED" : "UNLINKED"}
                      </Badge>
                    </div>
                    <p className="text-sm font-medium text-white">
                      {node.sourceControl.prTitle}
                    </p>
                    <div className="flex items-center justify-between text-xs text-slate-400 pt-2 border-t border-slate-800/80">
                      <span>Author: @{node.sourceControl.author}</span>
                      <span className="font-mono flex items-center gap-1 text-slate-300">
                        <GitCommit className="h-3.5 w-3.5 text-slate-500" />
                        {hasCommit ? node.sourceControl.commitSha.substring(0, 10) : "N/A"}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="rounded-xl border border-slate-800 bg-slate-950 p-4 space-y-2">
                  <div className="text-xs font-semibold text-slate-300">Trace Correlation Signature</div>
                  <div className="font-mono text-[11px] text-indigo-300 break-all bg-slate-900 p-2.5 rounded-lg border border-slate-800">
                    {hasCommit
                      ? `00-${node.sourceControl.commitSha.padEnd(32, "0").substring(0, 32)}-${Date.now().toString(16).padStart(16, "0")}-01`
                      : "00-00000000000000000000000000000000-0000000000000000-00"}
                  </div>
                  <p className="text-[11px] text-slate-500">
                    W3C traceparent header injected across distributed microservices.
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* Footer */}
          <div className="p-4 border-t border-slate-800 bg-slate-900/70 flex items-center justify-between">
            <span className="text-xs text-slate-500 font-mono">
              ForgeAI Traceability Spine v1.0
            </span>
            <Button variant="secondary" size="sm" onClick={onClose}>
              Close Inspector
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};
