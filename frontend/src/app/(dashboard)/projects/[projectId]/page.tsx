"use client";

import React, { useState, useEffect, useCallback, Suspense } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Task, TaskStatus, TaskPriority, Project } from "@/types";
import { apiClient } from "@/lib/api-client";
import {
  FolderGit2,
  Layers,
  Plus,
  GitPullRequest,
  CheckCircle2,
  ArrowRight,
  Tag,
  User,
  Sparkles,
  Loader2,
  AlertCircle,
  Radio,
  ArrowLeft,
} from "lucide-react";
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

const COLUMNS: { id: TaskStatus; label: string; color: string }[] = [
  { id: "TODO", label: "To Do", color: "border-slate-700" },
  { id: "IN_PROGRESS", label: "In Progress", color: "border-sky-500/40" },
  { id: "IN_REVIEW", label: "In Review", color: "border-purple-500/40" },
  { id: "DONE", label: "Verified & Done", color: "border-emerald-500/40" },
];

function ProjectDetailContent() {
  const params = useParams();
  const router = useRouter();
  const rawProjectId = params?.projectId as string | undefined;

  const [project, setProject] = useState<Project | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [requirements, setRequirements] = useState<any[]>([]);
  const [repositories, setRepositories] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Create Task modal state
  const [showTaskModal, setShowTaskModal] = useState(false);
  const [taskTitle, setTaskTitle] = useState("");
  const [taskKey, setTaskKey] = useState("");
  const [selectedReqId, setSelectedReqId] = useState("");
  const [priority, setPriority] = useState<TaskPriority>("HIGH");
  const [assignee, setAssignee] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const fetchProjectData = useCallback(async () => {
    if (!rawProjectId) {
      setError("No project ID specified in URL route.");
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);

    try {
      const [projRes, tasksRes, reposRes, reqsRes] = await Promise.allSettled([
        apiClient.get<any>(`/api/v1/projects/${rawProjectId}`),
        apiClient.get<any[]>(`/api/v1/projects/${rawProjectId}/tasks`),
        apiClient.get<any[]>(`/api/v1/projects/${rawProjectId}/repositories`),
        apiClient.get<any[]>(`/api/v1/projects/${rawProjectId}/requirements`),
      ]);

      if (projRes.status === "fulfilled" && projRes.value) {
        const p = projRes.value;
        setProject({
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
        });
      } else {
        throw new Error("Could not find project with ID: " + rawProjectId);
      }

      if (tasksRes.status === "fulfilled" && Array.isArray(tasksRes.value)) {
        setTasks(
          tasksRes.value.map((t) => ({
            id: t.id,
            projectId: t.projectId || rawProjectId,
            taskKey: t.taskKey,
            requirementKey: t.requirementKey,
            title: t.title,
            description: t.description || "",
            status: t.status || "TODO",
            priority: t.priority || "MEDIUM",
            assigneeName: t.assigneeName || (t.assigneeId ? "Assignee" : undefined),
            branchName: t.branchName,
            linkedPrNumber: t.linkedPrNumber,
            updatedAt: t.updatedAt || t.createdAt || "",
          }))
        );
      } else {
        setTasks([]);
      }

      if (reposRes.status === "fulfilled" && Array.isArray(reposRes.value)) {
        setRepositories(reposRes.value);
      } else {
        setRepositories([]);
      }

      if (reqsRes.status === "fulfilled" && Array.isArray(reqsRes.value)) {
        setRequirements(reqsRes.value);
      } else {
        setRequirements([]);
      }
    } catch (err: any) {
      console.warn("Failed to load project details:", err);
      setError(err?.message || "Failed to load project details from Gateway.");
    } finally {
      setIsLoading(false);
    }
  }, [rawProjectId]);

  useEffect(() => {
    fetchProjectData();
  }, [fetchProjectData]);

  const handleCreateTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!taskTitle.trim() || !rawProjectId) return;

    setIsSubmitting(true);
    setError(null);

    try {
      const generatedKey = taskKey.trim() || `${project?.key || "TASK"}-${Date.now().toString().slice(-4)}`;
      const payload: any = {
        taskKey: generatedKey,
        title: taskTitle.trim(),
        description: "Task added to project board.",
        priority,
      };

      if (selectedReqId) {
        payload.requirementId = selectedReqId;
      }

      const created = await apiClient.post<any>(`/api/v1/projects/${rawProjectId}/tasks`, payload);

      const newTask: Task = {
        id: created.id,
        projectId: rawProjectId,
        taskKey: created.taskKey || generatedKey,
        requirementKey: created.requirementKey,
        title: created.title || taskTitle.trim(),
        description: created.description || "",
        status: created.status || "TODO",
        priority: created.priority || priority,
        assigneeName: assignee.trim() || undefined,
        updatedAt: created.updatedAt || new Date().toISOString(),
      };

      setTasks((prev) => [...prev, newTask]);
      setShowTaskModal(false);
      setTaskTitle("");
      setTaskKey("");
      setSelectedReqId("");
      setAssignee("");
    } catch (err: any) {
      console.error("Failed to create task on backend:", err);
      setError(err?.message || "Failed to create task on server.");
    } finally {
      setIsSubmitting(false);
    }
  };

  const moveTask = async (taskId: string, newStatus: TaskStatus) => {
    // Optimistic UI update
    setTasks((prev) =>
      prev.map((t) => (t.id === taskId ? { ...t, status: newStatus } : t))
    );

    try {
      await apiClient.patch(`/api/v1/tasks/${taskId}/status`, { status: newStatus });
    } catch (err: any) {
      console.error("Failed to update task status on backend:", err);
      // Revert / re-fetch on failure
      fetchProjectData();
    }
  };

  if (isLoading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[400px] space-y-3">
        <Loader2 className="h-8 w-8 animate-spin text-sky-400" />
        <p className="text-xs text-slate-400">Loading project details and task board...</p>
      </div>
    );
  }

  if (error && !project) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-3">
          <Button variant="outline" size="sm" onClick={() => router.push("/projects")} className="gap-2">
            <ArrowLeft className="h-4 w-4" />
            Back to Projects
          </Button>
        </div>
        <div className="p-8 rounded-2xl border border-rose-500/30 bg-rose-950/20 text-center space-y-3">
          <AlertCircle className="mx-auto h-8 w-8 text-rose-400" />
          <h2 className="text-base font-bold text-white">Project Not Found</h2>
          <p className="text-xs text-slate-300 max-w-md mx-auto">{error}</p>
        </div>
      </div>
    );
  }

  const linkedRepo = repositories[0]?.repositoryName || project?.repositoryName;

  return (
    <div className="space-y-8">
      {/* Project Banner with Traceability Anchor link */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6 rounded-2xl border border-slate-800 bg-slate-900/60 p-6 backdrop-blur-xl">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Badge variant="default" className="font-mono text-xs">
              PROJECT: {project?.key || "PROJECT"}
            </Badge>
            <span className="text-xs text-slate-400">
              Repository:{" "}
              {linkedRepo ? (
                <span className="font-mono text-slate-200">{linkedRepo}</span>
              ) : (
                <span className="text-slate-500 italic">No repository linked</span>
              )}
            </span>
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white">
            {project?.name || "Project Details"}
          </h1>
          <p className="text-xs text-slate-400 max-w-2xl">
            {project?.description || "Sprint task execution board linked deterministically to engineering requirements."}
          </p>
        </div>

        <div className="flex items-center gap-3 flex-wrap">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowTaskModal(true)}
            className="gap-1.5"
          >
            <Plus className="h-4 w-4" />
            Add Task
          </Button>
          <Link href={`/projects/${rawProjectId}/traceability`}>
            <Button variant="outline" size="sm" className="gap-2 border-slate-700 text-slate-300">
              <Layers className="h-4 w-4 text-purple-400" />
              Traceability Matrix
            </Button>
          </Link>
          <Link href={`/ai-insights`}>
            <Button variant="glow" size="sm" className="gap-2">
              <Sparkles className="h-4 w-4 text-indigo-300" />
              AI Engineering Health
              <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          </Link>
        </div>
      </div>

      {/* Global error banner if action failed */}
      {error && (
        <div className="flex items-center gap-2 rounded-xl border border-rose-500/30 bg-rose-950/40 p-3 text-xs text-rose-300">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {/* Kanban Board Columns */}
      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
        {COLUMNS.map((col) => {
          const colTasks = tasks.filter((t) => t.status === col.id);

          return (
            <div
              key={col.id}
              className={`flex flex-col rounded-2xl border ${col.color} bg-slate-950/60 p-4 min-h-[500px] backdrop-blur-md`}
            >
              {/* Column Header */}
              <div className="flex items-center justify-between pb-3 border-b border-slate-800/80 mb-4">
                <div className="flex items-center gap-2">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-slate-300">
                    {col.label}
                  </h3>
                  <span className="flex h-5 w-5 items-center justify-center rounded-full bg-slate-800 text-[11px] font-bold text-slate-400">
                    {colTasks.length}
                  </span>
                </div>
              </div>

              {/* Task Cards */}
              <div className="flex-1 space-y-3">
                {colTasks.map((task) => (
                  <div
                    key={task.id}
                    className="rounded-xl border border-slate-800 bg-slate-900/80 p-3.5 shadow-sm hover:border-indigo-500/50 hover:bg-slate-900 transition-all space-y-2.5"
                  >
                    {/* Header: Key & Priority */}
                    <div className="flex items-center justify-between">
                      <span className="font-mono text-xs font-bold text-indigo-400">
                        {task.taskKey}
                      </span>
                      <Badge
                        variant={
                          task.priority === "CRITICAL"
                            ? "destructive"
                            : task.priority === "HIGH"
                            ? "warning"
                            : "secondary"
                        }
                        className="text-[10px] px-1.5 py-0"
                      >
                        {task.priority}
                      </Badge>
                    </div>

                    {/* Title */}
                    <p className="text-xs font-medium text-slate-200 leading-snug">
                      {task.title}
                    </p>

                    {/* Requirement Pill */}
                    {task.requirementKey && (
                      <div className="flex items-center gap-1.5 text-[11px] text-slate-400 font-mono">
                        <Tag className="h-3 w-3 text-purple-400" />
                        <span>Bound:</span>
                        <span className="text-purple-300 font-bold">{task.requirementKey}</span>
                      </div>
                    )}

                    {/* PR & Assignee */}
                    <div className="flex items-center justify-between pt-2 border-t border-slate-800/80 text-[11px] text-slate-400">
                      {task.linkedPrNumber ? (
                        <Link
                          href={`/ai-insights?pr=${task.linkedPrNumber}`}
                          className="flex items-center gap-1 font-mono text-sky-400 hover:text-sky-300 hover:underline transition-colors"
                          title="Open in AI Engineering Health"
                        >
                          <GitPullRequest className="h-3 w-3" />
                          #{task.linkedPrNumber}
                          <ArrowRight className="h-2.5 w-2.5 opacity-60" />
                        </Link>
                      ) : (
                        <span className="text-slate-600">No PR yet</span>
                      )}

                      <span className="flex items-center gap-1 text-slate-300">
                        <User className="h-3 w-3 text-slate-500" />
                        {task.assigneeName || "Unassigned"}
                      </span>
                    </div>

                    {/* Quick Move Trigger */}
                    <div className="flex items-center justify-end gap-1 pt-1">
                      {col.id !== "TODO" && (
                        <button
                          type="button"
                          onClick={() => moveTask(task.id, "TODO")}
                          className="text-[10px] text-slate-500 hover:text-slate-300 px-1 py-0.5 rounded"
                          title="Move to Todo"
                        >
                          ← Todo
                        </button>
                      )}
                      {col.id !== "IN_PROGRESS" && (
                        <button
                          type="button"
                          onClick={() => moveTask(task.id, "IN_PROGRESS")}
                          className="text-[10px] text-slate-500 hover:text-slate-300 px-1 py-0.5 rounded"
                          title="Move to In Progress"
                        >
                          {col.id === "TODO" ? "Progress →" : "← Progress"}
                        </button>
                      )}
                      {col.id !== "DONE" && (
                        <button
                          type="button"
                          onClick={() => moveTask(task.id, "DONE")}
                          className="text-[10px] text-emerald-400 hover:text-emerald-300 px-1 py-0.5 rounded"
                          title="Mark Done"
                        >
                          Done ✓
                        </button>
                      )}
                    </div>
                  </div>
                ))}

                {colTasks.length === 0 && (
                  <div className="flex h-32 items-center justify-center rounded-xl border border-dashed border-slate-800 text-xs text-slate-600">
                    No tasks in this stage
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Create Task Modal */}
      <Dialog open={showTaskModal} onOpenChange={setShowTaskModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Add Engineering Task</DialogTitle>
              <DialogDescription>
                Assign work linked directly to a verified specification requirement.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowTaskModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleCreateTask} className="space-y-4 mt-4">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Task Key</label>
              <Input
                placeholder="e.g. TASK-101"
                value={taskKey}
                onChange={(e) => setTaskKey(e.target.value.toUpperCase())}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Bound Requirement</label>
              {requirements.length > 0 ? (
                <select
                  value={selectedReqId}
                  onChange={(e) => setSelectedReqId(e.target.value)}
                  className="h-10 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 text-xs text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
                >
                  <option value="">None (Unbound Task)</option>
                  {requirements.map((req) => (
                    <option key={req.id} value={req.id}>
                      {req.reqKey}: {req.title}
                    </option>
                  ))}
                </select>
              ) : (
                <div className="flex h-10 w-full items-center rounded-lg border border-slate-800 bg-slate-900 px-3 text-xs text-slate-500 italic">
                  No requirements created yet (leaving unbound)
                </div>
              )}
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Task Summary</label>
            <Input
              placeholder="e.g. Implement OAuth2 token revocation filter"
              value={taskTitle}
              onChange={(e) => setTaskTitle(e.target.value)}
              required
            />
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Priority</label>
              <select
                value={priority}
                onChange={(e) => setPriority(e.target.value as TaskPriority)}
                className="h-10 w-full rounded-lg border border-slate-800 bg-slate-900 px-3 text-xs text-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
              >
                <option value="LOW">LOW</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="HIGH">HIGH</option>
                <option value="CRITICAL">CRITICAL</option>
              </select>
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Assignee</label>
              <Input
                placeholder="e.g. Lead Engineer"
                value={assignee}
                onChange={(e) => setAssignee(e.target.value)}
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowTaskModal(false)}
            >
              Cancel
            </Button>
            <Button type="submit" variant="glow" size="sm" disabled={!taskTitle || isSubmitting}>
              {isSubmitting ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin mr-1.5" />
                  Creating...
                </>
              ) : (
                "Create Task"
              )}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>
    </div>
  );
}

export default function ProjectDetailPage() {
  return (
    <Suspense fallback={<div className="p-8 text-center text-slate-500 text-xs">Loading project details...</div>}>
      <ProjectDetailContent />
    </Suspense>
  );
}
