"use client";

import React from "react";

interface GroundedReasoningRendererProps {
  summary: string;
}

function renderInlineFormatting(text: string): React.ReactNode[] {
  // Handle **bold** and `code` spans inside a single line fragment
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
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
          className="px-1 py-0.5 rounded bg-[#09090B] border border-[#26262C] font-mono text-[11px] text-zinc-200"
        >
          {part.slice(1, -1)}
        </code>
      );
    }
    return <React.Fragment key={idx}>{part}</React.Fragment>;
  });
}

function renderCellOrLineWithBreaks(raw: string): React.ReactNode {
  const segments = raw.split(/<br\s*\/?>/gi);
  return segments.map((seg, idx) => (
    <React.Fragment key={idx}>
      {idx > 0 && <br />}
      {renderInlineFormatting(seg.trim())}
    </React.Fragment>
  ));
}

function parsePipeRow(line: string): string[] {
  const trimmed = line.trim();
  const stripped = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  return stripped.split("|").map((cell) => cell.trim());
}

function isMarkdownSeparatorRow(line: string): boolean {
  const trimmed = line.trim();
  if (!trimmed.includes("|") && !trimmed.includes("-")) return false;
  const cells = parsePipeRow(trimmed);
  return cells.length > 0 && cells.every((c) => /^:?-{2,}:?$/.test(c));
}

export const GroundedReasoningRenderer: React.FC<GroundedReasoningRendererProps> = ({ summary }) => {
  if (!summary || !summary.trim()) {
    return (
      <div className="p-4 rounded-md bg-[#141418] border border-[#26262C] text-xs text-zinc-500">
        No reasoning summary available.
      </div>
    );
  }

  const normalized = summary.replace(/\r\n/g, "\n");
  const lines = normalized.split("\n");
  const blocks: React.ReactNode[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();

    if (!trimmed) {
      i += 1;
      continue;
    }

    // Markdown heading (### SECTION, ## SECTION, # SECTION)
    const headingMatch = trimmed.match(/^(#{1,4})\s+(.+)$/);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const title = headingMatch[2].trim();
      blocks.push(
        <div
          key={`h-${i}`}
          className={`pt-2 first:pt-0 pb-1 border-b border-[#26262C] font-semibold tracking-wider uppercase ${
            level <= 2 ? "text-xs text-zinc-100" : "text-[11px] text-zinc-200"
          }`}
        >
          {renderInlineFormatting(title)}
        </div>
      );
      i += 1;
      continue;
    }

    // Markdown pipe table
    if (trimmed.includes("|") && i + 1 < lines.length && isMarkdownSeparatorRow(lines[i + 1])) {
      const headers = parsePipeRow(trimmed);
      i += 2; // Skip header + separator
      const rows: string[][] = [];
      while (i < lines.length && lines[i].trim().includes("|") && !lines[i].trim().startsWith("#")) {
        rows.push(parsePipeRow(lines[i]));
        i += 1;
      }

      blocks.push(
        <div key={`tbl-${i}`} className="overflow-x-auto rounded-md border border-[#26262C] bg-[#0B0B0E]">
          <table className="w-full text-left text-xs border-collapse">
            <thead className="bg-[#09090B] text-[10px] uppercase tracking-wider text-zinc-400 font-mono border-b border-[#26262C]">
              <tr>
                {headers.map((h, hIdx) => (
                  <th key={hIdx} className="py-2 px-3 font-semibold whitespace-nowrap">
                    {renderCellOrLineWithBreaks(h)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[#1C1C21]">
              {rows.map((row, rIdx) => (
                <tr key={rIdx} className="hover:bg-[#141418]/70 align-top">
                  {headers.map((_, cIdx) => (
                    <td key={cIdx} className="py-2 px-3 text-zinc-300 leading-relaxed">
                      {renderCellOrLineWithBreaks(row[cIdx] ?? "")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
      continue;
    }

    // Bullet list
    if (/^[-*•]\s+/.test(trimmed)) {
      const items: string[] = [];
      while (i < lines.length && /^[-*•]\s+/.test(lines[i].trim())) {
        items.push(lines[i].trim().replace(/^[-*•]\s+/, ""));
        i += 1;
      }
      blocks.push(
        <ul key={`ul-${i}`} className="space-y-1 pl-4 list-disc text-zinc-300">
          {items.map((item, idx) => (
            <li key={idx} className="leading-relaxed">
              {renderCellOrLineWithBreaks(item)}
            </li>
          ))}
        </ul>
      );
      continue;
    }

    // Regular paragraph lines
    const paraLines: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() !== "" &&
      !lines[i].trim().match(/^#{1,4}\s+/) &&
      !/^[-*•]\s+/.test(lines[i].trim()) &&
      !(lines[i].trim().includes("|") && i + 1 < lines.length && isMarkdownSeparatorRow(lines[i + 1]))
    ) {
      paraLines.push(lines[i]);
      i += 1;
    }

    blocks.push(
      <p key={`p-${i}`} className="text-zinc-200 leading-relaxed">
        {paraLines.map((pl, pIdx) => (
          <React.Fragment key={pIdx}>
            {pIdx > 0 && <br />}
            {renderCellOrLineWithBreaks(pl)}
          </React.Fragment>
        ))}
      </p>
    );
  }

  return (
    <div className="p-4 rounded-md bg-[#141418] border border-[#26262C] text-xs text-zinc-200 space-y-3 font-sans">
      {blocks}
    </div>
  );
};

