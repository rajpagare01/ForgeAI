"use client";

import React, { useEffect, useRef, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  Bell,
  Search,
  LogOut,
  CheckCircle2,
  Unlink,
  ExternalLink,
  AlertCircle,
  Activity,
  ShieldCheck,
  Radio,
  ChevronRight,
} from "lucide-react";

const GitHubIcon: React.FC<{ className?: string }> = ({ className = "h-3.5 w-3.5" }) => (
  <svg className={`${className} fill-current`} viewBox="0 0 24 24">
    <path
      fillRule="evenodd"
      clipRule="evenodd"
      d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"
    />
  </svg>
);

export const TopNav: React.FC = () => {
  const router = useRouter();
  const pathname = usePathname();
  const { user, logout, githubStatus, disconnectGitHubAccount, fetchGitHubStatus } = useAuth();
  const { isPersonalWorkspace, currentOrg, activeWorkspace } = useTenant();
  const [gatewayOnline, setGatewayOnline] = useState<boolean | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const notificationsRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [disconnecting, setDisconnecting] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName;
      const isInput = tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || (e.target as HTMLElement)?.isContentEditable;
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
      } else if (e.key === "/" && !isInput) {
        e.preventDefault();
        searchInputRef.current?.focus();
      } else if (e.key === "Escape" && document.activeElement === searchInputRef.current) {
        searchInputRef.current?.blur();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  useEffect(() => {
    const handleOutsideClick = (e: MouseEvent) => {
      const target = e.target as Node;
      if (notificationsOpen && notificationsRef.current && !notificationsRef.current.contains(target)) {
        setNotificationsOpen(false);
      }
      if (userMenuOpen && userMenuRef.current && !userMenuRef.current.contains(target)) {
        setUserMenuOpen(false);
      }
    };
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setNotificationsOpen(false);
        setUserMenuOpen(false);
      }
    };
    document.addEventListener("mousedown", handleOutsideClick);
    document.addEventListener("keydown", handleEsc);
    return () => {
      document.removeEventListener("mousedown", handleOutsideClick);
      document.removeEventListener("keydown", handleEsc);
    };
  }, [notificationsOpen, userMenuOpen]);

  useEffect(() => {
    let isMounted = true;
    const checkGateway = async () => {
      try {
        const gatewayBase = apiClient.getBaseUrl();
        const res = await fetch(`${gatewayBase}/actuator/health`, {
          cache: "no-store",
        });
        if (isMounted) setGatewayOnline(res.ok);
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

  const handleConnectGitHub = async () => {
    setActionError(null);
    setConnecting(true);
    try {
      const callbackUri = `${window.location.origin}/auth/github/callback`;
      const res = await apiClient.get<{ authorizationUrl: string; state: string }>(
        `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`
      );
      if (res?.authorizationUrl) {
        if (typeof window !== "undefined") {
          sessionStorage.setItem("forgeai_github_oauth_action", "link");
        }
        window.location.href = res.authorizationUrl;
      } else {
        setActionError("Unable to start GitHub linking session.");
        setConnecting(false);
      }
    } catch (err: any) {
      setConnecting(false);
      setActionError(err?.message || "Failed to start GitHub linking.");
    }
  };

  const handleDisconnectGitHub = async () => {
    setActionError(null);
    setDisconnecting(true);
    try {
      await disconnectGitHubAccount();
      await fetchGitHubStatus();
      setUserMenuOpen(false);
    } catch (err: any) {
      setActionError(err?.message || "Failed to disconnect GitHub account.");
    } finally {
      setDisconnecting(false);
    }
  };

  const isGitHubLinked = !!githubStatus?.linked && !!githubStatus?.identity;
  const githubIdentity = githubStatus?.identity;

  // Resolve current section title for dynamic breadcrumb
  const sectionTitle = (() => {
    if (pathname === "/") return "Overview";
    if (pathname.startsWith("/projects")) return "Projects & Issues";
    if (pathname.startsWith("/repositories")) return "Repositories";
    if (pathname.startsWith("/test-runs")) return "Test Runs";
    if (pathname.startsWith("/ai-insights")) return "PR & Code Intelligence";
    if (pathname.startsWith("/traceability")) return "Traceability Graph";
    if (pathname.startsWith("/operations")) return "Operations & Outbox";
    return "Dashboard";
  })();

  const workspaceLabel = isPersonalWorkspace
    ? "Personal"
    : currentOrg?.name || "Organization";

  return (
    <header className="sticky top-0 z-30 flex h-12 w-full items-center justify-between border-b border-[#1C1C21] bg-[#09090B]/85 px-6 backdrop-blur-md">
      {/* Left: Dynamic Breadcrumb Bar */}
      <div className="flex items-center gap-2 text-xs">
        <span className="font-mono text-[11px] px-1.5 py-0.5 rounded border border-[#26262C] bg-[#141418] text-zinc-400">
          {workspaceLabel}
        </span>
        <ChevronRight className="h-3.5 w-3.5 text-zinc-600" />
        <span className="font-medium text-zinc-200">{sectionTitle}</span>
      </div>

      {/* Center & Right: Search, Gateway Pill, GitHub Chip, Notifications, User */}
      <div className="flex items-center gap-3">
        {/* Compact Search Bar with '/' shortcut hint */}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (searchQuery.trim()) {
              router.push(`/projects?q=${encodeURIComponent(searchQuery.trim())}`);
            }
          }}
          className="relative hidden sm:block w-60"
        >
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-zinc-500 pointer-events-none" />
          <input
            ref={searchInputRef}
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search tasks, PRs, SHAs..."
            className="h-7 w-full rounded-md border border-[#26262C] bg-[#0E0E11] pl-8 pr-7 text-xs text-zinc-200 placeholder-zinc-500 focus:border-zinc-500 focus:outline-none transition-colors"
            aria-label="Search tasks, PRs, requirements"
          />
          <kbd className="absolute right-2 top-1/2 -translate-y-1/2 font-mono text-[10px] text-zinc-500 border border-zinc-800 rounded px-1 bg-[#09090B]">
            /
          </kbd>
        </form>

        {/* Gateway Health Pill */}
        <div className="hidden md:flex h-7 items-center gap-1.5 px-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11] font-mono text-[11px]">
          <span
            className={`inline-block h-1.5 w-1.5 rounded-full ${gatewayOnline === true
              ? "bg-emerald-400"
              : gatewayOnline === false
                ? "bg-rose-400"
                : "bg-amber-400"
              }`}
          />
          <span className="text-zinc-500">Gateway:</span>
          <span
            className={`${gatewayOnline === true
              ? "text-emerald-400 font-medium"
              : gatewayOnline === false
                ? "text-rose-400 font-medium"
                : "text-amber-400 font-medium"
              }`}
          >
            {gatewayOnline === true ? "ONLINE" : gatewayOnline === false ? "OFFLINE" : "CHECKING"}
          </span>
        </div>

        {/* GitHub Connection Chip */}
        {isGitHubLinked ? (
          <div
            className="hidden sm:flex h-7 items-center gap-1.5 px-2 rounded-md border border-[#26262C] bg-[#0E0E11] text-[11px] text-zinc-300"
            title={`Connected GitHub Account: @${githubIdentity?.githubLogin}`}
          >
            {githubIdentity?.avatarUrl ? (
              <img
                src={githubIdentity.avatarUrl}
                alt={githubIdentity.githubLogin}
                className="h-3.5 w-3.5 rounded-full border border-zinc-700 object-cover"
              />
            ) : (
              <GitHubIcon className="h-3.5 w-3.5 text-zinc-400" />
            )}
            <span className="font-mono text-zinc-300">@{githubIdentity?.githubLogin}</span>
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
          </div>
        ) : (
          <button
            onClick={handleConnectGitHub}
            disabled={connecting}
            className="hidden sm:flex h-7 items-center gap-1.5 px-2 rounded-md border border-zinc-700 bg-[#141418] hover:bg-[#1C1C22] text-[11px] font-medium text-zinc-300 transition-colors cursor-pointer"
            title="Connect your GitHub account"
          >
            <GitHubIcon className="h-3.5 w-3.5 text-zinc-400" />
            <span>{connecting ? "Connecting..." : "Connect GitHub"}</span>
          </button>
        )}

        {/* Notifications Icon & Popover */}
        <div ref={notificationsRef} className="relative">
          <button
            type="button"
            onClick={() => setNotificationsOpen(!notificationsOpen)}
            className="flex h-7 w-7 items-center justify-center rounded-md border border-[#26262C] bg-[#0E0E11] text-zinc-400 hover:text-zinc-200 hover:bg-[#141418] transition-colors cursor-pointer"
            title="System & Governance Alerts"
          >
            <Bell className="h-3.5 w-3.5" />
          </button>

          {notificationsOpen && (
              <div className="absolute right-0 mt-1.5 w-80 rounded-lg border border-[#26262C] bg-[#0E0E11] p-3 shadow-2xl z-50 space-y-2">
                <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
                  <span className="text-xs font-semibold text-zinc-100">Telemetry & Alerts</span>
                  <span className="text-[10px] font-mono text-emerald-400 bg-emerald-950/20 border border-emerald-500/30 px-1.5 py-0.5 rounded">
                    All Active
                  </span>
                </div>

                <div className="space-y-1.5 text-xs">
                  <div className="flex items-start gap-2 p-2 rounded-md bg-[#141418] border border-[#1C1C21]">
                    <Activity className="h-3.5 w-3.5 text-emerald-400 shrink-0 mt-0.5" />
                    <div>
                      <div className="font-medium text-zinc-200 text-[11px]">API Gateway Status</div>
                      <div className="text-[10px] text-zinc-400 mt-0.5">
                        Gateway is {gatewayOnline ? "ONLINE and routing requests" : "connecting..."}.
                      </div>
                    </div>
                  </div>

                  {isGitHubLinked && (
                    <div className="flex items-start gap-2 p-2 rounded-md bg-[#141418] border border-[#1C1C21]">
                      <ShieldCheck className="h-3.5 w-3.5 text-zinc-300 shrink-0 mt-0.5" />
                      <div>
                        <div className="font-medium text-zinc-200 text-[11px]">GitHub App Ingestion</div>
                        <div className="text-[10px] text-zinc-400 mt-0.5">
                          Webhook deliveries verified for @{githubIdentity?.githubLogin}.
                        </div>
                      </div>
                    </div>
                  )}

                  <div className="flex items-start gap-2 p-2 rounded-md bg-[#141418] border border-[#1C1C21]">
                    <CheckCircle2 className="h-3.5 w-3.5 text-zinc-300 shrink-0 mt-0.5" />
                    <div>
                      <div className="font-medium text-zinc-200 text-[11px]">AI Synthesis Pipeline</div>
                      <div className="text-[10px] text-zinc-400 mt-0.5">
                        PR impact, AST blast radius & automated suite generators ready.
                      </div>
                    </div>
                  </div>
                </div>
              </div>
          )}
        </div>

        {/* User Profile Menu */}
        <div ref={userMenuRef} className="relative">
          <button
            onClick={() => {
              setUserMenuOpen(!userMenuOpen);
              setActionError(null);
            }}
            className="flex h-7 items-center gap-1.5 rounded-md border border-[#26262C] bg-[#0E0E11] px-1.5 hover:bg-[#141418] transition-colors cursor-pointer"
          >
            {isGitHubLinked && githubIdentity?.avatarUrl ? (
              <img
                src={githubIdentity.avatarUrl}
                alt={githubIdentity.githubLogin}
                className="h-4 w-4 rounded-full border border-zinc-700 object-cover"
              />
            ) : (
              <div className="flex h-4 w-4 items-center justify-center rounded bg-zinc-800 text-[10px] font-semibold text-zinc-200">
                {user?.fullName?.charAt(0) || user?.email?.charAt(0)?.toUpperCase() || "U"}
              </div>
            )}
            <span className="hidden lg:inline text-[11px] font-medium text-zinc-300 max-w-[90px] truncate">
              {user?.fullName || (user?.email ? user.email.split("@")[0] : "User")}
            </span>
          </button>

          {userMenuOpen && (
              <div className="absolute right-0 mt-1.5 w-64 rounded-lg border border-[#26262C] bg-[#0E0E11] p-2 shadow-2xl z-50">
                {/* Header Profile Details */}
                <div className="px-2.5 py-1.5 border-b border-[#1C1C21]">
                  <p className="text-xs font-semibold text-zinc-100">{user?.fullName}</p>
                  <p className="text-[11px] text-zinc-400 truncate font-mono">{user?.email}</p>
                </div>

                {/* GitHub Identity Card */}
                <div className="px-2.5 py-2 border-b border-[#1C1C21]">
                  <div className="flex items-center justify-between text-xs mb-1.5">
                    <span className="font-medium text-zinc-300 flex items-center gap-1.5 text-[11px]">
                      <GitHubIcon className="h-3 w-3 text-zinc-400" />
                      GitHub Account
                    </span>
                    {isGitHubLinked && (
                      <span className="flex items-center gap-1 text-[10px] text-emerald-400 font-mono">
                        <CheckCircle2 className="h-3 w-3" />
                        Linked
                      </span>
                    )}
                  </div>

                  {isGitHubLinked ? (
                    <div className="space-y-1.5">
                      <div className="flex items-center justify-between text-[11px] bg-[#141418] p-1.5 rounded-md border border-[#1C1C21]">
                        <div className="flex items-center gap-1.5 truncate">
                          {githubIdentity?.avatarUrl && (
                            <img
                              src={githubIdentity.avatarUrl}
                              alt={githubIdentity.githubLogin}
                              className="h-4 w-4 rounded-full object-cover shrink-0"
                            />
                          )}
                          <span className="font-mono text-zinc-200 truncate text-[11px]">
                            @{githubIdentity?.githubLogin}
                          </span>
                        </div>
                        {githubIdentity?.profileUrl && (
                          <a
                            href={githubIdentity.profileUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-zinc-400 hover:text-zinc-100 p-0.5"
                            title="Open GitHub Profile"
                          >
                            <ExternalLink className="h-3 w-3" />
                          </a>
                        )}
                      </div>

                      <button
                        onClick={handleDisconnectGitHub}
                        disabled={disconnecting}
                        className="flex w-full items-center justify-center gap-1.5 rounded-md border border-[#26262C] hover:border-rose-500/30 bg-[#141418] hover:bg-rose-950/20 py-1 text-[11px] font-medium text-zinc-300 hover:text-rose-400 transition-colors disabled:opacity-60 cursor-pointer"
                      >
                        <Unlink className="h-3 w-3 text-zinc-400" />
                        {disconnecting ? "Disconnecting..." : "Disconnect GitHub"}
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-1.5">
                      <p className="text-[11px] text-zinc-400">
                        Link your GitHub account to sync repositories and inspect PRs.
                      </p>
                      <button
                        onClick={handleConnectGitHub}
                        disabled={connecting}
                        className="flex w-full items-center justify-center gap-1.5 rounded-md border border-zinc-700 bg-zinc-100 hover:bg-white py-1 text-[11px] font-medium text-zinc-950 transition-colors disabled:opacity-60 cursor-pointer"
                      >
                        <GitHubIcon className="h-3 w-3 text-zinc-950" />
                        {connecting ? "Connecting..." : "Connect GitHub"}
                      </button>
                    </div>
                  )}

                  {actionError && (
                    <div className="mt-1.5 flex items-start gap-1.5 p-1.5 rounded-md bg-rose-950/40 border border-rose-500/30 text-[10px] text-rose-300">
                      <AlertCircle className="h-3 w-3 text-rose-400 shrink-0 mt-0.5" />
                      <span>{actionError}</span>
                    </div>
                  )}
                </div>

                {/* Sign Out Button */}
                <div className="pt-1">
                  <button
                    onClick={logout}
                    className="flex w-full items-center gap-1.5 rounded-md px-2 py-1.5 text-left text-xs text-rose-400 hover:bg-rose-500/10 transition-colors font-medium cursor-pointer"
                  >
                    <LogOut className="h-3.5 w-3.5" />
                    Sign Out
                  </button>
                </div>
              </div>
          )}
        </div>
      </div>
    </header>
  );
};
