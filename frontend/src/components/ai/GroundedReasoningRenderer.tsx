"use client";

import React from "react";
import { CheckCircle2, Cpu, FileCheck, Lightbulb, ShieldCheck } from "lucide-react";

interface GroundedReasoningRendererProps {
  summary: string;
}

interface ParsedSection {
  id: string;
  kind: "FACTS" | "INFERENCES" | "RECOMMENDATIONS" | "GENERAL";
  title: string;
  subtitle?: string;
  lines: string[];
}

function stripJsonAndSection4(raw: string): string {
  let cleaned = raw.replace(/\r\n/g, "\n");

  // 1. Cut everything from "SECTION 4: STRUCTURED REVIEW FINDINGS" onwards
  cleaned = cleaned.replace(
    /(?:^|\n)[ \t]*#{0,4}[ \t]*\*?\*?SECTION\s*4\b[\s\S]*$/i,
    ""
  );

  // 2. Remove any remaining fenced ```json ... ``` or ``` ... ``` blocks containing JSON arrays
  cleaned = cleaned.replace(/```(?:json)?[\s\S]*?(?:```|`|$)/gi, "");

  // 3. Remove any unfenced raw JSON array of review findings at the end
  cleaned = cleaned.replace(
    /\n\[\s*\{\s*"severity"\s*:\s*"(?:CRITICAL|HIGH|MEDIUM|LOW|INFO)"[\s\S]*$/i,
    ""
  );

  return cleaned.trim();
}

function renderInlineFormatting(text: string): React.ReactNode[] {
  const cleanedText = text.replace(/^`+|`+$/g, "");
  const parts = cleanedText.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((part, idx) => {
    if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      return (
        <strong key={idx} className="font-semibold text-zinc-100">
          {part.slice(2, -2)}
        </strong>
      );
    }
    if (part.startsWith("`") && part.endsWith("`") && part.length > 2) {
      return (
        <code
          key={idx}
          className="px-1.5 py-0.5 rounded bg-[#09090B] border border-[#26262C] font-mono text-[11px] text-sky-300"
        >
          {part.slice(1, -1)}
        </code>
      );
    }
    return <React.Fragment key={idx}>{part}</React.Fragment>;
  });
}

function renderFormattedLineContent(rawLine: string): React.ReactNode {
  const clean = rawLine
    .replace(/^[-*•]\s+/, "")
    .replace(/^\*\*([^*]+)\*\*:\s*/, "$1: ")
    .trim();

  const kvMatch = clean.match(/^([A-Za-z0-9 &/_()-]{2,48}):\s+(.+)$/);
  if (kvMatch) {
    return (
      <span>
        <span className="font-semibold text-zinc-100">{kvMatch[1]}: </span>
        <span className="text-zinc-300">{renderInlineFormatting(kvMatch[2])}</span>
      </span>
    );
  }

  return <span className="text-zinc-300">{renderInlineFormatting(clean)}</span>;
}

function parseSections(cleanedSummary: string): ParsedSection[] {
  const lines = cleanedSummary.split("\n");
  const sections: ParsedSection[] = [];
  let current: ParsedSection = {
    id: "sec-0",
    kind: "GENERAL",
    title: "Executive Engineering Synthesis",
    lines: [],
  };

  const sectionHeaderRegex =
    /^(?:#{1,4}\s*)?(?:\*\*)?SECTION\s*(\d+)\s*:\s*([^(\n*]+)(?:\(([^)]+)\))?(?:\*\*)?$/i;

  for (const rawLine of lines) {
    const trimmed = rawLine.trim();
    if (!trimmed || trimmed === "`" || trimmed === "```") continue;

    const match = trimmed.match(sectionHeaderRegex);
    if (match) {
      if (current.lines.length > 0) {
        sections.push(current);
      }
      const secNum = match[1];
      const rawTitle = match[2].trim();
      const rawSub = match[3]?.trim();

      let kind: ParsedSection["kind"] = "GENERAL";
      if (secNum === "1" || /FACT/i.test(rawTitle)) kind = "FACTS";
      else if (secNum === "2" || /INFERENCE/i.test(rawTitle)) kind = "INFERENCES";
      else if (secNum === "3" || /RECOMMENDATION/i.test(rawTitle)) kind = "RECOMMENDATIONS";

      current = {
        id: `sec-${sections.length + 1}`,
        kind,
        title:
          kind === "FACTS"
            ? "Evidence-Grounded Facts"
            : kind === "INFERENCES"
            ? "Architectural, Security & Reliability Reasoning"
            : kind === "RECOMMENDATIONS"
            ? "Prioritized Engineering Recommendations"
            : rawTitle,
        subtitle: rawSub,
        lines: [],
      };
      continue;
    }

    const genericHeader = trimmed.match(/^#{1,4}\s+(.+)$/);
    if (genericHeader) {
      const hText = genericHeader[1].replace(/\*\*/g, "").trim();
      if (/SECTION\s*4|STRUCTURED REVIEW FINDINGS/i.test(hText)) {
        break;
      }
      if (current.lines.length > 0) {
        sections.push(current);
      }
      let kind: ParsedSection["kind"] = "GENERAL";
      if (/FACT/i.test(hText)) kind = "FACTS";
      else if (/INFERENCE/i.test(hText)) kind = "INFERENCES";
      else if (/RECOMMENDATION/i.test(hText)) kind = "RECOMMENDATIONS";

      current = {
        id: `sec-${sections.length + 1}`,
        kind,
        title: hText,
        lines: [],
      };
      continue;
    }

    current.lines.push(trimmed);
  }

  if (current.lines.length > 0) {
    sections.push(current);
  }

  return sections;
}

export const GroundedReasoningRenderer: React.FC<GroundedReasoningRendererProps> = ({
  summary,
}) => {
  if (!summary || !summary.trim()) {
    return (
      <div className="p-4 rounded-md bg-[#141418] border border-[#26262C] text-xs text-zinc-500">
        No reasoning summary available.
      </div>
    );
  }

  const cleaned = stripJsonAndSection4(summary);
  const sections = parseSections(cleaned);

  return (
    <div className="space-y-3 font-sans">
      {sections.map((section) => {
        const badgeStyle =
          section.kind === "FACTS"
            ? "border-emerald-900/60 bg-emerald-950/20 text-emerald-400"
            : section.kind === "INFERENCES"
            ? "border-sky-900/60 bg-sky-950/20 text-sky-400"
            : section.kind === "RECOMMENDATIONS"
            ? "border-amber-900/60 bg-amber-950/20 text-amber-400"
            : "border-[#26262C] bg-[#0E0E11] text-zinc-300";

        const borderAccent =
          section.kind === "FACTS"
            ? "border-l-2 border-l-emerald-500"
            : section.kind === "INFERENCES"
            ? "border-l-2 border-l-sky-500"
            : section.kind === "RECOMMENDATIONS"
            ? "border-l-2 border-l-amber-500"
            : "";

        return (
          <div
            key={section.id}
            className={`p-4 rounded-md bg-[#141418] border border-[#26262C] ${borderAccent} space-y-3`}
          >
            {/* Section Card Header */}
            <div className="flex items-center justify-between gap-2 pb-2 border-b border-[#1C1C21]">
              <div className="flex items-center gap-2">
                {section.kind === "FACTS" && (
                  <ShieldCheck className="h-4 w-4 text-emerald-400 shrink-0" />
                )}
                {section.kind === "INFERENCES" && (
                  <Cpu className="h-4 w-4 text-sky-400 shrink-0" />
                )}
                {section.kind === "RECOMMENDATIONS" && (
                  <Lightbulb className="h-4 w-4 text-amber-400 shrink-0" />
                )}
                {section.kind === "GENERAL" && (
                  <FileCheck className="h-4 w-4 text-zinc-400 shrink-0" />
                )}
                <h4 className="text-xs font-semibold uppercase tracking-wider text-zinc-100">
                  {section.title}
                </h4>
              </div>
              <span
                className={`px-2 py-0.5 rounded text-[10px] font-mono uppercase border ${badgeStyle}`}
              >
                {section.kind === "FACTS"
                  ? "Verified Evidence"
                  : section.kind === "INFERENCES"
                  ? "Architectural Deduction"
                  : section.kind === "RECOMMENDATIONS"
                  ? "Action Strategy"
                  : "Synthesis"}
              </span>
            </div>

            {/* Section Body Items */}
            <div className="space-y-2 text-xs leading-relaxed">
              {section.lines.map((line, idx) => {
                if (/^\d+\.\s*$/.test(line)) {
                  return null;
                }

                const numberedMatch = line.match(/^(\d+)\.\s+(.+)$/);
                if (numberedMatch) {
                  return (
                    <div
                      key={idx}
                      className="pt-2 first:pt-0 flex items-start gap-2 font-semibold text-zinc-100"
                    >
                      <span className="px-1.5 py-0.2 rounded bg-[#09090B] border border-[#26262C] text-[10px] font-mono text-amber-400 shrink-0 mt-0.5">
                        #{numberedMatch[1]}
                      </span>
                      <span>{renderInlineFormatting(numberedMatch[2].replace(/:$/, ""))}</span>
                    </div>
                  );
                }

                if (/^[-*•]\s+/.test(line)) {
                  return (
                    <div
                      key={idx}
                      className="flex items-start gap-2 pl-2 py-0.5"
                    >
                      <CheckCircle2 className="h-3.5 w-3.5 text-zinc-500 shrink-0 mt-0.5" />
                      <div className="flex-1">{renderFormattedLineContent(line)}</div>
                    </div>
                  );
                }

                return (
                  <p key={idx} className="text-zinc-300">
                    {renderFormattedLineContent(line)}
                  </p>
                );
              })}
            </div>
          </div>
        );
      })}
    </div>
  );
};
