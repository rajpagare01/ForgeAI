"use client";

import React, { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { Project } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import {
  FolderGit2,
  Plus,
  ArrowRight,
  GitPullRequest,
  CheckCircle2,
  Layers,
  Sparkles,
  ExternalLink,
  Loader2,
  AlertCircle,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogClose,
  DialogFooter,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

export default function ProjectsPage() {
  const { currentOrg, activeWorkspace, isPersonalWorkspace, isLoading: isTenantLoading } = useTenant();
  const [projects, setProjects] = useState<Project[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [description, setDescription] = useState("");
  const [repoName, setRepoName] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const fetchProjects = useCallback(async () => {
    if (isTenantLoading) return;
    if (!activeWorkspace?.id) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    setError(null);
    try {
      const data = await apiClient.get<any[]>(`/api/v1/projects?workspaceId=${activeWorkspace.id}`);
      if (Array.isArray(data)) {
        const mapped: Project[] = data.map((p) => ({
          id: p.id,
          organizationId: p.organizationId,
          workspaceId: p.workspaceId,
          key: p.key,
          name: p.name,
          description: p.description || "",
          repositoryName: p.repositoryName ?? null,
          repositoryUrl: p.repositoryUrl ?? null,
          requirementCount: p.requirementCount ?? 0,
          taskCount: p.taskCount ?? 0,
          healthScore: p.healthScore ?? null,
          updatedAt: p.updatedAt || p.createdAt || "",
        }));
        setProjects(mapped);
      } else {
        setProjects([]);
      }
    } catch (err: any) {
      console.warn("Failed to load projects from Gateway:", err);
      setError("Could not load projects for the active workspace.");
    } finally {
      setIsLoading(false);
    }
  }, [activeWorkspace?.id, isTenantLoading]);

  useEffect(() => {
    fetchProjects();
  }, [fetchProjects]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name || !key || !activeWorkspace?.id) return;

    setIsSubmitting(true);
    setError(null);
    try {
      const cleanKey = key.trim().toUpperCase();
      const payload: any = {
        name: name.trim(),
        key: cleanKey,
        description: description.trim() || undefined,
        workspaceId: activeWorkspace.id,
      };

      if (!isPersonalWorkspace && currentOrg?.id) {
        payload.organizationId = currentOrg.id;
      }

      const res = await apiClient.post<any>("/api/v1/projects", payload);

      const newProj: Project = {
        id: res.id,
        organizationId: res.organizationId,
        workspaceId: res.workspaceId || activeWorkspace.id,
        key: res.key,
        name: res.name,
        description: res.description || description.trim() || "",
        repositoryName: repoName.trim() || null,
        repositoryUrl: null,
        requirementCount: 0,
        taskCount: 0,
        healthScore: null,
        updatedAt: res.createdAt || new Date().toISOString(),
      };

      setProjects((prev) => [newProj, ...prev]);
      setShowCreateModal(false);
      setName("");
      setKey("");
      setDescription("");
      setRepoName("");
    } catch (err: any) {
      console.error("Failed to create project:", err);
      setError(err?.message || "Failed to create project on Gateway.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const workspaceDisplayName = isPersonalWorkspace
    ? "Personal Workspace"
    : currentOrg?.name || "Organization Workspace";

  return (
    <div className="space-y-8">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <FolderGit2 className="h-6 w-6 text-indigo-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              Governed Projects
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Manage projects, assign sprint requirements, and audit end-to-end code delivery spines for{" "}
            <span className="text-indigo-300 font-medium">{workspaceDisplayName}</span>.
          </p>
        </div>

        <Button
          variant="glow"
          onClick={() => setShowCreateModal(true)}
          className="gap-2"
        >
          <Plus className="h-4 w-4" />
          Create Project
        </Button>
      </div>

      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Loading state */}
      {isLoading && (
        <div className="flex flex-col items-center justify-center p-16 text-slate-400">
          <Loader2 className="h-8 w-8 animate-spin text-indigo-400 mb-2" />
          <p className="text-xs">Fetching governed projects from Gateway...</p>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && projects.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-2xl border border-dashed border-slate-800 p-16 text-center">
          <FolderGit2 className="h-12 w-12 text-slate-600 mb-4" />
          <h3 className="text-sm font-semibold text-white">No projects found</h3>
          <p className="text-xs text-slate-400 max-w-sm mt-1 mb-6">
            Get started by creating your first governed project to link requirements, tasks, and pull requests.
          </p>
          <Button variant="glow" onClick={() => setShowCreateModal(true)} className="gap-2">
            <Plus className="h-4 w-4" />
            Create Project
          </Button>
        </div>
      )}

      {/* Projects Grid */}
      {!isLoading && projects.length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {projects.map((project) => (
            <Card
              key={project.id}
              className="flex flex-col justify-between hover:border-indigo-500/50 transition-all group"
            >
              <CardHeader className="space-y-3">
                <div className="flex items-center justify-between">
                  <Badge variant="default" className="font-mono font-bold text-xs">
                    {project.key}
                  </Badge>
                  {project.healthScore !== null && project.healthScore !== undefined ? (
                    <div className="flex items-center gap-1.5 text-xs font-semibold text-emerald-400">
                      <div className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
                      {project.healthScore}% Traceability
                    </div>
                  ) : (
                    <span className="text-xs text-slate-500 font-medium">
                      Traceability unavailable
                    </span>
                  )}
                </div>
                <div>
                  <CardTitle className="text-base group-hover:text-indigo-300 transition-colors">
                    {project.name}
                  </CardTitle>
                  <CardDescription className="line-clamp-2 mt-1">
                    {project.description || "No project description provided."}
                  </CardDescription>
                </div>
              </CardHeader>

              <CardContent className="space-y-4 pt-2">
                <div className="flex items-center justify-between text-xs text-slate-400 py-2 border-y border-slate-800/80">
                  <div className="flex items-center gap-1">
                    <span className="font-bold text-white">{project.requirementCount}</span>
                    <span>Requirements</span>
                  </div>
                  <div className="h-3 w-px bg-slate-800" />
                  <div className="flex items-center gap-1">
                    <span className="font-bold text-white">{project.taskCount}</span>
                    <span>Tasks</span>
                  </div>
                </div>

                <div className="flex items-center justify-between text-[11px] text-slate-500 font-mono">
                  <span className="truncate max-w-[180px]">
                    {project.repositoryName || "No repository linked"}
                  </span>
                  {project.repositoryName ? (
                    <span className="text-indigo-400 flex items-center gap-0.5">
                      Connected
                    </span>
                  ) : (
                    <span className="text-slate-600">Unlinked</span>
                  )}
                </div>

                <div className="pt-2 flex items-center gap-2">
                  <Link
                    href={`/projects/${project.id}`}
                    className="flex-1"
                  >
                    <Button variant="secondary" size="sm" className="w-full text-xs">
                      Task Board
                    </Button>
                  </Link>
                  <Link
                    href={`/projects/${project.id}/traceability`}
                    className="flex-1"
                  >
                    <Button variant="glow" size="sm" className="w-full text-xs gap-1">
                      <Layers className="h-3.5 w-3.5" />
                      Trace Spine
                    </Button>
                  </Link>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}

      {/* Create Project Modal */}
      <Dialog open={showCreateModal} onOpenChange={setShowCreateModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Create Governed Project</DialogTitle>
              <DialogDescription>
                Define project key, repository association, and automated governance boundaries.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowCreateModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleCreate} className="space-y-4 mt-4">
          <div className="rounded-lg border border-slate-800 bg-slate-900/60 p-2.5 flex items-center justify-between text-xs">
            <div className="flex items-center gap-2 text-slate-400">
              <span className="text-slate-500 font-mono uppercase text-[10px]">Target Workspace:</span>
              <span className="font-semibold text-white">{workspaceDisplayName}</span>
            </div>
            <Badge
              variant="outline"
              className={
                isPersonalWorkspace
                  ? "text-violet-400 border-violet-500/30 font-mono text-[10px]"
                  : "text-indigo-400 border-indigo-500/30 font-mono text-[10px]"
              }
            >
              {isPersonalWorkspace ? "PERSONAL" : "ORGANIZATION"}
            </Badge>
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-1 space-y-1">
              <label className="text-xs font-medium text-slate-300">Project Key</label>
              <Input
                placeholder="e.g. CORE"
                value={key}
                onChange={(e) => setKey(e.target.value.toUpperCase())}
                pattern="^[A-Z0-9]{2,16}$"
                title="2-16 uppercase alphanumeric characters"
                required
              />
            </div>
            <div className="col-span-2 space-y-1">
              <label className="text-xs font-medium text-slate-300">Project Name</label>
              <Input
                placeholder="e.g. Identity & Security Core"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Description</label>
            <textarea
              placeholder="High-level architecture and scope description..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="h-20 w-full rounded-lg border border-slate-800 bg-slate-950 p-3 text-xs text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">
              Associated Repository
            </label>
            <Input
              placeholder="e.g. forgeai/governance-core"
              value={repoName}
              onChange={(e) => setRepoName(e.target.value)}
            />
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowCreateModal(false)}
            >
              Cancel
            </Button>
            <Button type="submit" variant="glow" size="sm" disabled={!name || !key || isSubmitting}>
              {isSubmitting ? "Creating..." : "Create Project"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}
