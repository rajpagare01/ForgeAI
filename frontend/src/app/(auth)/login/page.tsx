"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { apiClient } from "@/lib/api-client";
import { Cpu, ArrowRight, Lock, Mail, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function LoginPage() {
  const router = useRouter();
  const { login } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [githubLoading, setGithubLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await login(email, password);
      router.push("/");
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError("Invalid email or password");
      }
    } finally {
      setLoading(false);
    }
  };

  const handleGitHubLogin = async () => {
    setError(null);
    setGithubLoading(true);
    try {
      const callbackUri = `${window.location.origin}/auth/github/callback`;
      const res = await apiClient.get<{ authorizationUrl: string; state: string }>(
        `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`,
        { skipAuth: true }
      );
      if (res?.authorizationUrl) {
        window.location.href = res.authorizationUrl;
      } else {
        setError("Unable to initialize GitHub authorization session.");
        setGithubLoading(false);
      }
    } catch (err: any) {
      setGithubLoading(false);
      const msg = err?.message || "GitHub authentication is unavailable.";
      if (msg.includes("not configured") || msg.includes("Missing")) {
        setError("GitHub OAuth is not yet configured. Please set GITHUB_CLIENT_ID and GITHUB_CLIENT_SECRET in the environment.");
      } else {
        setError(msg);
      }
    }
  };

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[#090d16] overflow-hidden">
      {/* Background ambient neon glow */}
      <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-96 h-96 bg-indigo-600/20 rounded-full blur-[128px] pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-purple-600/15 rounded-full blur-[128px] pointer-events-none" />

      <div className="relative w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900/80 p-8 shadow-2xl backdrop-blur-2xl">
        {/* Brand */}
        <div className="flex flex-col items-center text-center space-y-2 mb-8">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-tr from-indigo-600 to-purple-600 shadow-lg shadow-indigo-500/30 border border-indigo-400/30">
            <Cpu className="h-6 w-6 text-white" />
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white">
            ForgeAI Platform
          </h1>
          <p className="text-xs text-slate-400 max-w-xs">
            Autonomous Enterprise Engineering Governance, Traceability & AST Risk Matrix
          </p>
        </div>

        {error && (
          <div className="mb-6 flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
            <AlertCircle className="h-4 w-4 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        {/* GitHub User Authentication Button */}
        <div className="space-y-4 mb-4">
          <button
            type="button"
            onClick={handleGitHubLogin}
            disabled={githubLoading || loading}
            className="flex w-full items-center justify-center gap-2.5 rounded-xl border border-slate-700 bg-slate-800/90 hover:bg-slate-750 hover:border-slate-600 px-4 py-2.5 text-xs font-semibold text-white shadow-sm transition-all focus:outline-none focus:ring-2 focus:ring-indigo-500/50 disabled:opacity-60 disabled:cursor-not-allowed group cursor-pointer"
          >
            <svg className="h-4 w-4 fill-current text-white group-hover:scale-105 transition-transform" viewBox="0 0 24 24">
              <path fillRule="evenodd" clipRule="evenodd" d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0112 6.844c.85.004 1.705.115 2.504.337 1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.019 10.019 0 0022 12.017C22 6.484 17.522 2 12 2z" />
            </svg>
            {githubLoading ? "Connecting to GitHub..." : "Continue with GitHub"}
          </button>

          <div className="relative my-4 flex items-center justify-center">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-800" />
            </div>
            <span className="relative bg-slate-900/90 px-3 text-[10px] font-semibold text-slate-500 uppercase tracking-wider">
              Or sign in with email
            </span>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-slate-300">
              Work Email
            </label>
            <Input
              type="email"
              placeholder="name@company.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              icon={<Mail className="h-4 w-4" />}
              required
            />
          </div>

          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs font-medium text-slate-300">
                Password
              </label>
            </div>
            <Input
              type="password"
              placeholder="••••••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              icon={<Lock className="h-4 w-4" />}
              required
            />
          </div>

          <Button
            type="submit"
            variant="glow"
            className="w-full mt-2"
            disabled={loading || githubLoading}
          >
            {loading ? "Authenticating..." : "Sign In to Workspace"}
            {!loading && <ArrowRight className="ml-2 h-4 w-4" />}
          </Button>
        </form>

        <div className="mt-6 text-center text-xs text-slate-500">
          New organization?{" "}
          <Link
            href="/register"
            className="font-medium text-indigo-400 hover:text-indigo-300 hover:underline"
          >
            Create an enterprise account
          </Link>
        </div>
      </div>
    </div>
  );
}
