"use client";

import React, { useEffect, useState } from "react";
import { OrgSwitcher } from "./OrgSwitcher";
import { useAuth } from "@/lib/auth-context";
import {
  Bell,
  Search,
  ShieldCheck,
  Activity,
  LogOut,
  User as UserIcon,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";

export const TopNav: React.FC = () => {
  const { user, logout } = useAuth();
  const [gatewayOnline, setGatewayOnline] = useState<boolean | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);

  // Poll gateway health probe directly with no-cors to avoid CORS errors & eliminate dev server reload loops
  useEffect(() => {
    let isMounted = true;
    const checkGateway = async () => {
      try {
        await fetch("http://localhost:8080/actuator/health", {
          mode: "no-cors",
          cache: "no-store",
        });
        if (isMounted) setGatewayOnline(true);
      } catch {
        if (isMounted) setGatewayOnline(false);
      }
    };

    checkGateway();
    const interval = setInterval(checkGateway, 15000);
    return () => {
      isMounted = false;
      clearInterval(interval);
    };
  }, []);

  return (
    <header className="sticky top-0 z-30 flex h-16 w-full items-center justify-between border-b border-slate-800/80 bg-slate-950/70 px-6 backdrop-blur-xl">
      {/* Left: Search & Breadcrumbs */}
      <div className="flex items-center gap-4">
        <div className="relative w-64 md:w-80">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400 pointer-events-none" />
          <input
            type="text"
            placeholder="Search tasks, PRs, requirements..."
            className="h-9 w-full rounded-lg border border-slate-800 bg-slate-900/90 pl-9 pr-4 text-xs text-slate-200 placeholder-slate-500 focus:border-indigo-500 focus:outline-none focus:ring-1 focus:ring-indigo-500 transition-all"
          />
        </div>
      </div>

      {/* Right: Gateway Health, OrgSwitcher, User */}
      <div className="flex items-center gap-3.5">
        {/* Gateway Health Indicator */}
        <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-lg border border-slate-800 bg-slate-900/60 text-xs">
          <span className="relative flex h-2 w-2">
            <span
              className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${
                gatewayOnline === true
                  ? "bg-emerald-400"
                  : gatewayOnline === false
                  ? "bg-rose-400"
                  : "bg-amber-400"
              }`}
            />
            <span
              className={`relative inline-flex rounded-full h-2 w-2 ${
                gatewayOnline === true
                  ? "bg-emerald-500"
                  : gatewayOnline === false
                  ? "bg-rose-500"
                  : "bg-amber-500"
              }`}
            />
          </span>
          <span className="text-slate-400 text-[11px] font-mono">Gateway:</span>
          <span
            className={`font-semibold text-[11px] ${
              gatewayOnline === true
                ? "text-emerald-400"
                : gatewayOnline === false
                ? "text-rose-400"
                : "text-amber-400"
            }`}
          >
            {gatewayOnline === true
              ? "ONLINE (8080)"
              : gatewayOnline === false
              ? "OFFLINE"
              : "CONNECTING"}
          </span>
        </div>

        {/* Tenant Organization Switcher */}
        <OrgSwitcher />

        {/* Notifications Icon */}
        <button
          type="button"
          className="relative rounded-lg p-2 text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
          title="Notifications"
        >
          <Bell className="h-4 w-4" />
          <span className="absolute top-1.5 right-1.5 h-1.5 w-1.5 rounded-full bg-indigo-500 ring-2 ring-slate-950" />
        </button>

        {/* User Profile */}
        <div className="relative">
          <button
            onClick={() => setUserMenuOpen(!userMenuOpen)}
            className="flex items-center gap-2.5 rounded-lg p-1.5 hover:bg-slate-800 transition-colors"
          >
            <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-tr from-indigo-600 to-purple-600 text-xs font-semibold text-white shadow-sm">
              {user?.fullName?.charAt(0) || "U"}
            </div>
            <div className="hidden lg:block text-left text-xs">
              <div className="font-medium text-slate-200">{user?.fullName || "Engineer"}</div>
              <div className="text-[10px] text-slate-500 truncate max-w-[110px]">
                {user?.email || "admin@forgeai.dev"}
              </div>
            </div>
          </button>

          {userMenuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setUserMenuOpen(false)}
              />
              <div className="absolute right-0 mt-2 w-56 rounded-xl border border-slate-700 bg-slate-900 p-2 shadow-2xl backdrop-blur-xl z-50">
                <div className="px-3 py-2 border-b border-slate-800">
                  <p className="text-xs font-semibold text-white">{user?.fullName}</p>
                  <p className="text-[11px] text-slate-400 truncate">{user?.email}</p>
                </div>
                <div className="pt-2">
                  <button
                    onClick={logout}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-xs text-rose-400 hover:bg-rose-500/10 transition-colors font-medium"
                  >
                    <LogOut className="h-4 w-4" />
                    Sign Out
                  </button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
};
