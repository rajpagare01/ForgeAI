"use client";

import React, { useState, useEffect, useCallback, Suspense } from "react";
import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import { Project, Task, TaskStatus, TaskPriority } from "@/types";
import { useTenant } from "@/lib/tenant-context";
import { apiClient, formatUserError } from "@/lib/api-client";
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
  Clock,
  Check,
  Tag,
  GitCommit,
  GitBranch,
  RefreshCw,
  ArrowLeft,
  ChevronRight,
  ShieldCheck,
  Link2,
  FileCode,
  Copy,
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
import { formatDate } from "@/lib/utils";

const KANBAN_COLUMNS: { id: TaskStatus; label: string; color: string; badgeColor: string }[] = [
  { id: "TODO", label: "To Do", color: "border-slate-800 bg-slate-900/30", badgeColor: "bg-slate-800 text-slate-300" },
  { id: "IN_PROGRESS", label: "In Progress", color: "border-sky-500/30 bg-sky-950/10", badgeColor: "bg-sky-950 text-sky-400 border border-sky-500/30" },
  { id: "IN_REVIEW", label: "In Review", color: "border-purple-500/30 bg-purple-950/10", badgeColor: "bg-purple-950 text-purple-400 border border-purple-500/30" },
  { id: "DONE", label: "Verified & Done", color: "border-emerald-500/30 bg-emerald-950/10", badgeColor: "bg-emerald-950 text-emerald-400 border border-emerald-500/30" },
  { id: "BLOCKED", label: "Blocked", color: "border-rose-500/30 bg-rose-950/10", badgeColor: "bg-rose-950 text-rose-400 border border-rose-500/30" },
];

interface GeneratedBacklogDraft {
  project_key: string;
  requirements: Array<{ identifier: string; title: string; description: string }>;
  tasks: Array<{
    task_key: string;
    requirement_identifier: string;
    title: string;
    description: string;
    priority: string;
    target_files?: string[];
  }>;
  used_fallback: boolean;
}

interface CodeLinkItem {
  id: string;
  linkType: string;
  externalRef: string;
  repositoryId?: string;
  createdBy?: string;
  createdAt: string;
}

