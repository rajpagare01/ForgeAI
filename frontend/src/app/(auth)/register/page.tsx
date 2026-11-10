"use client";

import React, { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { ArrowRight, Lock, Mail, User, Building2, AlertCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function RegisterPage() {
  const router = useRouter();
  const { register } = useAuth();
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [orgName, setOrgName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await register(fullName, email, password, orgName);
      router.push("/");
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError("Failed to create account");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-[#09090B]">
      <div className="relative w-full max-w-sm rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-6 shadow-2xl before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col items-center text-center space-y-2 mb-6">
          <div className="flex h-9 w-9 items-center justify-center rounded-md bg-[#141418] border border-[#26262C] text-zinc-100 font-mono text-sm font-semibold tracking-wider">
            F
          </div>
          <h1 className="text-lg font-semibold tracking-tight text-zinc-100">
            Create Enterprise Account
          </h1>
          <p className="text-xs text-zinc-400 max-w-xs">
            Start governing requirements, GitHub pull requests, and CI blast radius.
          </p>
        </div>

        {error && (
          <div className="mb-4 flex items-center gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-2.5 text-xs text-rose-300">
            <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-3">
          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">
              Full Name
            </label>
            <Input
              type="text"
              placeholder="e.g. Jane Doe"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              icon={<User className="h-3.5 w-3.5" />}
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">
              Company / Organization Name
            </label>
            <Input
              type="text"
              placeholder="e.g. Acme Corp"
              value={orgName}
              onChange={(e) => setOrgName(e.target.value)}
              icon={<Building2 className="h-3.5 w-3.5" />}
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">
              Work Email
            </label>
            <Input
              type="email"
              placeholder="name@company.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              icon={<Mail className="h-3.5 w-3.5" />}
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">
              Password
            </label>
            <Input
              type="password"
              placeholder="••••••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              icon={<Lock className="h-3.5 w-3.5" />}
              required
            />
          </div>

          <Button
            type="submit"
            className="w-full h-8 text-xs font-medium bg-zinc-100 hover:bg-zinc-200 text-zinc-900 mt-1"
            disabled={loading}
          >
            {loading ? "Creating..." : "Initialize Workspace"}
            {!loading && <ArrowRight className="ml-1.5 h-3.5 w-3.5" />}
          </Button>
        </form>

        <div className="mt-5 text-center text-xs text-zinc-500">
          Already registered?{" "}
          <Link
            href="/login"
            className="font-medium text-zinc-300 hover:text-white underline"
          >
            Sign in
          </Link>
        </div>
      </div>
    </div>
  );
}
