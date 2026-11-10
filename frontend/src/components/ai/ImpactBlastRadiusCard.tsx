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
  const getRiskBadge = (level: string) => {
    switch (level) {
      case "HIGH":
        return <Badge variant="riskHigh">{level} RISK ({analysis.blastRadiusScore}/100)</Badge>;
      case "MEDIUM":
        return <Badge variant="riskMedium">{level} RISK ({analysis.blastRadiusScore}/100)</Badge>;
      default:
        return <Badge variant="riskLow">{level} RISK ({analysis.blastRadiusScore}/100)</Badge>;
    }
  };

  return (
    <div className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 shadow-none space-y-4">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-zinc-300" />
            <h3 className="text-sm font-semibold text-zinc-100">
              PR #{analysis.pullRequestId} Impact & Blast Radius Audit
            </h3>
          </div>
          <p className="text-[11px] text-zinc-400 mt-0.5">
            Evaluated via <span className="font-mono text-zinc-300">{analysis.modelName}</span> (Prompt: {analysis.promptVersion})
          </p>
        </div>

        <div className="flex items-center gap-2">
          {getRiskBadge(analysis.riskLevel)}
        </div>
      </div>

      {/* Summary */}
      <div className="rounded-md border border-[#1C1C21] bg-[#0B0B0E] p-3.5">
        <h4 className="text-[10px] font-mono font-medium uppercase text-zinc-500 tracking-wider mb-1.5">
          Executive Synthesis
        </h4>
        <p className="text-xs text-zinc-300 leading-relaxed">
          {analysis.summary}
        </p>
      </div>

      {/* Traversed Knowledge Graph Edges */}
      <div>
        <h4 className="text-[10px] font-mono font-medium uppercase text-zinc-500 tracking-wider mb-2 flex items-center gap-1.5">
          <Network className="h-3.5 w-3.5 text-zinc-400" />
          Dependency Call Graph Edges
        </h4>
        <div className="flex flex-wrap gap-1.5">
          {analysis.graphEdges.map((edge, idx) => (
            <div
              key={idx}
              className="flex items-center gap-1.5 rounded-md border border-[#26262C] bg-[#141418] px-2.5 py-1 text-[11px] font-mono"
            >
              <span className="text-zinc-200 font-medium">{edge.from}</span>
              <span className="text-zinc-500 text-[10px] font-sans">[{edge.relation}]</span>
              <ArrowRight className="h-3 w-3 text-zinc-600" />
              <span className="text-zinc-300 font-medium">{edge.to}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Affected AST Symbols Grid */}
      <div>
        <h4 className="text-[10px] font-mono font-medium uppercase text-zinc-500 tracking-wider mb-2 flex items-center gap-1.5">
          <Layers className="h-3.5 w-3.5 text-zinc-400" />
          Impacted AST Code Symbols ({analysis.affectedSymbols.length})
        </h4>
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
          {analysis.affectedSymbols.map((sym, idx) => (
            <div
              key={idx}
              className="flex items-center justify-between rounded-md border border-[#1C1C21] bg-[#141418] p-2.5"
            >
              <div className="space-y-0.5 truncate pr-2">
                <div className="text-xs font-mono font-medium text-zinc-200 truncate">
                  {sym.name}
                </div>
                <div className="text-[10px] text-zinc-500 font-mono truncate">{sym.filePath}</div>
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
        <h4 className="text-[10px] font-mono font-medium uppercase text-zinc-500 tracking-wider mb-2 flex items-center gap-1.5">
          <CheckCircle className="h-3.5 w-3.5 text-emerald-400" />
          Automated Remediation Guidelines
        </h4>
        <ul className="space-y-1.5">
          {analysis.recommendations.map((rec, idx) => (
            <li
              key={idx}
              className="flex items-start gap-2 text-xs text-zinc-300 bg-[#141418] p-2 rounded-md border border-[#1C1C21]"
            >
              <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded bg-zinc-800 text-[10px] font-mono font-bold text-zinc-300">
                {idx + 1}
              </span>
              <span className="text-[12px] leading-relaxed">{rec}</span>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
};
