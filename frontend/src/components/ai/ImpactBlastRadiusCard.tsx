"use client";

import React from "react";
import { AnalysisAudit } from "@/types";
import { Badge } from "@/components/ui/badge";
import {
  Sparkles,
  AlertTriangle,
  CheckCircle,
  Network,
  Cpu,
  Layers,
  ArrowRight,
} from "lucide-react";

interface ImpactBlastRadiusCardProps {
  analysis: AnalysisAudit;
}

export const ImpactBlastRadiusCard: React.FC<ImpactBlastRadiusCardProps> = ({
  analysis,
}) => {
  const getRiskColor = (level: string) => {
    switch (level) {
      case "HIGH":
        return "text-rose-400 border-rose-500/40 bg-rose-950/40";
      case "MEDIUM":
        return "text-amber-400 border-amber-500/40 bg-amber-950/40";
      default:
        return "text-emerald-400 border-emerald-500/40 bg-emerald-950/40";
    }
  };

  return (
    <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-6 backdrop-blur-xl shadow-xl space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-4">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="h-5 w-5 text-indigo-400" />
            <h3 className="text-base font-semibold text-white">
              PR #{analysis.pullRequestId} Impact & Blast Radius Audit
            </h3>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Evaluated via <span className="font-mono text-indigo-300">{analysis.modelName}</span> (Prompt: {analysis.promptVersion})
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className={`px-3 py-1 rounded-full border text-xs font-bold ${getRiskColor(analysis.riskLevel)}`}>
            {analysis.riskLevel} RISK ({analysis.blastRadiusScore}/100)
          </div>
        </div>
      </div>

      {/* Summary */}
      <div className="rounded-xl border border-slate-800/80 bg-slate-950/50 p-4">
        <h4 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-2">
          Executive Synthesis
        </h4>
        <p className="text-sm text-slate-300 leading-relaxed">
          {analysis.summary}
        </p>
      </div>

      {/* Traversed Knowledge Graph Edges */}
      <div>
        <h4 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-3 flex items-center gap-2">
          <Network className="h-4 w-4 text-purple-400" />
          Traversed Dependency Call Graph Edges
        </h4>
        <div className="flex flex-wrap gap-2">
          {analysis.graphEdges.map((edge, idx) => (
            <div
              key={idx}
              className="flex items-center gap-2 rounded-lg border border-slate-800 bg-slate-950/80 px-3 py-2 text-xs font-mono"
            >
              <span className="text-indigo-300 font-medium">{edge.from}</span>
              <span className="text-slate-500 text-[10px] uppercase font-sans">[{edge.relation}]</span>
              <ArrowRight className="h-3 w-3 text-slate-500" />
              <span className="text-sky-300 font-medium">{edge.to}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Affected AST Symbols Grid */}
      <div>
        <h4 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-3 flex items-center gap-2">
          <Layers className="h-4 w-4 text-sky-400" />
          Impacted AST Code Symbols ({analysis.affectedSymbols.length})
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          {analysis.affectedSymbols.map((sym, idx) => (
            <div
              key={idx}
              className="flex items-center justify-between rounded-lg border border-slate-800/80 bg-slate-950/40 p-3"
            >
              <div className="space-y-0.5 truncate pr-2">
                <div className="text-xs font-mono font-medium text-slate-200 truncate">
                  {sym.name}
                </div>
                <div className="text-[11px] text-slate-500 truncate">{sym.filePath}</div>
              </div>
              <Badge variant="outline" className="text-[10px] shrink-0 font-mono">
                {sym.kind}
              </Badge>
            </div>
          ))}
        </div>
      </div>

      {/* Actionable Recommendations */}
      <div>
        <h4 className="text-xs font-semibold uppercase text-slate-400 tracking-wider mb-3 flex items-center gap-2">
          <CheckCircle className="h-4 w-4 text-emerald-400" />
          Automated Remediation Guidelines
        </h4>
        <ul className="space-y-2">
          {analysis.recommendations.map((rec, idx) => (
            <li
              key={idx}
              className="flex items-start gap-2.5 text-xs text-slate-300 bg-slate-950/40 p-2.5 rounded-lg border border-slate-800/80"
            >
              <span className="flex h-4 w-4 shrink-0 items-center justify-center rounded-full bg-indigo-500/20 text-[10px] font-bold text-indigo-300">
                {idx + 1}
              </span>
              <span>{rec}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
};
