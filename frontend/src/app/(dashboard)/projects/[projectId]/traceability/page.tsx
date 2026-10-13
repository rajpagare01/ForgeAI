"use client";

import React, { Suspense } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import {
  TraceabilitySpineFlow,
} from "@/components/traceability/TraceabilitySpineFlow";
import {
  Layers,
  ArrowLeft,
  ShieldCheck,
  Download,
} from "lucide-react";
import { Button } from "@/components/ui/button";

function TraceabilityMatrixContent() {
  const params = useParams();
  const projectId = (params?.projectId as string) || "proj-forgeai-core";

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
              className="hover:text-indigo-400 transition-colors"
            >
              FORGE-CORE
            </Link>
            <span>/</span>
            <span className="text-white font-semibold">Traceability Matrix</span>
          </div>
          <div className="flex items-center gap-2 pt-1">
            <div className="p-2 rounded-xl bg-indigo-600/20 text-indigo-400 border border-indigo-500/30">
              <Layers className="h-5 w-5" />
            </div>
            <h1 className="text-2xl font-bold tracking-tight text-white">
              End-to-End Governance Traceability Spine
            </h1>
          </div>
          <p className="text-xs text-slate-400 max-w-2xl">
            Live unbroken delivery spine: Requirement specification → Task execution → Git commit & PR → CI test run → Tree-sitter AI blast radius score.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" className="gap-1.5 text-xs">
            <Download className="h-3.5 w-3.5" />
            Export Audit Matrix
          </Button>
          <Button variant="glow" size="sm" className="gap-1.5 text-xs">
            <ShieldCheck className="h-3.5 w-3.5" />
            Verify Invariants
          </Button>
        </div>
      </div>

      {/* Traceability KPI Strip */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Spine Coverage</div>
          <div className="text-xl font-bold text-emerald-400 font-mono">100.0%</div>
          <div className="text-[10px] text-slate-500">Every commit has linked REQ</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">CI Verification Ratio</div>
          <div className="text-xl font-bold text-white font-mono">99.2%</div>
          <div className="text-[10px] text-emerald-400">All staging test suites passed</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">AI Blast Radii Evaluated</div>
          <div className="text-xl font-bold text-indigo-400 font-mono">51 PRs</div>
          <div className="text-[10px] text-slate-500">AST dependency graph parsed</div>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-1">
          <div className="text-xs text-slate-400">Active High-Risk Interceptions</div>
          <div className="text-xl font-bold text-amber-400 font-mono">1 PR Blocked</div>
          <div className="text-[10px] text-amber-400">Deadlock safeguard required</div>
        </div>
      </div>

      {/* Main Interactive Flow Component */}
      <TraceabilitySpineFlow />
    </div>
  );
}

export default function TraceabilityMatrixPage() {
  return (
    <Suspense fallback={<div className="p-8 text-center text-slate-500 text-xs">Loading traceability matrix...</div>}>
      <TraceabilityMatrixContent />
    </Suspense>
  );
}
