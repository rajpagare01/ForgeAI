"use client";

import React, { useEffect, useState } from "react";
import { OrgSwitcher } from "./OrgSwitcher";
import { useAuth } from "@/lib/auth-context";
import { apiClient } from "@/lib/api-client";
import {
  Bell,
  Search,
  LogOut,
  CheckCircle2,
  Unlink,
  ExternalLink,
  AlertCircle,
} from "lucide-react";

const GitHubIcon: React.FC<{ className?: string }> = ({ className = "h-4 w-4" }) => (
  <svg className={`${className} fill-current`} viewBox="0 0 24 24">
    <path
      fillRule="evenodd"
      clipRule="evenodd"
      d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z"
    />
  </svg>
);

export const TopNav: React.FC = () => {
  const { user, logout, githubStatus, disconnectGitHubAccount, fetchGitHubStatus } = useAuth();
  const [gatewayOnline, setGatewayOnline] = useState<boolean | null>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [disconnecting, setDisconnecting] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Poll gateway health probe directly with no-cors to avoid CORS errors & eliminate dev server reload loops
  useEffect(() => {
    let isMounted = true;
    const checkGateway = async () => {
      try {
        const gatewayBase = apiClient.getBaseUrl();
        await fetch(`${gatewayBase}/actuator/health`, {
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

  const handleConnectGitHub = async () => {
    setActionError(null);
    setConnecting(true);
    try {
      const callbackUri = `${window.location.origin}/auth/github/callback`;
      const res = await apiClient.get<{ authorizationUrl: string; state: string }>(
        `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`
      );
      if (res?.authorizationUrl) {
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
            aria-label="Search tasks, PRs, requirements"
          />
        </div>
      </div>

      {/* Right: Gateway Health, OrgSwitcher, GitHub Status, User */}
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
              ? "ONLINE"
              : gatewayOnline === false
              ? "OFFLINE"
              : "CONNECTING"}
          </span>
        </div>

        {/* Tenant Organization Switcher */}
        <OrgSwitcher />

        {/* GitHub Linked Badge indicator */}
        {isGitHubLinked ? (
          <div
            className="hidden md:flex items-center gap-1.5 px-2.5 py-1 rounded-lg border border-slate-800 bg-slate-900/70 text-xs text-slate-300"
            title={`Connected GitHub Account: @${githubIdentity?.githubLogin}`}
          >
            {githubIdentity?.avatarUrl ? (
              <img
                src={githubIdentity.avatarUrl}
                alt={githubIdentity.githubLogin}
                className="h-4 w-4 rounded-full border border-slate-700 object-cover"
              />
            ) : (
              <GitHubIcon className="h-3.5 w-3.5 text-slate-400" />
            )}
            <span className="text-[11px] font-medium text-slate-300">
              @{githubIdentity?.githubLogin}
            </span>
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
          </div>
        ) : (
          <button
            onClick={handleConnectGitHub}
            disabled={connecting}
            className="hidden md:flex items-center gap-1.5 px-2.5 py-1 rounded-lg border border-indigo-500/30 bg-indigo-950/30 hover:bg-indigo-900/40 text-xs font-medium text-indigo-300 transition-colors cursor-pointer"
            title="Connect your GitHub account"
          >
            <GitHubIcon className="h-3.5 w-3.5 text-indigo-400" />
            <span className="text-[11px]">
              {connecting ? "Connecting..." : "Connect GitHub"}
            </span>
          </button>
        )}

        {/* Notifications Icon (No fake unread activity badge) */}
        <button
          type="button"
          className="relative rounded-lg p-2 text-slate-400 hover:bg-slate-800 hover:text-slate-200 transition-colors"
          title="Notifications"
        >
          <Bell className="h-4 w-4" />
        </button>

        {/* User Profile */}
        <div className="relative">
          <button
            onClick={() => {
              setUserMenuOpen(!userMenuOpen);
              setActionError(null);
            }}
            className="flex items-center gap-2.5 rounded-lg p-1.5 hover:bg-slate-800 transition-colors cursor-pointer"
          >
            {isGitHubLinked && githubIdentity?.avatarUrl ? (
              <img
                src={githubIdentity.avatarUrl}
                alt={githubIdentity.githubLogin}
                className="h-7 w-7 rounded-lg border border-slate-700 object-cover"
              />
            ) : (
              <div className="flex h-7 w-7 items-center justify-center rounded-lg bg-gradient-to-tr from-indigo-600 to-purple-600 text-xs font-semibold text-white shadow-sm">
                {user?.fullName?.charAt(0) || user?.email?.charAt(0)?.toUpperCase() || "U"}
              </div>
            )}
            <div className="hidden lg:block text-left text-xs">
              <div className="font-medium text-slate-200">
                {user?.fullName || (user?.email ? user.email.split("@")[0] : "Signed In")}
              </div>
              <div className="text-[10px] text-slate-500 truncate max-w-[110px]">
                {user?.email || "Authenticated"}
              </div>
            </div>
          </button>

          {userMenuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setUserMenuOpen(false)}
              />
              <div className="absolute right-0 mt-2 w-64 rounded-xl border border-slate-700 bg-slate-900 p-2 shadow-2xl backdrop-blur-xl z-50">
                {/* Header Profile Details */}
                <div className="px-3 py-2 border-b border-slate-800">
                  <p className="text-xs font-semibold text-white">{user?.fullName}</p>
                  <p className="text-[11px] text-slate-400 truncate">{user?.email}</p>
                </div>

                {/* GitHub Identity Card */}
                <div className="px-3 py-2.5 border-b border-slate-800">
                  <div className="flex items-center justify-between text-xs mb-1.5">
                    <span className="font-medium text-slate-300 flex items-center gap-1.5">
                      <GitHubIcon className="h-3.5 w-3.5 text-slate-400" />
                      GitHub Account
                    </span>
                    {isGitHubLinked && (
                      <span className="flex items-center gap-1 text-[10px] text-emerald-400 font-medium">
                        <CheckCircle2 className="h-3 w-3" />
                        Linked
                      </span>
                    )}
                  </div>

                  {isGitHubLinked ? (
                    <div className="space-y-2">
                      <div className="flex items-center justify-between text-[11px] bg-slate-950/60 p-2 rounded-lg border border-slate-800">
                        <div className="flex items-center gap-2 truncate">
                          {githubIdentity?.avatarUrl && (
                            <img
                              src={githubIdentity.avatarUrl}
                              alt={githubIdentity.githubLogin}
                              className="h-5 w-5 rounded-full object-cover shrink-0"
                            />
                          )}
                          <span className="font-mono text-slate-200 truncate">
                            @{githubIdentity?.githubLogin}
                          </span>
                        </div>
                        {githubIdentity?.profileUrl && (
                          <a
                            href={githubIdentity.profileUrl}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="text-slate-400 hover:text-indigo-400 p-1"
                            title="Open GitHub Profile"
                          >
                            <ExternalLink className="h-3 w-3" />
                          </a>
                        )}
                      </div>

                      <button
                        onClick={handleDisconnectGitHub}
                        disabled={disconnecting}
                        className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-slate-700 hover:border-rose-500/40 bg-slate-800/80 hover:bg-rose-950/30 py-1.5 text-[11px] font-medium text-slate-300 hover:text-rose-300 transition-colors disabled:opacity-60 cursor-pointer"
                      >
                        <Unlink className="h-3 w-3 text-slate-400" />
                        {disconnecting ? "Disconnecting..." : "Disconnect GitHub"}
                      </button>
                    </div>
                  ) : (
                    <div className="space-y-2">
                      <p className="text-[11px] text-slate-400">
                        Link your GitHub account to sync repositories and review PRs.
                      </p>
                      <button
                        onClick={handleConnectGitHub}
                        disabled={connecting}
                        className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-indigo-500/40 bg-indigo-950/40 hover:bg-indigo-900/50 py-1.5 text-[11px] font-medium text-indigo-300 transition-colors disabled:opacity-60 cursor-pointer"
                      >
                        <GitHubIcon className="h-3 w-3 text-indigo-400" />
                        {connecting ? "Connecting..." : "Connect GitHub"}
                      </button>
                    </div>
                  )}

                  {actionError && (
                    <div className="mt-2 flex items-start gap-1.5 p-2 rounded-lg bg-rose-950/50 border border-rose-500/30 text-[10px] text-rose-300">
                      <AlertCircle className="h-3.5 w-3.5 text-rose-400 shrink-0 mt-0.5" />
                      <span>{actionError}</span>
                    </div>
                  )}
                </div>

                {/* Sign Out Button */}
                <div className="pt-1">
                  <button
                    onClick={logout}
                    className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-xs text-rose-400 hover:bg-rose-500/10 transition-colors font-medium cursor-pointer"
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
