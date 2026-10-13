"use client";

import React, { useState } from "react";
import { TraceabilityNode } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  X,
  Layers,
  Network,
  CheckCircle2,
  XCircle,
  FileCode,
  Sparkles,
  GitPullRequest,
  GitCommit,
  ShieldAlert,
  ArrowRight,
  Copy,
  Check,
  Terminal,
  FolderTree,
  ChevronRight,
  ChevronDown,
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
  const [copiedTrace, setCopiedTrace] = useState(false);
  const [expandedNodes, setExpandedNodes] = useState<Record<string, boolean>>({
    "root-ast": true,
    "class-1": true,
    "suite-1": true,
  });

  if (!isOpen || !node) return null;

  const toggleNode = (id: string) => {
    setExpandedNodes((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const handleCopyTrace = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedTrace(true);
    setTimeout(() => setCopiedTrace(false), 2000);
  };

  // Sample stack trace for verification inspection
  const sampleStackTrace =
    node.verification.status === "FAILED"
      ? `org.opentest4j.AssertionFailedError: Status expected:<200> but was:<401>
  at org.springframework.test.web.servlet.result.StatusResultMatchers.lambda$assertStatus$4(StatusResultMatchers.java:101)
  at org.springframework.test.web.servlet.MockMvc.perform(MockMvc.java:214)
  at com.forgeai.identity.api.controller.UserControllerTest.getUserProfile_WhenAuthenticated(UserControllerTest.java:82)
  at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
  at java.base/java.lang.reflect.Method.invoke(Method.java:580)
  at org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:728)`
      : `[INFO] Running com.forgeai.gateway.filter.JwtRevocationGatewayTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.12 s -- in com.forgeai.gateway.filter.JwtRevocationGatewayTest
[INFO] Running com.forgeai.gateway.filter.GatewayRateLimiterTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.33 s -- in com.forgeai.gateway.filter.GatewayRateLimiterTest
[INFO] 
[INFO] Results:
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------`;

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
                    PR #{node.sourceControl.prNumber}
                  </span>
                </div>
                <h2 className="text-lg font-bold text-white tracking-tight mt-1">
                  {node.requirement.title}
                </h2>
                <p className="text-xs text-slate-400">
                  {node.task.title}
                </p>
              </div>

              <button
                onClick={onClose}
                className="rounded-lg p-2 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Quick KPI Strip */}
            <div className="mt-4 flex items-center justify-between pt-3 border-t border-slate-800/80 text-xs">
              <div className="flex items-center gap-2">
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
              <div className="flex items-center gap-2">
                <span className="text-slate-400">CI Verification:</span>
                <Badge variant={node.verification.status === "PASSED" ? "success" : "destructive"}>
                  {node.verification.status} ({node.verification.passedTests}/{node.verification.totalTests})
                </Badge>
              </div>
            </div>

            {/* Tab Selection */}
            <div className="flex items-center gap-2 mt-4">
              <button
                onClick={() => setActiveTab("ast")}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
                  activeTab === "ast"
                    ? "bg-indigo-600 text-white shadow-sm"
                    : "text-slate-400 hover:text-slate-200 hover:bg-slate-800"
                }`}
              >
                <FolderTree className="h-3.5 w-3.5" />
                AST Dependency Tree
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
                Raw JUnit Stack Traces
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
            {/* Tab 1: AST Dependency Tree */}
            {activeTab === "ast" && (
              <div className="space-y-6">
                <div>
                  <h3 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-2 flex items-center gap-2">
                    <FolderTree className="h-4 w-4 text-indigo-400" />
                    Tree-Sitter Semantic AST Hierarchy
                  </h3>
                  <p className="text-xs text-slate-400 mb-4">
                    Hierarchical AST decomposition of code modifications parsed from commit SHA {node.sourceControl.commitSha.substring(0, 7)}.
                  </p>

                  {/* Interactive Tree View */}
                  <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 font-mono text-xs space-y-2">
                    {/* Root Package */}
                    <div>
                      <div
                        onClick={() => toggleNode("root-ast")}
                        className="flex items-center gap-2 text-indigo-300 font-semibold cursor-pointer hover:text-indigo-200 select-none"
                      >
                        {expandedNodes["root-ast"] ? (
                          <ChevronDown className="h-3.5 w-3.5" />
                        ) : (
                          <ChevronRight className="h-3.5 w-3.5" />
                        )}
                        <span>package com.forgeai.gateway.filter</span>
                      </div>

                      {expandedNodes["root-ast"] && (
                        <div className="ml-5 pl-2 border-l border-slate-800 mt-2 space-y-2">
                          {/* Class Node */}
                          <div>
                            <div
                              onClick={() => toggleNode("class-1")}
                              className="flex items-center gap-2 text-sky-300 cursor-pointer hover:text-sky-200 select-none"
                            >
                              {expandedNodes["class-1"] ? (
                                <ChevronDown className="h-3.5 w-3.5" />
                              ) : (
                                <ChevronRight className="h-3.5 w-3.5" />
                              )}
                              <span>class JwtRevocationGatewayFilter</span>
                              <Badge variant="outline" className="text-[10px] py-0 text-slate-400">
                                GlobalFilter
                              </Badge>
                            </div>

                            {expandedNodes["class-1"] && (
                              <div className="ml-5 pl-2 border-l border-slate-800 mt-2 space-y-2">
                                {/* Methods */}
                                <div className="flex items-center justify-between text-slate-300 bg-slate-950/60 p-2 rounded-lg border border-slate-800/80">
                                  <div>
                                    <span className="text-purple-300">Mono&lt;Void&gt;</span>{" "}
                                    <span className="text-white font-semibold">filter</span>
                                    <span className="text-slate-400">(ServerWebExchange, GatewayFilterChain)</span>
                                  </div>
                                  <Badge variant="warning" className="text-[10px]">
                                    MUTATED
                                  </Badge>
                                </div>

                                <div className="flex items-center justify-between text-slate-300 bg-slate-950/60 p-2 rounded-lg border border-slate-800/80">
                                  <div>
                                    <span className="text-purple-300">Mono&lt;Boolean&gt;</span>{" "}
                                    <span className="text-white font-semibold">isTokenRevoked</span>
                                    <span className="text-slate-400">(String jti)</span>
                                  </div>
                                  <Badge variant="success" className="text-[10px]">
                                    NEW
                                  </Badge>
                                </div>

                                {/* Call Edge to Redis */}
                                <div className="ml-4 pl-2 border-l border-indigo-500/30 flex items-center gap-2 text-[11px] text-amber-300">
                                  <ArrowRight className="h-3 w-3 text-slate-500" />
                                  <span>calls ReactiveRedisTemplate.hasKey("blacklist:" + jti)</span>
                                </div>
                              </div>
                            )}
                          </div>
                        </div>
                      )}
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

            {/* Tab 2: Raw JUnit Stack Traces */}
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

                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => handleCopyTrace(sampleStackTrace)}
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
                </div>

                {/* Raw Terminal Stack Trace Box */}
                <div className="rounded-xl border border-slate-800 bg-black/90 p-4 font-mono text-xs overflow-x-auto shadow-inner">
                  <div className="flex items-center justify-between pb-2 mb-2 border-b border-slate-800 text-[11px] text-slate-500">
                    <span>CONSOLE OUTPUT / STACK TRACE</span>
                    <span>JVM Test Engine (JUnit 5)</span>
                  </div>
                  <pre
                    className={`leading-5 whitespace-pre ${
                      node.verification.status === "FAILED" ? "text-rose-300" : "text-emerald-300"
                    }`}
                  >
                    {sampleStackTrace}
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
                        PR #{node.sourceControl.prNumber}
                      </span>
                      <Badge variant="outline" className="font-mono text-[10px]">
                        MERGED
                      </Badge>
                    </div>
                    <p className="text-sm font-medium text-white">
                      {node.sourceControl.prTitle}
                    </p>
                    <div className="flex items-center justify-between text-xs text-slate-400 pt-2 border-t border-slate-800/80">
                      <span>Author: @{node.sourceControl.author}</span>
                      <span className="font-mono flex items-center gap-1 text-slate-300">
                        <GitCommit className="h-3.5 w-3.5 text-slate-500" />
                        {node.sourceControl.commitSha.substring(0, 10)}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="rounded-xl border border-slate-800 bg-slate-950 p-4 space-y-2">
                  <div className="text-xs font-semibold text-slate-300">Trace Correlation Signature</div>
                  <div className="font-mono text-[11px] text-indigo-300 break-all bg-slate-900 p-2.5 rounded-lg border border-slate-800">
                    00-{node.sourceControl.commitSha.substring(0, 32)}-{Date.now().toString(16).padStart(16, "0")}-01
                  </div>
                  <p className="text-[11px] text-slate-500">
                    W3C traceparent header injected across all distributed Spring Boot microservices.
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
