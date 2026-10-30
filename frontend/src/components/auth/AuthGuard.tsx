"use client";

import React, { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { Loader2 } from "lucide-react";

export const AuthGuard: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { user, isLoading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!isLoading && !user) {
      router.push("/login");
    }
  }, [isLoading, user, router]);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-[#090d16] flex flex-col items-center justify-center text-slate-400 space-y-3 font-mono text-xs">
        <Loader2 className="h-6 w-6 animate-spin text-indigo-400" />
        <p>Authenticating ForgeAI session...</p>
      </div>
    );
  }

  if (!user) {
    return null;
  }

  return <>{children}</>;
};

