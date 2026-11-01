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
  Activity,
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
  {
    name: "Operations & Governance",
    href: "/operations",
    icon: Activity,
  },
];

export const Sidebar: React.FC = () => {
  const pathname = usePathname();

  return (
    <aside className="fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-[#1f1f23] bg-[#0c0c0e]">
      {/* Brand Header */}
      <div className="flex h-16 items-center gap-3 px-6 border-b border-[#1f1f23]">
        <div className="flex h-7 w-7 items-center justify-center rounded-md bg-zinc-100 text-zinc-950 font-mono text-xs font-bold">
          F
        </div>
        <div className="flex flex-col">
          <div className="flex items-center gap-1.5">
            <span className="font-bold tracking-tight text-white text-base">ForgeAI</span>
            <Badge variant="default" className="text-[9px] px-1 py-0 h-4 uppercase">
              Core
            </Badge>
          </div>
          <span className="text-[10px] text-zinc-400 font-mono tracking-wider">
            GOVERNANCE MATRIX
          </span>
        </div>
      </div>

      {/* Main Navigation Links */}
      <div className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
        <div className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-zinc-500">
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
              className={`group flex items-center gap-3 rounded-lg px-3 py-2 text-xs transition-colors ${
                isActive
                  ? "bg-[#1c1c21] text-zinc-100 font-medium"
                  : "text-zinc-400 hover:bg-[#141418] hover:text-zinc-200"
              }`}
            >
              <Icon
                className={`h-4 w-4 shrink-0 transition-colors ${
                  isActive
                    ? "text-zinc-100"
                    : "text-zinc-400 group-hover:text-zinc-200"
                }`}
              />
              <span>{item.name}</span>
            </Link>
          );
        })}

        {/* Traceability Anchor Link */}
        <div className="pt-6">
          <div className="px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-zinc-500">
            Core Governance Anchor
          </div>
          <Link
            href="/traceability"
            className="group flex items-center gap-3 rounded-lg px-3 py-2 text-xs font-medium text-zinc-300 hover:text-white bg-[#131316] border border-[#232329] hover:border-zinc-700 transition-colors"
          >
            <Layers className="h-4 w-4 text-zinc-400 shrink-0 group-hover:text-zinc-200" />
            <div className="flex flex-col">
              <span className="font-semibold text-zinc-200">Traceability Spine</span>
              <span className="text-[10px] text-zinc-500">REQ → TASK → PR → AI</span>
            </div>
          </Link>
        </div>
      </div>

      {/* Bottom Health & Telemetry Info */}
      <div className="p-4 border-t border-[#1f1f23]">
        <div className="rounded-lg border border-[#232329] bg-[#131316] p-3 space-y-2">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-zinc-400 flex items-center gap-1.5">
              <Terminal className="h-3 w-3 text-zinc-400" />
              Correlation Trace
            </span>
            <span className="text-emerald-400 font-mono text-[10px]">W3C ACTIVE</span>
          </div>
          <div className="text-[10px] text-zinc-500 leading-tight">
            All requests signed with JWT & tenant isolation headers.
          </div>
        </div>
      </div>
    </aside>
  );
};
