"use client";

import React, { Suspense, useEffect, useState, useRef } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { apiClient, formatUserError } from "@/lib/api-client";
import { Cpu, AlertCircle, CheckCircle2, ArrowRight, RotateCcw } from "lucide-react";
import Link from "next/link";

function GitHubCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { loginWithGitHub, linkGitHubAccount, isAuthenticated, isLoading } = useAuth();

  const [status, setStatus] = useState<"processing" | "success" | "error">("processing");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [stepMessage, setStepMessage] = useState<string>("Verifying GitHub authorization...");
  const [retryTrigger, setRetryTrigger] = useState(0);
  const hasExecuted = useRef(false);

  const handleRetry = async () => {
    setStatus("processing");
    setErrorMessage(null);
    setStepMessage("Re-initiating GitHub authorization...");
    try {
      const callbackUri = `${window.location.origin}/auth/github/callback`;
      const res = await apiClient.get<{ authorizationUrl: string }>(
        `/api/v1/auth/github/start?redirectUri=${encodeURIComponent(callbackUri)}`,
        { skipAuth: true }
      );
      if (res?.authorizationUrl) {
        if (typeof window !== "undefined") {
          sessionStorage.setItem("forgeai_github_oauth_action", "login");
          localStorage.removeItem("forgeai_access_token");
        }
        window.location.href = res.authorizationUrl;
        return;
      }
    } catch (e) {
      console.error("Retry start failed:", e);
    }
    router.push("/login");
  };

  useEffect(() => {
    // Wait until AuthProvider completes initial session hydration to avoid race condition
    if (isLoading) return;
    if (hasExecuted.current) return;
    hasExecuted.current = true;

    const code = searchParams.get("code");
    const state = searchParams.get("state");
    const error = searchParams.get("error");
    const errorDescription = searchParams.get("error_description");

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

    const hasExistingSession = Boolean(
      isAuthenticated ||
      (typeof window !== "undefined" && localStorage.getItem("forgeai_access_token"))
    );

    const exchange = async () => {
      try {
        setStepMessage("Exchanging GitHub authorization credentials...");
        const callbackUri = `${window.location.origin}/auth/github/callback`;

        const oauthAction = typeof window !== "undefined" ? sessionStorage.getItem("forgeai_github_oauth_action") : null;
        const isExplicitLink = oauthAction === "link";

        let targetRoute = "/";
        const exchangePromise = (async () => {
          if (isExplicitLink && hasExistingSession) {
            // Account linking flow for logged-in user
            setStepMessage("Linking GitHub identity to ForgeAI workspace...");
            await linkGitHubAccount(code, state, callbackUri);
            setStepMessage("GitHub account successfully linked! Redirecting to repositories...");
            targetRoute = "/repositories";
            return;
          }

          // Sign in / Registration flow
          setStepMessage("Establishing secure ForgeAI session & personal workspace...");
          await loginWithGitHub(code, state, callbackUri);
          setStepMessage("Identity verified! Redirecting to workspace...");
          targetRoute = "/";
        })();

        const timeoutPromise = new Promise((_, reject) =>
          setTimeout(
            () =>
              reject(
                new Error(
                  "GitHub OAuth exchange timed out after 90 seconds. Network latency was high. Please click 'Try Again' or return to Sign In."
                )
              ),
            90000
          )
        );

        await Promise.race([exchangePromise, timeoutPromise]);

        setStatus("success");
        router.push(targetRoute);
        setTimeout(() => {
          if (typeof window !== "undefined" && window.location.pathname.includes("/callback")) {
            window.location.href = targetRoute;
          }
        }, 1200);
      } catch (err: any) {
        setStatus("error");
        setErrorMessage(formatUserError(err, "Failed to authenticate with GitHub. Please try again."));
      }
    };

    exchange();
  }, [searchParams, router, loginWithGitHub, linkGitHubAccount, isAuthenticated, isLoading, retryTrigger]);

  return (
    <div className="min-h-screen flex items-center justify-center p-4 bg-[#09090B]">
      <div className="w-full max-w-md rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-6 text-center">
        {/* Brand */}
        <div className="flex flex-col items-center space-y-2 mb-6">
          <div className="flex h-10 w-10 items-center justify-center rounded-md bg-[#141418] border border-[#26262C]">
            <Cpu className="h-5 w-5 text-zinc-200" />
          </div>
          <h1 className="text-lg font-semibold tracking-tight text-zinc-100">
            ForgeAI Identity Integration
          </h1>
          <p className="text-xs text-zinc-400">
            Secure GitHub OAuth & Workspace Authentication
          </p>
        </div>

        {/* Processing State */}
        {status === "processing" && (
          <div className="flex flex-col items-center py-6 space-y-4">
            <div className="relative flex h-9 w-9 items-center justify-center rounded-full border border-[#26262C] bg-[#141418]">
              <svg className="animate-spin h-4 w-4 text-zinc-200" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
              </svg>
            </div>
            <div className="text-xs font-medium text-zinc-200">
              {stepMessage}
            </div>
            <p className="text-xs text-zinc-500 max-w-xs">
              Exchanging cryptographically verified OAuth state and provisioning identity invariants.
            </p>
            <div className="pt-2">
              <Link
                href="/"
                className="text-xs font-mono text-zinc-300 hover:text-zinc-100 underline transition-colors"
              >
                Proceed to Dashboard &rarr;
              </Link>
            </div>
          </div>
        )}

        {/* Success State */}
        {status === "success" && (
          <div className="flex flex-col items-center py-6 space-y-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-full bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
              <CheckCircle2 className="h-5 w-5" />
            </div>
            <div className="text-xs font-medium text-emerald-400">
              {stepMessage}
            </div>
          </div>
        )}

        {/* Error State */}
        {status === "error" && (
          <div className="py-4 space-y-4 text-left">
            <div className="flex items-start gap-2.5 rounded-md border border-rose-500/30 bg-rose-950/30 p-3 text-xs text-rose-300">
              <AlertCircle className="h-4 w-4 text-rose-400 shrink-0 mt-0.5" />
              <div className="space-y-1">
                <div className="font-semibold text-rose-200">Authentication Failed</div>
                <div className="text-rose-300/90 leading-relaxed">{errorMessage}</div>
              </div>
            </div>

            <div className="pt-2 flex flex-col sm:flex-row gap-2">
              <button
                type="button"
                onClick={handleRetry}
                className="flex flex-1 h-8 items-center justify-center gap-1.5 rounded-md bg-zinc-100 hover:bg-white px-3 text-xs font-medium text-zinc-950 transition-colors cursor-pointer"
              >
                <RotateCcw className="h-3.5 w-3.5" />
                Try Again
              </button>
              <Link
                href="/login"
                className="flex flex-1 h-8 items-center justify-center gap-1.5 rounded-md border border-[#26262C] bg-[#141418] hover:bg-[#1C1C22] px-3 text-xs font-medium text-zinc-200 transition-colors"
              >
                Return to Sign In
                <ArrowRight className="h-3.5 w-3.5" />
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
        <div className="min-h-screen flex items-center justify-center bg-[#09090B] text-zinc-500 font-mono text-xs">
          Loading OAuth context...
        </div>
      }
    >
      <GitHubCallbackContent />
    </Suspense>
  );
}
