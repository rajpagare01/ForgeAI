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
      <div className="flex flex-col items-center justify-center min-h-[60vh] gap-4">
        <Loader2 className="h-8 w-8 animate-spin text-indigo-500" />
        <p className="text-sm text-slate-400">Loading Traceability Spine...</p>
      </div>
    );
  }

  if (hasNoProjects) {
    return (
      <div className="p-8 max-w-2xl mx-auto space-y-6">
        <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-xl">
          <CardHeader>
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 mb-2">
              <Layers className="h-6 w-6" />
            </div>
            <CardTitle className="text-xl">No Governance Projects Found</CardTitle>
            <CardDescription>
              To view the Traceability Spine (Requirements → Tasks → PRs → AI Blast Radius), create a project first.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <Link href="/projects">
              <Button variant="glow" className="gap-2">
                <Plus className="h-4 w-4" />
                Go to Projects & Create One
                <ArrowRight className="h-4 w-4" />
              </Button>
            </Link>
          </CardContent>
        </Card>
      </div>
    );
  }

  return null;
}
