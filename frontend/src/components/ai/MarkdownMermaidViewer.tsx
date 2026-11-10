"use client";

import React, { useState, useEffect, useRef } from "react";
import {
  Copy,
  Check,
  Download,
  Code,
  Eye,
  FileText,
  ExternalLink,
  Maximize2,
  RefreshCw,
  GitBranch,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface MarkdownMermaidViewerProps {
  content: string;
  title?: string;
  citations?: Array<{ file_path: string; commit_sha?: string }>;
  onCitationClick?: (citation: { file_path: string; commit_sha?: string }) => void;
}

export const MarkdownMermaidViewer: React.FC<MarkdownMermaidViewerProps> = ({
  content,
  title = "Document",
  citations = [],
  onCitationClick,
}) => {
  const [activeTab, setActiveTab] = useState<"rendered" | "raw">("rendered");
  const [copied, setCopied] = useState(false);
  const [renderedMermaid, setRenderedMermaid] = useState<Record<string, string>>({});
  const [mermaidLoading, setMermaidLoading] = useState(false);
  const [copiedBlockId, setCopiedBlockId] = useState<string | null>(null);

  // Copy full markdown
  const handleCopyMarkdown = async () => {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (e) {
      console.warn("Failed to copy markdown:", e);
    }
  };

  // Download .md file
  const handleDownload = () => {
    const blob = new Blob([content], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    const safeTitle = title.toLowerCase().replace(/[^a-z0-9_-]/g, "-") || "document";
    link.href = url;
    link.download = `${safeTitle}.md`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  // Copy specific code snippet
  const handleCopySnippet = async (text: string, id: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedBlockId(id);
      setTimeout(() => setCopiedBlockId(null), 2000);
    } catch {}
  };

  // Dynamically load Mermaid and render diagrams
  useEffect(() => {
    if (activeTab !== "rendered") return;
    const mermaidRegex = /```mermaid\s*([\s\S]*?)```/g;
    const matches: Array<{ id: string; code: string }> = [];
    let match;
    let idx = 0;
    while ((match = mermaidRegex.exec(content)) !== null) {
      matches.push({ id: `mermaid-diagram-${idx++}`, code: match[1].trim() });
    }

    if (matches.length === 0) return;

    let isMounted = true;
    setMermaidLoading(true);

    const renderDiagrams = async () => {
      try {
        // Load Mermaid via dynamic runtime ESM import without static TS module resolution error
        const dynamicImport = new Function("specifier", "return import(specifier)");
        const mermaidModule = (await dynamicImport(
          "https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.esm.min.mjs"
        )) as { default: any };
        const mermaid = mermaidModule.default;
        mermaid.initialize({
          startOnLoad: false,
          theme: "dark",
          themeVariables: {
            darkMode: true,
            background: "#09090b",
            primaryColor: "#27272a",
            primaryTextColor: "#f4f4f6",
            primaryBorderColor: "#3f3f46",
            lineColor: "#52525b",
            secondaryColor: "#141418",
            tertiaryColor: "#0e0e11",
          },
          securityLevel: "loose",
        });

        const svgs: Record<string, string> = {};
        for (const m of matches) {
          try {
            const { svg } = await mermaid.render(`svg-${m.id}-${Date.now()}`, m.code);
            svgs[m.id] = svg;
          } catch (diagramErr: any) {
            console.warn(`Mermaid render failed for ${m.id}:`, diagramErr);
            svgs[m.id] = `<div class="p-3 bg-red-950/40 border border-red-800 rounded text-red-300 text-xs font-mono">Failed to render Mermaid diagram. Click 'Raw Source' to view diagram code.</div>`;
          }
        }
        if (isMounted) {
          setRenderedMermaid(svgs);
        }
      } catch (err) {
        console.warn("Could not load dynamic Mermaid renderer:", err);
      } finally {
        if (isMounted) setMermaidLoading(false);
      }
    };

    renderDiagrams();
    return () => {
      isMounted = false;
    };
  }, [content, activeTab]);

  // Basic word and line count
  const lineCount = content ? content.split("\n").length : 0;
  const wordCount = content ? content.trim().split(/\s+/).length : 0;

  // Markdown Parser & Renderer
  const renderFormattedMarkdown = (raw: string) => {
    if (!raw) return <div className="text-zinc-500 italic">No content available.</div>;

    // Split markdown into blocks (code blocks, tables, paragraphs)
    const blocks: React.ReactNode[] = [];
    const lines = raw.split("\n");
    let inCodeBlock = false;
    let codeLanguage = "";
    let codeBuffer: string[] = [];
    let inTable = false;
    let tableBuffer: string[] = [];
    let mermaidCounter = 0;

    const flushCodeBlock = () => {
      if (codeBuffer.length === 0) return;
      const codeText = codeBuffer.join("\n");
      const blockId = `code-block-${blocks.length}`;

      if (codeLanguage.toLowerCase() === "mermaid") {
        const diagramId = `mermaid-diagram-${mermaidCounter++}`;
        const renderedSvg = renderedMermaid[diagramId];

        blocks.push(
          <div key={blockId} className="my-4 rounded-lg border border-[#26262C] bg-[#09090B] overflow-hidden">
            <div className="flex items-center justify-between px-3 py-2 bg-[#0E0E11] border-b border-[#1C1C21] text-[11px] text-zinc-300 font-mono font-medium">
              <span className="flex items-center gap-1.5">
                <GitBranch className="h-3.5 w-3.5 text-zinc-400" />
                Mermaid Architecture Diagram
              </span>
              <button
                type="button"
                onClick={() => handleCopySnippet(codeText, blockId)}
                className="flex items-center gap-1 text-zinc-400 hover:text-zinc-100 transition-colors cursor-pointer"
              >
                {copiedBlockId === blockId ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                {copiedBlockId === blockId ? "Copied" : "Copy Diagram Code"}
              </button>
            </div>
            <div className="p-4 overflow-x-auto flex justify-center bg-[#09090B] min-h-[140px] items-center">
              {renderedSvg ? (
                <div
                  className="w-full flex justify-center [&_svg]:max-w-full [&_svg]:h-auto"
                  dangerouslySetInnerHTML={{ __html: renderedSvg }}
                />
              ) : (
                <div className="text-center py-6 text-zinc-400 text-xs font-mono space-y-2">
                  <RefreshCw className="h-4 w-4 animate-spin mx-auto text-zinc-400" />
                  <div>Rendering interactive diagram...</div>
                </div>
              )}
            </div>
          </div>
        );
      } else {
        blocks.push(
          <div key={blockId} className="my-3 rounded-md border border-[#1C1C21] bg-[#09090B] overflow-hidden">
            <div className="flex items-center justify-between px-3 py-1.5 bg-[#0E0E11] border-b border-[#1C1C21] text-[11px] text-zinc-400 font-mono">
              <span>{codeLanguage || "text"}</span>
              <button
                type="button"
                onClick={() => handleCopySnippet(codeText, blockId)}
                className="flex items-center gap-1 text-zinc-400 hover:text-zinc-100 transition-colors cursor-pointer"
              >
                {copiedBlockId === blockId ? <Check className="h-3 w-3 text-emerald-400" /> : <Copy className="h-3 w-3" />}
                {copiedBlockId === blockId ? "Copied" : "Copy"}
              </button>
            </div>
            <pre className="p-3 overflow-x-auto text-xs font-mono text-emerald-300 bg-[#09090B] leading-relaxed">
              <code>{codeText}</code>
            </pre>
          </div>
        );
      }
      codeBuffer = [];
      codeLanguage = "";
    };

    const flushTable = () => {
      if (tableBuffer.length === 0) return;
      const tLines = [...tableBuffer];
      tableBuffer = [];
      if (tLines.length < 2) {
        blocks.push(<p key={`tbl-${blocks.length}`} className="my-1 text-xs text-zinc-300">{tLines.join("\n")}</p>);
        return;
      }

      const parseCells = (rowStr: string) => {
        return rowStr
          .trim()
          .replace(/^\|/, "")
          .replace(/\|$/, "")
          .split("|")
          .map((c) => c.trim());
      };

      const headerCells = parseCells(tLines[0]);
      // Skip delimiter line (tLines[1])
      const dataRows = tLines.slice(2).map(parseCells);

      blocks.push(
        <div key={`table-${blocks.length}`} className="my-3 overflow-x-auto rounded-lg border border-[#1C1C21] bg-[#0E0E11]">
          <table className="w-full text-left text-xs border-collapse">
            <thead>
              <tr className="border-b border-[#1C1C21] bg-[#0B0B0E] text-zinc-400 font-mono text-[11px] uppercase">
                {headerCells.map((h, i) => (
                  <th key={i} className="py-2 px-3 whitespace-nowrap">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-[#18181C] text-zinc-300">
              {dataRows.map((row, rIdx) => (
                <tr key={rIdx} className="hover:bg-[#121216] transition-colors">
                  {row.map((cell, cIdx) => (
                    <td key={cIdx} className="py-2 px-3 leading-snug">
                      {formatInlineMarkdown(cell)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    };

    // Helper for inline text (bold, code, links, citation chips)
    const formatInlineMarkdown = (text: string): React.ReactNode => {
      // Citation chip detection [file@sha]
      const citationRegex = /\[([^\]]+)@([a-f0-9]{7,40}|HEAD)\]/g;
      const parts: React.ReactNode[] = [];
      let lastIndex = 0;
      let match;

      while ((match = citationRegex.exec(text)) !== null) {
        if (match.index > lastIndex) {
          parts.push(renderTextDecorations(text.substring(lastIndex, match.index)));
        }
        const filePath = match[1];
        const sha = match[2];
        parts.push(
          <button
            key={`cite-${match.index}`}
            type="button"
            onClick={() => onCitationClick && onCitationClick({ file_path: filePath, commit_sha: sha })}
            className="inline-flex items-center gap-1 mx-1 px-1.5 py-0.5 rounded text-[10px] font-mono bg-[#141418] border border-[#26262C] text-zinc-200 hover:bg-[#1C1C22] hover:text-white transition-colors"
            title={`Verified citation: ${filePath}@${sha}`}
          >
            <span className="font-medium">{filePath}</span>
            <span className="text-zinc-500">@{sha.slice(0, 7)}</span>
          </button>
        );
        lastIndex = match.index + match[0].length;
      }

      if (lastIndex < text.length) {
        parts.push(renderTextDecorations(text.substring(lastIndex)));
      }

      return parts.length === 1 ? parts[0] : <>{parts}</>;
    };

    const renderTextDecorations = (t: string): React.ReactNode => {
      // Replace inline `code` with badge
      const codeRegex = /`([^`]+)`/g;
      const subParts: React.ReactNode[] = [];
      let lIdx = 0;
      let cMatch;

      while ((cMatch = codeRegex.exec(t)) !== null) {
        if (cMatch.index > lIdx) {
          subParts.push(renderBoldItalic(t.substring(lIdx, cMatch.index)));
        }
        subParts.push(
          <code key={`code-${cMatch.index}`} className="px-1.5 py-0.5 rounded bg-[#141418] text-zinc-200 font-mono text-[11px] border border-[#26262C]">
            {cMatch[1]}
          </code>
        );
        lIdx = cMatch.index + cMatch[0].length;
      }
      if (lIdx < t.length) {
        subParts.push(renderBoldItalic(t.substring(lIdx)));
      }
      return subParts.length === 1 ? subParts[0] : <>{subParts}</>;
    };

    const renderBoldItalic = (str: string): React.ReactNode => {
      // Bold **text**
      const boldRegex = /\*\*([^*]+)\*\*/g;
      const bParts: React.ReactNode[] = [];
      let idx = 0;
      let bMatch;
      while ((bMatch = boldRegex.exec(str)) !== null) {
        if (bMatch.index > idx) {
          bParts.push(str.substring(idx, bMatch.index));
        }
        bParts.push(
          <strong key={`b-${bMatch.index}`} className="text-zinc-100 font-semibold">
            {bMatch[1]}
          </strong>
        );
        idx = bMatch.index + bMatch[0].length;
      }
      if (idx < str.length) {
        bParts.push(str.substring(idx));
      }
      return bParts.length === 1 ? bParts[0] : <>{bParts}</>;
    };

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];

      // Code block start / end
      if (line.startsWith("```")) {
        if (inCodeBlock) {
          inCodeBlock = false;
          flushCodeBlock();
        } else {
          if (inTable) {
            inTable = false;
            flushTable();
          }
          inCodeBlock = true;
          codeLanguage = line.replace(/^```/, "").trim();
        }
        continue;
      }

      if (inCodeBlock) {
        codeBuffer.push(line);
        continue;
      }

      // Markdown Table line: begins or contains pipe
      if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
        inTable = true;
        tableBuffer.push(line);
        continue;
      } else if (inTable) {
        inTable = false;
        flushTable();
      }

      // Headings
      if (line.startsWith("# ")) {
        blocks.push(
          <h1 key={`h1-${i}`} className="text-base font-semibold text-zinc-100 mt-5 mb-2.5 pb-2 border-b border-[#1C1C21] flex items-center gap-2">
            <span className="w-1 h-4 bg-zinc-400 rounded-sm"></span>
            {line.replace(/^#\s+/, "")}
          </h1>
        );
        continue;
      }
      if (line.startsWith("## ")) {
        blocks.push(
          <h2 key={`h2-${i}`} className="text-sm font-semibold text-zinc-200 mt-4 mb-2 pb-1 border-b border-[#1C1C21] flex items-center gap-2">
            <span className="w-1 h-3.5 bg-zinc-500 rounded-sm"></span>
            {line.replace(/^##\s+/, "")}
          </h2>
        );
        continue;
      }
      if (line.startsWith("### ")) {
        blocks.push(
          <h3 key={`h3-${i}`} className="text-xs font-semibold text-zinc-200 mt-3.5 mb-1">
            {line.replace(/^###\s+/, "")}
          </h3>
        );
        continue;
      }
      if (line.startsWith("#### ")) {
        blocks.push(
          <h4 key={`h4-${i}`} className="text-[11px] font-mono font-medium text-zinc-400 mt-3 mb-1 uppercase tracking-wider">
            {line.replace(/^####\s+/, "")}
          </h4>
        );
        continue;
      }

      // Blockquote / Alerts
      if (line.startsWith("> ")) {
        blocks.push(
          <div key={`bq-${i}`} className="my-2.5 p-3 rounded-md border-l-2 border-zinc-500 bg-[#141418] text-xs text-zinc-300 leading-relaxed">
            {formatInlineMarkdown(line.replace(/^>\s*/, ""))}
          </div>
        );
        continue;
      }

      // Bullet lists
      if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
        blocks.push(
          <div key={`li-${i}`} className="flex items-start gap-2 my-1 text-xs text-zinc-300 leading-relaxed pl-2">
            <span className="text-zinc-500 font-bold shrink-0 mt-0.5">&bull;</span>
            <div>{formatInlineMarkdown(line.replace(/^[\s-*]+/, ""))}</div>
          </div>
        );
        continue;
      }

      // Numbered lists
      if (/^\d+\.\s/.test(line.trim())) {
        const num = line.trim().match(/^(\d+)\./)?.[1] || "1";
        blocks.push(
          <div key={`num-${i}`} className="flex items-start gap-2 my-1 text-xs text-zinc-300 leading-relaxed pl-2">
            <span className="text-zinc-400 font-mono font-medium text-[11px] shrink-0 mt-0.5 tabular-nums">{num}.</span>
            <div>{formatInlineMarkdown(line.replace(/^\s*\d+\.\s+/, ""))}</div>
          </div>
        );
        continue;
      }

      // Empty line
      if (!line.trim()) {
        continue;
      }

      // Regular paragraph
      blocks.push(
        <p key={`p-${i}`} className="my-2 text-xs text-zinc-300 leading-relaxed">
          {formatInlineMarkdown(line)}
        </p>
      );
    }

    if (inCodeBlock) flushCodeBlock();
    if (inTable) flushTable();

    return blocks;
  };

  return (
    <div className="flex flex-col h-full rounded-lg border border-[#1C1C21] bg-[#09090B] overflow-hidden">
      {/* Action Toolbar & Tab Switcher */}
      <div className="flex items-center justify-between px-4 py-2 bg-[#0E0E11] border-b border-[#1C1C21] flex-wrap gap-2">
        <div className="flex items-center gap-1 p-0.5 rounded-md bg-[#09090B] border border-[#1C1C21]">
          <button
            type="button"
            onClick={() => setActiveTab("rendered")}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition-colors cursor-pointer ${
              activeTab === "rendered"
                ? "bg-zinc-100 text-zinc-950"
                : "text-zinc-400 hover:text-zinc-200"
            }`}
          >
            <Eye className="h-3.5 w-3.5" />
            Rendered Preview
          </button>
          <button
            type="button"
            onClick={() => setActiveTab("raw")}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded text-xs font-medium transition-colors cursor-pointer ${
              activeTab === "raw"
                ? "bg-zinc-100 text-zinc-950"
                : "text-zinc-400 hover:text-zinc-200"
            }`}
          >
            <Code className="h-3.5 w-3.5" />
            Raw Source
          </button>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[11px] font-mono text-zinc-500 hidden sm:inline tabular-nums">
            {lineCount} lines &middot; {wordCount} words
          </span>

          <Button
            variant="outline"
            size="sm"
            onClick={handleCopyMarkdown}
            className="text-xs gap-1.5 h-7"
          >
            {copied ? <Check className="h-3.5 w-3.5 text-emerald-400" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? "Copied" : "Copy Markdown"}
          </Button>

          <Button
            variant="outline"
            size="sm"
            onClick={handleDownload}
            className="text-xs gap-1.5 h-7"
          >
            <Download className="h-3.5 w-3.5" />
            Download .md
          </Button>
        </div>
      </div>

      {/* Content Area */}
      <div className="p-5 overflow-y-auto max-h-[620px] select-text">
        {activeTab === "rendered" ? (
          <div className="space-y-1">{renderFormattedMarkdown(content)}</div>
        ) : (
          <div className="rounded-md border border-[#1C1C21] bg-[#0E0E11] p-4 font-mono text-xs text-zinc-200 whitespace-pre-wrap leading-relaxed select-text">
            {content}
          </div>
        )}
      </div>
    </div>
  );
};

