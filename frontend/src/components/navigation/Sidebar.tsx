"use client";

import React, { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FolderGit2,
  GitPullRequest,
  CheckCircle2,
  Sparkles,
  Layers,
  Activity,
  Terminal,
} from "lucide-react";
import { OrgSwitcher } from "./OrgSwitcher";
import { apiClient } from "@/lib/api-client";

interface NavItem {
  name: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
}

const workspaceNavItems: NavItem[] = [
  { name: "Overview", href: "/", icon: LayoutDashboard },
  { name: "Projects & Issues", href: "/projects", icon: FolderGit2 },
  { name: "Repositories", href: "/repositories", icon: GitPullRequest },
  { name: "Test Runs", href: "/test-runs", icon: CheckCircle2 },
];

const intelligenceNavItems: NavItem[] = [
  { name: "PR & Code Intelligence", href: "/ai-insights", icon: Sparkles },
  { name: "Traceability Graph", href: "/traceability", icon: Layers },
  { name: "Operations & Outbox", href: "/operations", icon: Activity },
];

export const Sidebar: React.FC = () => {
  const pathname = usePathname();
  const [gatewayStatus, setGatewayStatus] = useState<"ok" | "degraded" | "down">("ok");

  useEffect(() => {
    let mounted = true;
    const checkHealth = async () => {
      try {
        const gatewayBase = apiClient.getBaseUrl();
        const res = await fetch(`${gatewayBase}/actuator/health`, { cache: "no-store" });
        if (mounted) setGatewayStatus(res.ok ? "ok" : "degraded");
      } catch {
        if (mounted) setGatewayStatus("down");
      }
    };
    checkHealth();
    const interval = setInterval(checkHealth, 20000);
    return () => {
      mounted = false;
      clearInterval(interval);
    };
  }, []);

  return (
    <aside className="fixed inset-y-0 left-0 z-40 flex w-56 flex-col border-r border-[#1C1C21] bg-[#09090B] select-none">
      {/* Top Header: ForgeAI Mark + OrgSwitcher */}
      <div className="flex h-12 items-center justify-between px-3 border-b border-[#1C1C21] bg-[#09090B]">
        <div className="flex items-center gap-2">
          {/* 20px x 20px Matte Geometric Emblem */}
          <div className="flex h-5 w-5 items-center justify-center rounded bg-zinc-100 text-zinc-950 font-bold text-[11px] tracking-tight shadow-xs">
            F
          </div>
          <span className="font-semibold text-xs tracking-tight text-zinc-100">ForgeAI</span>
        </div>
        <OrgSwitcher />
      </div>

      {/* Navigation Groups */}
      <div className="flex-1 overflow-y-auto px-2 py-3 space-y-4">
        {/* Group 1: Workspace */}
        <div className="space-y-0.5">
          <div className="px-2 pb-1.5 text-[10px] font-mono font-medium uppercase tracking-[0.08em] text-zinc-500">
            Workspace
          </div>
          {workspaceNavItems.map((item) => {
            const isActive =
              item.href === "/"
                ? pathname === "/"
                : pathname.startsWith(item.href);
            const Icon = item.icon;

            return (
              <Link
                key={item.name}
                href={item.href}
                className={`group flex h-7 items-center gap-2.5 rounded-md px-2 text-[12px] font-medium transition-colors ${isActive
                  ? "bg-zinc-900 text-zinc-100 border border-zinc-800/80 shadow-xs"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/50"
                  }`}
              >
                <Icon
                  className={`h-3.5 w-3.5 shrink-0 transition-colors ${isActive ? "text-zinc-100" : "text-zinc-500 group-hover:text-zinc-300"
                    }`}
                />
                <span className="truncate">{item.name}</span>
              </Link>
            );
          })}
        </div>

        {/* Group 2: Intelligence & Governance */}
        <div className="space-y-0.5 pt-1 border-t border-[#1C1C21]/60">
          <div className="px-2 pb-1.5 pt-2 text-[10px] font-mono font-medium uppercase tracking-[0.08em] text-zinc-500">
            Intelligence
          </div>
          {intelligenceNavItems.map((item) => {
            const isActive = pathname.startsWith(item.href);
            const Icon = item.icon;

            return (
              <Link
                key={item.name}
                href={item.href}
                className={`group flex h-7 items-center gap-2.5 rounded-md px-2 text-[12px] font-medium transition-colors ${isActive
                  ? "bg-zinc-900 text-zinc-100 border border-zinc-800/80 shadow-xs"
                  : "text-zinc-400 hover:text-zinc-200 hover:bg-zinc-900/50"
                  }`}
              >
                <Icon
                  className={`h-3.5 w-3.5 shrink-0 transition-colors ${isActive ? "text-zinc-100" : "text-zinc-500 group-hover:text-zinc-300"
                    }`}
                />
                <span className="truncate">{item.name}</span>
              </Link>
            );
          })}
        </div>
      </div>

      {/* Sidebar Footer: Compact System Status Indicator */}
      <div className="p-2.5 border-t border-[#1C1C21] bg-[#0B0B0E]">
        <div className="flex items-center justify-between text-[11px] font-mono text-zinc-400 px-1 py-0.5">
          <div className="flex items-center gap-1.5">
            <span
              className={`h-1.5 w-1.5 rounded-full ${gatewayStatus === "ok"
                ? "bg-emerald-400"
                : gatewayStatus === "degraded"
                  ? "bg-amber-400"
                  : "bg-rose-400"
                }`}
            />
            <span className="text-[10px] text-zinc-400">Gateway {gatewayStatus.toUpperCase()}</span>
          </div>
          <kbd className="font-mono text-[9px] text-zinc-500 border border-zinc-800 rounded px-1 py-0.2 bg-[#09090B]">
            ⌘K
          </kbd>
        </div>
      </div>
    </aside>
  );
};
