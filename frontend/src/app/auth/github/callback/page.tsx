"use client";

import React, { Suspense, useEffect, useState, useRef } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { Cpu, AlertCircle, CheckCircle2, ArrowRight } from "lucide-react";
import Link from "next/link";

function GitHubCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { loginWithGitHub, linkGitHubAccount, isAuthenticated } = useAuth();

  const [status, setStatus] = useState<"processing" | "success" | "error">("processing");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [stepMessage, setStepMessage] = useState<string>("Verifying GitHub authorization...");
  const hasExecuted = useRef(false);

  useEffect(() => {
    if (hasExecuted.current) return;
    hasExecuted.current = true;

    const code = searchParams.get("code");
    const state = searchParams.get("state");
    const error = searchParams.get("error");
    const errorDescription = searchParams.get("error_description");

    // If an active session token is already present and not in a callback handshake, immediately proceed to workspace
    const existingToken = typeof window !== "undefined" ? localStorage.getItem("forgeai_access_token") : null;
    if (existingToken && !code && !state && !error) {
      setStatus("success");
      setStepMessage("Active session verified! Redirecting to workspace...");
      const redirectTimer = setTimeout(() => {
        window.location.href = "/";
      }, 400);
      return () => clearTimeout(redirectTimer);
    }

    if (error) {
      setStatus("error");
      setErrorMessage(errorDescription || `GitHub returned error: ${error}`);
      return;
    }

    if (!code || !state) {
      setStatus("error");
      setErrorMessage("Missing required OAuth parameters (code or state). Please try again.");
      return;
    }

    const exchange = async () => {
      try {
        setStepMessage("Exchanging GitHub authorization credentials...");
        const callbackUri = `${window.location.origin}/auth/github/callback`;

        if (isAuthenticated) {
          // Account linking flow for logged-in user
          setStepMessage("Linking GitHub identity to ForgeAI workspace...");
          await linkGitHubAccount(code, state, callbackUri);
        } else {
          // Sign in / Registration flow
          setStepMessage("Establishing secure ForgeAI session & personal workspace...");
          await loginWithGitHub(code, state, callbackUri);
        }

        setStatus("success");
        setStepMessage("Identity verified! Redirecting to workspace...");
        router.push("/");
        setTimeout(() => {
          if (typeof window !== "undefined" && window.location.pathname.includes("/callback")) {
            window.location.href = "/";
          }
        }, 1500);
      } catch (err: any) {
        // If access token was saved during exchange or code was already consumed, navigate to workspace
        const savedToken = typeof window !== "undefined" ? localStorage.getItem("forgeai_access_token") : null;
        if (savedToken) {
          setStatus("success");
          setStepMessage("Session verified! Redirecting to workspace...");
          router.push("/");
          setTimeout(() => {
            if (typeof window !== "undefined" && window.location.pathname.includes("/callback")) {
              window.location.href = "/";
            }
          }, 1500);
          return;
        }
        setStatus("error");
        setErrorMessage(err?.message || "Failed to authenticate with GitHub. Please try again.");
      }
    };

    exchange();

    // Fallback safety timeout: if still processing after 4 seconds and token exists, redirect
    const safetyTimeout = setTimeout(() => {
      const savedToken = typeof window !== "undefined" ? localStorage.getItem("forgeai_access_token") : null;
      if (savedToken) {
        window.location.href = "/";
      }
    }, 4000);

    return () => clearTimeout(safetyTimeout);
  }, [searchParams, router, loginWithGitHub, linkGitHubAccount, isAuthenticated]);

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[#090d16] overflow-hidden">
      {/* Background neon blur */}
      <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-96 h-96 bg-indigo-600/20 rounded-full blur-[128px] pointer-events-none" />
      <div className="absolute bottom-1/4 right-1/4 w-80 h-80 bg-purple-600/15 rounded-full blur-[128px] pointer-events-none" />

      <div className="relative w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900/80 p-8 shadow-2xl backdrop-blur-2xl text-center">
        {/* Brand */}
        <div className="flex flex-col items-center space-y-2 mb-6">
          <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-gradient-to-tr from-indigo-600 to-purple-600 shadow-lg shadow-indigo-500/30 border border-indigo-400/30">
            <Cpu className="h-6 w-6 text-white" />
          </div>
          <h1 className="text-xl font-bold tracking-tight text-white">
            ForgeAI Identity Integration
          </h1>
          <p className="text-xs text-slate-400">
            Secure GitHub OAuth & Workspace Authentication
          </p>
        </div>

        {/* Processing State */}
        {status === "processing" && (
          <div className="flex flex-col items-center py-6 space-y-4">
            <div className="relative flex h-10 w-10">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-indigo-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-10 w-10 bg-indigo-600/50 border border-indigo-400/40 items-center justify-center">
                <svg className="animate-spin h-5 w-5 text-indigo-200" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                </svg>
              </span>
            </div>
            <div className="text-sm font-medium text-slate-200 animate-pulse">
              {stepMessage}
            </div>
            <p className="text-xs text-slate-500 max-w-xs">
              Exchanging cryptographically verified OAuth state and provisioning identity invariants.
            </p>
            <div className="pt-2">
              <Link
                href="/"
                className="text-xs text-indigo-400 hover:text-indigo-300 underline transition-colors"
              >
                Proceed to Dashboard &rarr;
              </Link>
            </div>
          </div>
        )}

        {/* Success State */}
        {status === "success" && (
          <div className="flex flex-col items-center py-6 space-y-3">
            <div className="flex h-12 w-12 items-center justify-center rounded-full bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
              <CheckCircle2 className="h-6 w-6" />
            </div>
            <div className="text-sm font-semibold text-emerald-400">
              {stepMessage}
            </div>
          </div>
        )}

        {/* Error State */}
        {status === "error" && (
          <div className="py-4 space-y-4 text-left">
            <div className="flex items-start gap-3 rounded-xl border border-rose-500/30 bg-rose-950/40 p-4 text-xs text-rose-300">
              <AlertCircle className="h-5 w-5 text-rose-400 shrink-0 mt-0.5" />
              <div className="space-y-1">
                <div className="font-semibold text-rose-200">Authentication Failed</div>
                <div className="text-rose-300/90 leading-relaxed">{errorMessage}</div>
              </div>
            </div>

            <div className="pt-2">
              <Link
                href="/login"
                className="flex w-full items-center justify-center gap-2 rounded-xl bg-slate-800 hover:bg-slate-700 px-4 py-2.5 text-xs font-semibold text-white transition-colors"
              >
                Return to Sign In
                <ArrowRight className="h-4 w-4" />
              </Link>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default function GitHubCallbackPage() {
  return (
    <Suspense
      fallback={
        <div className="min-h-screen flex items-center justify-center bg-[#090d16] text-slate-400 text-xs">
          Loading OAuth context...
        </div>
      }
    >
      <GitHubCallbackContent />
    </Suspense>
  );
}
