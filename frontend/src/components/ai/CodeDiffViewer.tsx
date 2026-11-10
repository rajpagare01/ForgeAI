"use client";

import React from "react";
import { FileCode, AlertCircle, Check } from "lucide-react";
import { Badge } from "@/components/ui/badge";

interface DiffLine {
  type: "add" | "del" | "context";
  oldLineNumber?: number;
  newLineNumber?: number;
  content: string;
  flagged?: boolean;
}

interface CodeDiffViewerProps {
  filename: string;
  lines: DiffLine[];
  riskNote?: string;
}

export const CodeDiffViewer: React.FC<CodeDiffViewerProps> = ({
  filename,
  lines,
  riskNote,
}) => {
  return (
    <div className="rounded-md border border-[#1C1C21] bg-[#09090B] font-mono text-[11px] overflow-hidden">
      {/* File Header */}
      <div className="flex items-center justify-between px-3 py-1.5 bg-[#0B0B0E] border-b border-[#1C1C21]">
        <div className="flex items-center gap-2">
          <FileCode className="h-3.5 w-3.5 text-zinc-400" />
          <span className="font-medium text-zinc-200">{filename}</span>
        </div>
        {riskNote && (
          <Badge variant="warning" className="text-[10px]">
            {riskNote}
          </Badge>
        )}
      </div>

      {/* Diff Content */}
      <div className="overflow-x-auto divide-y divide-[#18181C] font-mono">
        {lines.map((line, idx) => {
          const isAdd = line.type === "add";
          const isDel = line.type === "del";

          return (
            <div
              key={idx}
              className={`flex items-start px-2 py-0.5 leading-5 ${
                line.flagged
                  ? "bg-rose-950/40 text-rose-200 font-semibold"
                  : isAdd
                  ? "bg-emerald-950/20 text-emerald-300"
                  : isDel
                  ? "bg-rose-950/20 text-rose-300 line-through opacity-80"
                  : "text-zinc-400 hover:bg-[#121216]"
              }`}
            >
              <div className="w-8 shrink-0 text-right pr-2 text-[10px] text-zinc-600 select-none">
                {line.oldLineNumber || ""}
              </div>
              <div className="w-8 shrink-0 text-right pr-3 text-[10px] text-zinc-600 select-none">
                {line.newLineNumber || ""}
              </div>
              <div className="w-4 shrink-0 text-center select-none font-bold text-zinc-500">
                {isAdd ? "+" : isDel ? "-" : " "}
              </div>
              <div className="flex-1 whitespace-pre pl-1 text-zinc-200">{line.content}</div>
              {line.flagged && (
                <div className="shrink-0 flex items-center gap-1 text-[10px] text-rose-400 bg-rose-950/60 px-1.5 py-0.5 rounded border border-rose-800/80">
                  <AlertCircle className="h-3 w-3" />
                  AST Risk
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
};
