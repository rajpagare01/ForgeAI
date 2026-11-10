"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import { Layers, Loader2, Plus, ArrowRight } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Button } from "@/components/ui/button";

export default function TraceabilityRedirectPage() {
  const router = useRouter();
  const { activeWorkspace, isLoading: isTenantLoading } = useTenant();
  const [loading, setLoading] = useState(true);
  const [hasNoProjects, setHasNoProjects] = useState(false);

  useEffect(() => {
    let isMounted = true;
    async function resolveProject() {
      if (isTenantLoading) return;
      if (!activeWorkspace?.id) {
        setLoading(false);
        setHasNoProjects(true);
        return;
      }
      try {
        const data = await apiClient.get<any[]>(`/api/v1/projects?workspaceId=${activeWorkspace.id}`);
        if (!isMounted) return;
        if (Array.isArray(data) && data.length > 0) {
          router.replace(`/projects/${data[0].id}/traceability`);
        } else {
          setHasNoProjects(true);
          setLoading(false);
        }
      } catch (err) {
        if (!isMounted) return;
        console.warn("Could not resolve project for traceability spine:", err);
        setHasNoProjects(true);
        setLoading(false);
      }
    }

    resolveProject();
    return () => {
      isMounted = false;
    };
  }, [activeWorkspace?.id, isTenantLoading, router]);

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[60vh] gap-3">
        <Loader2 className="h-6 w-6 animate-spin text-zinc-400" />
        <p className="text-xs font-mono text-zinc-400">Loading Traceability Spine...</p>
      </div>
    );
  }

  if (hasNoProjects) {
    return (
      <div className="p-8 max-w-2xl mx-auto space-y-6">
        <Card className="p-5 border-[#1C1C21] bg-[#0E0E11]">
          <CardHeader className="p-0 pb-4">
            <div className="flex h-10 w-10 items-center justify-center rounded-md bg-[#141418] border border-[#26262C] text-zinc-300 mb-3">
              <Layers className="h-5 w-5" />
            </div>
            <CardTitle className="text-base font-semibold text-zinc-100">No Governed Projects Found</CardTitle>
            <CardDescription className="text-xs text-zinc-400">
              To inspect the Traceability Spine (Requirements → Tasks → PRs → AI Blast Radius), create a project first.
            </CardDescription>
          </CardHeader>
          <CardContent className="p-0">
            <Link href="/projects">
              <Button size="sm" className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
                <Plus className="h-3.5 w-3.5" />
                Go to Projects & Create One
                <ArrowRight className="h-3.5 w-3.5" />
              </Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  return null;
}
