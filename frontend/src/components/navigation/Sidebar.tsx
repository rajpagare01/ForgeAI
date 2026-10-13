"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FolderGit2,
  GitPullRequest,
  CheckCircle2,
  Sparkles,
  Layers,
  Cpu,
  Terminal,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";

const navigationItems = [
  {
    name: "Overview",
    href: "/",
    icon: LayoutDashboard,
  },
  {
    name: "Projects & Tasks",
    href: "/projects",
    icon: FolderGit2,
  },
  {
    name: "Repositories & Webhooks",
    href: "/repositories",
    icon: GitPullRequest,
  },
  {
    name: "CI Test Runs",
    href: "/test-runs",
    icon: CheckCircle2,
  },
  {
    name: "AI Insights & Blast Radius",
    href: "/ai-insights",
    icon: Sparkles,
  },
];

export const Sidebar: React.FC = () => {
  const pathname = usePathname();

  return (
    <aside className="fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-slate-800/80 bg-slate-950/80 backdrop-blur-xl">
      {/* Brand Header */}
      <div className="flex h-16 items-center gap-3 px-6 border-b border-slate-800/80">
        <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-gradient-to-tr from-indigo-600 to-purple-600 shadow-md shadow-indigo-500/30 border border-indigo-400/30">
          <Cpu className="h-5 w-5 text-white" />
        </div>
        <div className="flex flex-col">
          <div className="flex items-center gap-1.5">
            <span className="font-bold tracking-tight text-white text-base">ForgeAI</span>
            <Badge variant="default" className="text-[9px] px-1 py-0 h-4 uppercase">
              Core
            </Badge>
          </div>
          <span className="text-[10px] text-slate-400 font-mono tracking-wider">
            GOVERNANCE MATRIX
          </span>
        </div>
      </div>

      {/* Main Navigation Links */}
      <div className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
        <div className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
          Platform Workspace
        </div>
        {navigationItems.map((item) => {
          const isActive =
            item.href === "/"
              ? pathname === "/"
              : pathname.startsWith(item.href);
          const Icon = item.icon;

          return (
            <Link
              key={item.name}
              href={item.href}
              className={`group flex items-center gap-3 rounded-xl px-3 py-2.5 text-xs font-medium transition-all ${
                isActive
                  ? "bg-indigo-600/15 text-indigo-300 border border-indigo-500/30 shadow-sm"
                  : "text-slate-400 hover:bg-slate-900 hover:text-slate-200"
              }`}
            >
              <Icon
                className={`h-4 w-4 shrink-0 transition-colors ${
                  isActive
                    ? "text-indigo-400"
                    : "text-slate-400 group-hover:text-slate-200"
                }`}
              />
              <span>{item.name}</span>
            </Link>
          );
        })}

        {/* Traceability Anchor Link */}
        <div className="pt-6">
          <div className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
            Core Governance Anchor
          </div>
          <Link
            href="/projects/proj-forgeai-core/traceability"
            className="group flex items-center gap-3 rounded-xl px-3 py-2.5 text-xs font-medium text-slate-300 hover:bg-slate-900 border border-slate-800/80 hover:border-indigo-500/40 transition-all bg-gradient-to-r from-indigo-950/20 to-purple-950/20"
          >
            <Layers className="h-4 w-4 text-indigo-400 shrink-0" />
            <div className="flex flex-col">
              <span className="font-semibold text-indigo-300">Traceability Spine</span>
              <span className="text-[10px] text-slate-400">REQ → TASK → PR → AI</span>
            </div>
          </Link>
        </div>
      </div>

      {/* Bottom Health & Telemetry Info */}
      <div className="p-4 border-t border-slate-800/80">
        <div className="rounded-xl border border-slate-800 bg-slate-900/50 p-3 space-y-2">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-slate-400 flex items-center gap-1.5">
              <Terminal className="h-3 w-3 text-indigo-400" />
              Correlation Trace
            </span>
            <span className="text-emerald-400 font-mono text-[10px]">W3C ACTIVE</span>
          </div>
          <div className="text-[10px] text-slate-400 leading-tight">
            All requests signed with JWT & tenant isolation headers.
          </div>
        </div>
      </div>
    </aside>
  );
};