function ProjectsPageContent() {
  const { currentOrg, activeWorkspace, isPersonalWorkspace, isLoading: isTenantLoading } = useTenant();
  const searchParams = useSearchParams();
  const router = useRouter();
  const rawSearchQuery = searchParams.get("q") || "";
  const searchQuery = rawSearchQuery.trim().toLowerCase();
  const [projects, setProjects] = useState<Project[]>([]);
  const [availableRepositories, setAvailableRepositories] = useState<any[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successToast, setSuccessToast] = useState<string | null>(null);

  // Active Workspace / Project View State
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null);
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [requirements, setRequirements] = useState<any[]>([]);
  const [isLoadingTasks, setIsLoadingTasks] = useState(false);

  // Create Project Modal State
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [name, setName] = useState("");
  const [key, setKey] = useState("");
  const [description, setDescription] = useState("");
  const [selectedRepoId, setSelectedRepoId] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);

  // AI Sprint Planner Modal State
  const [showAiBacklogModal, setShowAiBacklogModal] = useState(false);
  const [backlogPrompt, setBacklogPrompt] = useState("");
  const [isGeneratingBacklog, setIsGeneratingBacklog] = useState(false);
  const [generatedBacklog, setGeneratedBacklog] = useState<GeneratedBacklogDraft | null>(null);
  const [isImportingBacklog, setIsImportingBacklog] = useState(false);

  // Add Requirement Modal State
  const [showAddReqModal, setShowAddReqModal] = useState(false);
  const [reqIdentifier, setReqIdentifier] = useState("");
  const [reqTitle, setReqTitle] = useState("");
  const [reqDescription, setReqDescription] = useState("");
  const [isSubmittingReq, setIsSubmittingReq] = useState(false);

  // Add Task Modal State
  const [showAddTaskModal, setShowAddTaskModal] = useState(false);
  const [taskKey, setTaskKey] = useState("");
  const [taskTitle, setTaskTitle] = useState("");
  const [taskDesc, setTaskDesc] = useState("");
  const [taskReqId, setTaskReqId] = useState("");
  const [taskPriority, setTaskPriority] = useState<TaskPriority>("HIGH");
  const [isSubmittingTask, setIsSubmittingTask] = useState(false);

  // Link Code Dialog State
  const [linkTask, setLinkTask] = useState<Task | null>(null);
  const [taskLinks, setTaskLinks] = useState<CodeLinkItem[]>([]);
  const [isLoadingLinks, setIsLoadingLinks] = useState(false);
  const [linkType, setLinkType] = useState<"COMMIT" | "BRANCH" | "PULL_REQUEST">("COMMIT");
  const [linkExternalRef, setLinkExternalRef] = useState("");
  const [isSubmittingLink, setIsSubmittingLink] = useState(false);

  // Sync Git Activity State
  const [isSyncingGit, setIsSyncingGit] = useState(false);

  const showNotification = (msg: string) => {
    setSuccessToast(msg);
    setTimeout(() => setSuccessToast(null), 5000);
  };

  // Fetch available repositories
  const fetchRepositories = useCallback(async () => {
    if (isTenantLoading || !activeWorkspace?.id) return;
    try {
      const data = await apiClient.get<any[]>(`/api/v1/repositories?workspaceId=${activeWorkspace.id}`);
      if (Array.isArray(data)) {
        setAvailableRepositories(data);
        if (data.length > 0 && !selectedRepoId) {
          setSelectedRepoId(data[0].id);
        }
      }
    } catch (e) {
      console.warn("Failed to load repositories:", e);
    }
  }, [activeWorkspace?.id, isTenantLoading, selectedRepoId]);

  // Fetch all projects
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
    fetchRepositories();
    fetchProjects();
  }, [fetchRepositories, fetchProjects]);

  // Fetch tasks and requirements for selected project
  const fetchProjectDetails = useCallback(async (projId: string) => {
    setIsLoadingTasks(true);
    try {
      const [projData, tasksData, reqsData] = await Promise.allSettled([
        apiClient.get<any>(`/api/v1/projects/${projId}`),
        apiClient.get<any[]>(`/api/v1/projects/${projId}/tasks`),
        apiClient.get<any[]>(`/api/v1/projects/${projId}/requirements`),
      ]);

      if (projData.status === "fulfilled" && projData.value) {
        const p = projData.value;
        setSelectedProject({
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
      }

      if (tasksData.status === "fulfilled" && Array.isArray(tasksData.value)) {
        setTasks(tasksData.value);
      } else {
        setTasks([]);
      }

      if (reqsData.status === "fulfilled" && Array.isArray(reqsData.value)) {
        setRequirements(reqsData.value);
        if (reqsData.value.length > 0 && !taskReqId) {
          setTaskReqId(reqsData.value[0].id);
        }
      } else {
        setRequirements([]);
      }
    } catch (err) {
      console.error("Failed to load project details:", err);
    } finally {
      setIsLoadingTasks(false);
    }
  }, [taskReqId]);

  useEffect(() => {
    if (selectedProjectId) {
      fetchProjectDetails(selectedProjectId);
    }
  }, [selectedProjectId, fetchProjectDetails]);

  // Open Workspace for a project
  const openProjectWorkspace = (proj: Project) => {
    setSelectedProjectId(proj.id);
    setSelectedProject(proj);
    setTaskKey(`${proj.key}-${(proj.taskCount || 0) + 101}`);
    setReqIdentifier(`REQ-00${(proj.requirementCount || 0) + 1}`);
  };

  // Close Workspace back to list
  const closeProjectWorkspace = () => {
    setSelectedProjectId(null);
    setSelectedProject(null);
    fetchProjects();
  };

  // Handle Project Creation with Repository Dropdown
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

      // Link repository if selected
      let linkedRepoName: string | null = null;
      if (selectedRepoId) {
        const repo = availableRepositories.find((r) => r.id === selectedRepoId);
        if (repo) {
          linkedRepoName = repo.fullName || repo.name;
          try {
            await apiClient.linkProjectRepository(res.id, repo.id, linkedRepoName || undefined);
          } catch (linkErr) {
            console.warn("Could not link repository on project creation:", linkErr);
          }
        }
      }

      showNotification(`✓ Governed project '${res.name}' (${res.key}) created successfully!`);
      setShowCreateModal(false);
      setName("");
      setKey("");
      setDescription("");
      await fetchProjects();
    } catch (err: any) {
      console.error("Failed to create project:", err);
      setError(err?.message || "Failed to create project on Gateway.");
    } finally {
      setIsSubmitting(false);
    }
  };

  // Task Status Transition Handler
  const handleTransitionTask = async (task: Task, targetStatus: TaskStatus) => {
    try {
      await apiClient.patch(`/api/v1/tasks/${task.id}/status`, {
        status: targetStatus,
      });
      // Refresh local tasks & project dynamic health score
      if (selectedProjectId) {
        await fetchProjectDetails(selectedProjectId);
      }
      showNotification(`✓ Task ${task.taskKey} transitioned to ${targetStatus}`);
    } catch (err: any) {
      console.error("Failed to transition task status:", err);
      setError(err?.message || "Invalid status transition.");
    }
  };

  // Open Link Code Dialog
  const openLinkDialog = async (task: Task) => {
    setLinkTask(task);
    setIsLoadingLinks(true);
    setLinkExternalRef("");
    try {
      const links = await apiClient.get<CodeLinkItem[]>(`/api/v1/tasks/${task.id}/links`);
      setTaskLinks(Array.isArray(links) ? links : []);
    } catch (e) {
      setTaskLinks([]);
    } finally {
      setIsLoadingLinks(false);
    }
  };

  // Submit Code Link
  const handleAddCodeLink = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!linkTask || !linkExternalRef.trim()) return;

    setIsSubmittingLink(true);
    try {
      const linkedRepo = availableRepositories.find(
        (r) => r.fullName === selectedProject?.repositoryName || r.name === selectedProject?.repositoryName
      );
      const repoId = linkedRepo?.id || availableRepositories[0]?.id;
      if (!repoId) {
        throw new Error("No repository connected to workspace. Please connect a repository in Source Control first.");
      }
      await apiClient.post(`/api/v1/tasks/${linkTask.id}/links`, {
        linkType,
        externalRef: linkExternalRef.trim(),
        repositoryId: repoId,
        createdBy: "MANUAL",
      });
      showNotification(`✓ Linked ${linkType} to ${linkTask.taskKey}`);
      // Refresh links
      const links = await apiClient.get<CodeLinkItem[]>(`/api/v1/tasks/${linkTask.id}/links`);
      setTaskLinks(Array.isArray(links) ? links : []);
      setLinkExternalRef("");
    } catch (err: any) {
      console.error("Failed to link code artifact:", err);
      setError(formatUserError(err, "Failed to link code artifact."));
    } finally {
      setIsSubmittingLink(false);
    }
  };

  // Sync Git Activity to Tasks
  const handleSyncGitActivity = async () => {
    if (!selectedProjectId) return;
    setIsSyncingGit(true);
    try {
      let activities: any[] = [];
      try {
        const linkedRepo = availableRepositories.find(
          (r) => r.fullName === selectedProject?.repositoryName || r.name === selectedProject?.repositoryName
        );
        const repoId = linkedRepo?.id || availableRepositories[0]?.id;
        if (repoId) {
          const commits = await apiClient.get<any[]>(`/api/v1/repositories/${repoId}/commits?limit=15`);
          if (Array.isArray(commits)) {
            activities = commits.map((c) => ({
              sha: c.sha,
              message: c.message,
              branch: "main",
              repositoryId: repoId,
            }));
          }
        }
      } catch (cErr) {
        console.warn("Could not load repo commits for sync:", cErr);
      }

      const res = await apiClient.post<any>(`/api/v1/projects/${selectedProjectId}/sync-git-activity`, {
        activities,
      });
      showNotification(`✓ Git Sync: linked ${res?.linkedCount || 0} artifacts, updated ${res?.statusProgressedCount || 0} tasks!`);
      await fetchProjectDetails(selectedProjectId);
    } catch (err: any) {
      console.error("Failed to sync git activity:", err);
      setError(formatUserError(err, "Unable to sync Git activity right now. Please check that a repository is linked and try again."));
    } finally {
      setIsSyncingGit(false);
    }
  };

  // Add Requirement Submit
  const handleAddRequirement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProjectId || !reqIdentifier || !reqTitle) return;

    setIsSubmittingReq(true);
    try {
      await apiClient.post(`/api/v1/projects/${selectedProjectId}/requirements`, {
        identifier: reqIdentifier.trim().toUpperCase(),
        title: reqTitle.trim(),
        description: reqDescription.trim() || reqTitle.trim(),
      });
      setShowAddReqModal(false);
      setReqTitle("");
      setReqDescription("");
      showNotification(`✓ Requirement ${reqIdentifier} created!`);
      await fetchProjectDetails(selectedProjectId);
    } catch (err: any) {
      console.error("Failed to create requirement:", err);
      setError("Failed to create requirement.");
    } finally {
      setIsSubmittingReq(false);
    }
  };

  // Add Task Submit
  const handleAddTask = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProjectId || !taskKey || !taskTitle) return;

    setIsSubmittingTask(true);
    try {
      await apiClient.post(`/api/v1/projects/${selectedProjectId}/tasks`, {
        taskKey: taskKey.trim().toUpperCase(),
        title: taskTitle.trim(),
        description: taskDesc.trim() || undefined,
        requirementId: taskReqId || undefined,
        priority: taskPriority,
      });
      setShowAddTaskModal(false);
      setTaskTitle("");
      setTaskDesc("");
      showNotification(`✓ Task ${taskKey} created!`);
      await fetchProjectDetails(selectedProjectId);
    } catch (err: any) {
      console.error("Failed to create task:", err);
      setError("Failed to create task.");
    } finally {
      setIsSubmittingTask(false);
    }
  };

  // AI Sprint Planner: Generate Backlog
  const handleGenerateBacklog = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedProject || !backlogPrompt.trim()) return;

    setIsGeneratingBacklog(true);
    setGeneratedBacklog(null);
    try {
      const repoId = availableRepositories[0]?.id;
      const res = await apiClient.post<GeneratedBacklogDraft>("/api/v1/ai/projects/generate-backlog", {
        project_name: selectedProject.name,
        project_key: selectedProject.key,
        prompt: backlogPrompt.trim(),
        repository_id: repoId,
      });
      setGeneratedBacklog(res);
      showNotification("✓ AI generated sprint backlog with requirements & mapped tasks!");
    } catch (err: any) {
      console.error("Failed to generate backlog:", err);
      setError("AI Sprint Planner fell back to deterministic template.");
    } finally {
      setIsGeneratingBacklog(false);
    }
  };

  const handleImportBacklog = async () => {
    if (!selectedProjectId || !generatedBacklog) return;

    setIsImportingBacklog(true);
    try {
      const reqIdMap: Record<string, string> = {};
      const existingReqIdentifiers = new Set(
        requirements.map((er) => er.identifier.toUpperCase())
      );
      requirements.forEach((er) => {
        reqIdMap[er.identifier.toUpperCase()] = er.id;
      });

      let nextReqNum = requirements.length + 1;
      for (const r of generatedBacklog.requirements) {
        let targetId = r.identifier.trim().toUpperCase();
        if (existingReqIdentifiers.has(targetId)) {
          while (existingReqIdentifiers.has(`REQ-${String(nextReqNum).padStart(3, "0")}`)) {
            nextReqNum++;
          }
          targetId = `REQ-${String(nextReqNum).padStart(3, "0")}`;
        }
        try {
          const createdReq = await apiClient.post<any>(`/api/v1/projects/${selectedProjectId}/requirements`, {
            identifier: targetId,
            title: r.title,
            description: r.description,
          });
          if (createdReq?.id) {
            reqIdMap[r.identifier.trim().toUpperCase()] = createdReq.id;
            reqIdMap[targetId] = createdReq.id;
            existingReqIdentifiers.add(targetId);
          }
        } catch (e) {
          console.warn("Requirement creation skipped:", targetId, e);
        }
      }

      const existingTaskKeys = new Set(
        tasks.map((et) => et.taskKey.toUpperCase())
      );
      const projPrefix = (selectedProject?.key || generatedBacklog.project_key || "TASK").toUpperCase();
      let nextTaskNum = 101 + tasks.length;

      for (const t of generatedBacklog.tasks) {
        let finalTaskKey = t.task_key.trim().toUpperCase();
        if (existingTaskKeys.has(finalTaskKey)) {
          while (existingTaskKeys.has(`${projPrefix}-${nextTaskNum}`)) {
            nextTaskNum++;
          }
          finalTaskKey = `${projPrefix}-${nextTaskNum}`;
        }
        try {
          const matchedReqId =
            reqIdMap[t.requirement_identifier.trim().toUpperCase()] ||
            Object.values(reqIdMap)[0] ||
            undefined;
          await apiClient.post(`/api/v1/projects/${selectedProjectId}/tasks`, {
            taskKey: finalTaskKey,
            title: t.title,
            description: t.description,
            requirementId: matchedReqId,
            priority: t.priority as TaskPriority,
          });
          existingTaskKeys.add(finalTaskKey);
        } catch (e) {
          console.warn("Task creation skipped:", finalTaskKey, e);
        }
      }

      setShowAiBacklogModal(false);
      setGeneratedBacklog(null);
      setBacklogPrompt("");
      showNotification("✓ Imported AI backlog into project Kanban board!");
      await fetchProjectDetails(selectedProjectId);
    } catch (err: any) {
      console.error("Failed to import backlog:", err);
      setError("Partial backlog import completed.");
      await fetchProjectDetails(selectedProjectId);
    } finally {
      setIsImportingBacklog(false);
    }
  };

  const workspaceDisplayName = isPersonalWorkspace
    ? "Personal Workspace"
    : currentOrg?.name || "Organization Workspace";

  return (
    <div className="space-y-8">
      {/* ========================================================================= */}
      {/* VIEW A: PROJECT ENGINEERING WORKSPACE (KANBAN BOARD & BACKLOG)            */}
      {/* ========================================================================= */}
      {selectedProject ? (
        <div className="space-y-6">
          {/* Workspace Top Header */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-5">
            <div>
              <div className="flex items-center gap-3">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={closeProjectWorkspace}
                  className="h-8 px-2.5 text-xs text-slate-400 hover:text-white"
                >
                  <ArrowLeft className="h-3.5 w-3.5 mr-1" />
                  All Projects
                </Button>
                <div className="h-4 w-px bg-slate-800" />
                <Badge variant="default" className="font-mono font-bold text-xs bg-indigo-600 text-white">
                  {selectedProject.key}
                </Badge>
                <h1 className="text-xl font-bold tracking-tight text-white">
                  {selectedProject.name}
                </h1>
              </div>
              <p className="text-xs text-slate-400 mt-1">
                {selectedProject.description || "Project engineering backlog and interactive delivery board."}
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              {/* Dynamic Health Score Pill */}
              {selectedProject.healthScore !== null && selectedProject.healthScore !== undefined && (
                <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-emerald-500/40 bg-emerald-950/30 text-xs font-semibold text-emerald-300">
                  <span className="h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
                  <span>{selectedProject.healthScore}% Traceability Health</span>
                </div>
              )}

              <Button
                variant="glow"
                size="sm"
                onClick={() => {
                  setBacklogPrompt(`Design full microservice delivery for ${selectedProject.name}`);
                  setShowAiBacklogModal(true);
                }}
                className="text-xs gap-1.5"
              >
                Sprint Planner
              </Button>

              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowAddReqModal(true)}
                className="text-xs gap-1 border-slate-700 bg-slate-900/60"
              >
                <Plus className="h-3.5 w-3.5 text-indigo-400" />
                Add Requirement
              </Button>

              <Button
                variant="outline"
                size="sm"
                onClick={() => setShowAddTaskModal(true)}
                className="text-xs gap-1 border-slate-700 bg-slate-900/60"
              >
                <Plus className="h-3.5 w-3.5 text-sky-400" />
                Add Task
              </Button>

              <Button
                variant="outline"
                size="sm"
                onClick={handleSyncGitActivity}
                disabled={isSyncingGit}
                className="text-xs gap-1.5 border-slate-700 bg-slate-900/60"
              >
                {isSyncingGit ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <GitCommit className="h-3.5 w-3.5 text-purple-400" />}
                Sync Git Commits
              </Button>

              <Link href={`/projects/${selectedProject.id}/traceability`}>
                <Button variant="secondary" size="sm" className="text-xs gap-1">
                  <Layers className="h-3.5 w-3.5" />
                  Trace Spine
                </Button>
              </Link>
            </div>
          </div>

          {/* Project Metadata Banner */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/40 space-y-0.5">
              <span className="text-[10px] uppercase font-bold text-slate-500 font-mono">Linked Repository</span>
              <div className="text-xs font-mono font-medium text-indigo-300 truncate">
                {selectedProject.repositoryName || "Unlinked"}
              </div>
            </div>
            <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/40 space-y-0.5">
              <span className="text-[10px] uppercase font-bold text-slate-500 font-mono">Active Requirements</span>
              <div className="text-xs font-mono font-bold text-white">
                {requirements.length} Requirements
              </div>
            </div>
            <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/40 space-y-0.5">
              <span className="text-[10px] uppercase font-bold text-slate-500 font-mono">Total Tasks</span>
              <div className="text-xs font-mono font-bold text-white">
                {tasks.length} Tasks ({tasks.filter((t) => t.status === "DONE").length} completed)
              </div>
            </div>
            <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/40 space-y-0.5">
              <span className="text-[10px] uppercase font-bold text-slate-500 font-mono">Sprint Status</span>
              <div className="text-xs font-mono font-semibold text-emerald-400">
                ACTIVE CYCLE
              </div>
            </div>
          </div>

          {/* Active Search Filter Indicator */}
          {rawSearchQuery && (
            <div className="flex items-center justify-between px-3.5 py-2 rounded-lg border border-[#27272c] bg-[#141418] text-xs text-zinc-300">
              <span className="flex items-center gap-2">
                <span>Filtering tasks by:</span>
                <span className="font-mono font-medium text-white bg-[#1f1f25] px-2 py-0.5 rounded border border-[#27272c]">
                  &quot;{rawSearchQuery}&quot;
                </span>
                <span className="text-zinc-500 font-mono text-[11px]">({tasks.filter((t) => {
                  if (!searchQuery) return true;
                  const titleMatch = (t.title || "").toLowerCase().includes(searchQuery);
                  const descMatch = (t.description || "").toLowerCase().includes(searchQuery);
                  const branchMatch = (t.branchName || "").toLowerCase().includes(searchQuery);
                  const keyMatch = (t.taskKey || "").toLowerCase().includes(searchQuery);
                  return titleMatch || descMatch || branchMatch || keyMatch;
                }).length} matching)</span>
              </span>
              <button
                type="button"
                onClick={() => router.push("/projects")}
                className="text-[11px] text-zinc-400 hover:text-white underline cursor-pointer"
              >
                Clear filter
              </button>
            </div>
          )}

          {/* Kanban Board */}
          {isLoadingTasks ? (
            <div className="flex flex-col items-center justify-center p-16 text-slate-400">
              <Loader2 className="h-8 w-8 animate-spin text-indigo-400 mb-2" />
              <p className="text-xs">Loading Kanban Board & Tasks...</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-5 gap-4">
              {KANBAN_COLUMNS.map((col) => {
                const colTasks = tasks
                  .filter((t) => {
                    if (!searchQuery) return true;
                    const titleMatch = (t.title || "").toLowerCase().includes(searchQuery);
                    const descMatch = (t.description || "").toLowerCase().includes(searchQuery);
                    const branchMatch = (t.branchName || "").toLowerCase().includes(searchQuery);
                    const keyMatch = (t.taskKey || "").toLowerCase().includes(searchQuery);
                    return titleMatch || descMatch || branchMatch || keyMatch;
                  })
                  .filter((t) => t.status === col.id);
                return (
                  <div key={col.id} className={`rounded-2xl border ${col.color} p-3.5 space-y-3 min-h-[500px] flex flex-col`}>
                    <div className="flex items-center justify-between pb-2 border-b border-slate-800">
                      <span className="text-xs font-bold text-white tracking-wide">{col.label}</span>
                      <span className={`text-[10px] font-mono px-2 py-0.5 rounded-full font-bold ${col.badgeColor}`}>
                        {colTasks.length}
                      </span>
                    </div>

                    <div className="space-y-3 flex-1 overflow-y-auto">
                      {colTasks.length === 0 ? (
                        <div className="h-28 flex items-center justify-center rounded-xl border border-dashed border-slate-800/60 text-[11px] text-slate-500">
                          Empty
                        </div>
                      ) : (
                        colTasks.map((t) => (
                          <div
                            key={t.id}
                            className="p-3 rounded-xl bg-slate-950 border border-slate-800 hover:border-slate-700 transition-all space-y-2.5 shadow-sm group"
                          >
                            <div className="flex items-center justify-between">
                              <Badge variant="outline" className="text-[10px] font-mono font-bold text-indigo-300 border-indigo-500/40">
                                {t.taskKey}
                              </Badge>
                              <Badge
                                variant="outline"
                                className={`text-[9px] font-mono ${
                                  t.priority === "CRITICAL"
                                    ? "text-rose-400 border-rose-500/40"
                                    : t.priority === "HIGH"
                                    ? "text-amber-400 border-amber-500/40"
                                    : "text-slate-400 border-slate-700"
                                }`}
                              >
                                {t.priority}
                              </Badge>
                            </div>

                            <div>
                              <h4 className="text-xs font-bold text-white group-hover:text-indigo-300 transition-colors leading-snug">
                                {t.title}
                              </h4>
                              {t.description && (
                                <p className="text-[11px] text-slate-400 line-clamp-2 mt-1 leading-relaxed">
                                  {t.description}
                                </p>
                              )}
                            </div>

                            {/* Linked requirement badge */}
                            {t.requirementKey && (
                              <div className="text-[10px] font-mono text-slate-500 flex items-center gap-1">
                                <Tag className="h-3 w-3 text-slate-500" />
                                <span>{t.requirementKey}</span>
                              </div>
                            )}

                            {/* Task Actions & Status Progression */}
                            <div className="pt-2 border-t border-slate-800/80 flex items-center justify-between gap-1">
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => openLinkDialog(t)}
                                className="text-[10px] h-6 px-1.5 border-slate-800 hover:bg-slate-900 text-slate-400 hover:text-white"
                                title="Attach Git commit, PR or branch"
                              >
                                <Link2 className="h-3 w-3 mr-0.5" />
                                Code Link
                              </Button>

                              <div className="flex items-center gap-1">
                                {t.status === "TODO" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "IN_PROGRESS")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-sky-950/60 border border-sky-500/40 text-sky-300 hover:bg-sky-900/60 font-semibold"
                                  >
                                    → In Prog
                                  </button>
                                )}
                                {t.status === "IN_PROGRESS" && (
                                  <>
                                    <button
                                      onClick={() => handleTransitionTask(t, "IN_REVIEW")}
                                      className="text-[10px] px-2 py-0.5 rounded bg-purple-950/60 border border-purple-500/40 text-purple-300 hover:bg-purple-900/60 font-semibold"
                                    >
                                      → Review
                                    </button>
                                    <button
                                      onClick={() => handleTransitionTask(t, "BLOCKED")}
                                      className="text-[10px] px-1.5 py-0.5 rounded bg-rose-950/60 border border-rose-500/40 text-rose-300 hover:bg-rose-900/60"
                                      title="Mark Blocked"
                                    >
                                      Block
                                    </button>
                                  </>
                                )}
                                {t.status === "IN_REVIEW" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "DONE")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-emerald-950/60 border border-emerald-500/40 text-emerald-300 hover:bg-emerald-900/60 font-bold"
                                  >
                                    ✓ Done
                                  </button>
                                )}
                                {t.status === "BLOCKED" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "IN_PROGRESS")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-sky-950/60 border border-sky-500/40 text-sky-300 hover:bg-sky-900/60"
                                  >
                                    Unblock
                                  </button>
                                )}
                                {t.status === "DONE" && (
                                  <span className="text-[10px] text-emerald-400 font-mono flex items-center gap-0.5">
                                    <Check className="h-3 w-3" /> Verified
                                  </span>
                                )}
                              </div>
                            </div>
                          </div>
                        ))
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : (
        /* ========================================================================= */
        /* VIEW B: ALL GOVERNED PROJECTS OVERVIEW                                    */
        /* ========================================================================= */
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
                Manage projects, sprint requirements, and audit end-to-end code delivery spines for{" "}
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
                          Traceability calculating...
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
                      <Button
                        variant="secondary"
                        size="sm"
                        onClick={() => openProjectWorkspace(project)}
                        className="flex-1 text-xs"
                      >
                        Open Kanban & Backlog
                      </Button>
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
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 1: CREATE GOVERNED PROJECT WITH REPOSITORY DROPDOWN                 */}
      {/* ========================================================================= */}
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

          {/* Repository Selector Dropdown */}
          <div className="space-y-1.5">
            <label className="font-medium text-slate-300 text-xs">Associated Repository</label>
            {availableRepositories.length === 0 ? (
              <div className="rounded-lg border border-slate-800 bg-slate-950 p-2.5 text-xs text-slate-400">
                No connected repositories found.{" "}
                <Link href="/repositories" className="text-sky-400 hover:underline">
                  Connect a repository
                </Link>{" "}
                first.
              </div>
            ) : (
              <select
                aria-label="Associated Repository"
                value={selectedRepoId}
                onChange={(e) => setSelectedRepoId(e.target.value)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
              >
                <option value="">None (Link later)</option>
                {availableRepositories.map((repo) => (
                  <option key={repo.id} value={repo.id}>
                    {repo.fullName || repo.name}
                  </option>
                ))}
              </select>
            )}
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

      {/* ========================================================================= */}
      {/* MODAL 2: AI SPRINT PLANNER (PRD TO BACKLOG GENERATOR)                     */}
      {/* ========================================================================= */}
      <Dialog open={showAiBacklogModal} onOpenChange={setShowAiBacklogModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>AI Sprint Planner & PRD Backlog Generator</DialogTitle>
              <DialogDescription>
                Convert technical requirements or product specs into structured engineering requirements and tasks.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowAiBacklogModal(false)} />
          </div>
        </DialogHeader>

        <div className="space-y-4 my-3 max-h-[70vh] overflow-y-auto pr-1">
          <form onSubmit={handleGenerateBacklog} className="space-y-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Feature or Sprint Goal</label>
              <textarea
                value={backlogPrompt}
                onChange={(e) => setBacklogPrompt(e.target.value)}
                rows={3}
                placeholder="e.g. Build real-time webhook retry queue with dead-letter alerting and tenant isolation."
                className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
                required
              />
            </div>

            <Button
              type="submit"
              variant="glow"
              size="sm"
              disabled={isGeneratingBacklog || !backlogPrompt.trim()}
              className="w-full text-xs gap-1.5"
            >
              {isGeneratingBacklog ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              Generate Sprint Backlog
            </Button>
          </form>

          {/* Generated Backlog Preview */}
          {generatedBacklog && (
            <div className="p-4 rounded-xl border border-indigo-500/40 bg-indigo-950/20 space-y-4">
              <div className="flex items-center justify-between border-b border-indigo-500/30 pb-2">
                <span className="text-xs font-bold text-white flex items-center gap-1.5">
                  <Sparkles className="h-4 w-4 text-indigo-400" />
                  Generated Backlog ({generatedBacklog.requirements.length} Reqs, {generatedBacklog.tasks.length} Tasks)
                </span>
                <Badge variant="outline" className="text-[10px] font-mono text-indigo-300">
                  Key: {generatedBacklog.project_key}
                </Badge>
              </div>

              {/* Requirements Preview */}
              <div className="space-y-2">
                <span className="text-xs font-semibold text-slate-300">Requirements:</span>
                <div className="space-y-1.5">
                  {generatedBacklog.requirements.map((r, rIdx) => (
                    <div key={rIdx} className="p-2.5 rounded-lg bg-slate-900 border border-slate-800 text-xs space-y-1">
                      <div className="flex items-center justify-between">
                        <span className="font-bold font-mono text-indigo-300">{r.identifier}</span>
                        <span className="text-white font-medium">{r.title}</span>
                      </div>
                      <p className="text-[11px] text-slate-400">{r.description}</p>
                    </div>
                  ))}
                </div>
              </div>

              {/* Tasks Preview */}
              <div className="space-y-2">
                <span className="text-xs font-semibold text-slate-300">Tasks:</span>
                <div className="space-y-1.5">
                  {generatedBacklog.tasks.map((t, tIdx) => (
                    <div key={tIdx} className="p-2.5 rounded-lg bg-slate-900 border border-slate-800 text-xs flex items-center justify-between">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-bold font-mono text-sky-300">{t.task_key}</span>
                          <span className="text-white font-medium">{t.title}</span>
                        </div>
                        <div className="text-[11px] text-slate-400 mt-0.5">
                          Mapped to: <strong className="text-indigo-400">{t.requirement_identifier}</strong>
                        </div>
                      </div>
                      <Badge variant="outline" className="text-[9px] font-mono text-amber-400 border-amber-500/40">
                        {t.priority}
                      </Badge>
                    </div>
                  ))}
                </div>
              </div>

              <div className="pt-2 border-t border-indigo-500/30 flex justify-end">
                <Button
                  variant="glow"
                  size="sm"
                  onClick={handleImportBacklog}
                  disabled={isImportingBacklog}
                  className="text-xs gap-1.5"
                >
                  {isImportingBacklog ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                  Import Backlog
                </Button>
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => setShowAiBacklogModal(false)}>
            Close
          </Button>
        </DialogFooter>
      </Dialog>

      {/* ========================================================================= */}
      {/* MODAL 3: ADD REQUIREMENT                                                  */}
      {/* ========================================================================= */}
      <Dialog open={showAddReqModal} onOpenChange={setShowAddReqModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Add Engineering Requirement</DialogTitle>
              <DialogDescription>
                Define functional specifications and compliance criteria for this project.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowAddReqModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleAddRequirement} className="space-y-3 my-3">
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Requirement Identifier</label>
            <Input
              value={reqIdentifier}
              onChange={(e) => setReqIdentifier(e.target.value.toUpperCase())}
              placeholder="e.g. REQ-001"
              required
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Title</label>
            <Input
              value={reqTitle}
              onChange={(e) => setReqTitle(e.target.value)}
              placeholder="e.g. Multi-Tenant JWT Context Isolation"
              required
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Description</label>
            <textarea
              value={reqDescription}
              onChange={(e) => setReqDescription(e.target.value)}
              rows={3}
              placeholder="Detailed acceptance criteria..."
              className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
            />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setShowAddReqModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="glow" size="sm" disabled={isSubmittingReq || !reqIdentifier || !reqTitle}>
              {isSubmittingReq ? "Creating..." : "Save Requirement"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>

      {/* ========================================================================= */}
      {/* MODAL 4: ADD TASK                                                         */}
      {/* ========================================================================= */}
      <Dialog open={showAddTaskModal} onOpenChange={setShowAddTaskModal}>
        <DialogHeader>
          <div className="flex items-center justify-between w-full">
            <div>
              <DialogTitle>Create Task</DialogTitle>
              <DialogDescription>
                Add an actionable engineering task mapped to project requirements.
              </DialogDescription>
            </div>
            <DialogClose onClose={() => setShowAddTaskModal(false)} />
          </div>
        </DialogHeader>

        <form onSubmit={handleAddTask} className="space-y-3 my-3">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Task Key</label>
              <Input
                value={taskKey}
                onChange={(e) => setTaskKey(e.target.value.toUpperCase())}
                placeholder="e.g. CORE-101"
                required
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Priority</label>
              <select
                aria-label="Priority"
                value={taskPriority}
                onChange={(e) => setTaskPriority(e.target.value as TaskPriority)}
                className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
              >
                <option value="CRITICAL">CRITICAL</option>
                <option value="HIGH">HIGH</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="LOW">LOW</option>
              </select>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Title</label>
            <Input
              value={taskTitle}
              onChange={(e) => setTaskTitle(e.target.value)}
              placeholder="e.g. Enforce tenant filter on repository queries"
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Mapped Requirement</label>
            <select
              aria-label="Mapped Requirement"
              value={taskReqId}
              onChange={(e) => setTaskReqId(e.target.value)}
              className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
            >
              <option value="">No linked requirement (Standalone)</option>
              {requirements.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.identifier} - {r.title}
                </option>
              ))}
            </select>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Description</label>
            <textarea
              value={taskDesc}
              onChange={(e) => setTaskDesc(e.target.value)}
              rows={3}
              placeholder="Technical implementation instructions..."
              className="w-full bg-slate-950 border border-slate-800 rounded-lg p-2.5 text-xs text-white focus:outline-none"
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setShowAddTaskModal(false)}>
              Cancel
            </Button>
            <Button type="submit" variant="glow" size="sm" disabled={isSubmittingTask || !taskKey || !taskTitle}>
              {isSubmittingTask ? "Creating..." : "Save Task"}
            </Button>
          </DialogFooter>
        </form>
      </Dialog>

      {/* ========================================================================= */}
      {/* MODAL 5: CODE LINK ATTACHMENT DIALOG                                      */}
      {/* ========================================================================= */}
      {linkTask && (
        <Dialog open={!!linkTask} onOpenChange={(open) => !open && setLinkTask(null)}>
          <DialogHeader>
            <div className="flex items-center justify-between w-full">
              <div>
                <DialogTitle>Code Artifact Links: {linkTask.taskKey}</DialogTitle>
                <DialogDescription>
                  Attach Git commits, pull requests, or branch references to trace delivery.
                </DialogDescription>
              </div>
              <DialogClose onClose={() => setLinkTask(null)} />
            </div>
          </DialogHeader>

          <div className="space-y-4 my-3 max-h-[60vh] overflow-y-auto pr-1">
            {/* Existing Links */}
            <div className="space-y-2">
              <span className="text-xs font-semibold text-slate-300">Existing Linked Artifacts:</span>
              {isLoadingLinks ? (
                <div className="p-4 text-center text-xs text-slate-400">Loading links...</div>
              ) : taskLinks.length === 0 ? (
                <div className="p-4 rounded-lg bg-slate-950 border border-slate-800 text-center text-xs text-slate-500">
                  No code artifacts attached yet.
                </div>
              ) : (
                <div className="space-y-1.5">
                  {taskLinks.map((lk) => (
                    <div
                      key={lk.id}
                      className="p-2.5 rounded-lg bg-slate-950 border border-slate-800 flex items-center justify-between text-xs"
                    >
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="text-[10px] font-mono text-purple-300 border-purple-500/30">
                          {lk.linkType}
                        </Badge>
                        <span className="font-mono text-white">{lk.externalRef}</span>
                      </div>
                      <span className="text-[10px] text-slate-500 font-mono">
                        {formatDate(lk.createdAt)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Attach Form */}
            <form onSubmit={handleAddCodeLink} className="p-3.5 rounded-xl border border-slate-800 bg-slate-900/60 space-y-3">
              <span className="text-xs font-semibold text-white">Attach New Code Reference:</span>
              <div className="grid grid-cols-3 gap-2">
                <div className="col-span-1 space-y-1">
                  <label className="text-[11px] font-medium text-slate-300">Type</label>
                  <select
                    aria-label="Link Type"
                    value={linkType}
                    onChange={(e) => setLinkType(e.target.value as any)}
                    className="w-full bg-slate-950 border border-slate-800 rounded p-2 text-xs text-white focus:outline-none"
                  >
                    <option value="COMMIT">Commit SHA</option>
                    <option value="PULL_REQUEST">Pull Request</option>
                    <option value="BRANCH">Branch</option>
                  </select>
                </div>
                <div className="col-span-2 space-y-1">
                  <label className="text-[11px] font-medium text-slate-300">Reference</label>
                  <Input
                    value={linkExternalRef}
                    onChange={(e) => setLinkExternalRef(e.target.value)}
                    placeholder={
                      linkType === "COMMIT"
                        ? "e.g. 7a1656b"
                        : linkType === "PULL_REQUEST"
                        ? "e.g. PR-#12"
                        : "e.g. feature/auth-guard"
                    }
                    required
                  />
                </div>
              </div>

              <div className="flex justify-end pt-1">
                <Button type="submit" variant="glow" size="sm" disabled={isSubmittingLink || !linkExternalRef.trim()} className="text-xs">
                  {isSubmittingLink ? "Attaching..." : "Attach Link"}
                </Button>
              </div>
            </form>
          </div>

          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setLinkTask(null)}>
              Close
            </Button>
          </DialogFooter>
        </Dialog>
      )}
    </div>
  );
}

export default function ProjectsPage() {
  return (
    <Suspense fallback={<div className="p-8 text-center text-zinc-500 text-xs">Loading projects & workspaces...</div>}>
      <ProjectsPageContent />
    </Suspense>
  );
}
