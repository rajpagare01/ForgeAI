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
  Loader2,
  AlertCircle,
  Check,
  Tag,
  GitCommit,
  ArrowLeft,
  Link2,
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

const KANBAN_COLUMNS: { id: TaskStatus; label: string; dotColor: string }[] = [
  { id: "TODO", label: "To Do", dotColor: "bg-zinc-500" },
  { id: "IN_PROGRESS", label: "In Progress", dotColor: "bg-sky-400" },
  { id: "IN_REVIEW", label: "In Review", dotColor: "bg-amber-400" },
  { id: "DONE", label: "Verified & Done", dotColor: "bg-emerald-400" },
  { id: "BLOCKED", label: "Blocked", dotColor: "bg-rose-400" },
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
    <div className="space-y-6">
      {/* Success Notification Toast */}
      {successToast && (
        <div className="fixed bottom-4 right-4 z-50 rounded-md border border-[#26262C] bg-[#141418] px-3.5 py-2 text-xs text-emerald-400 shadow-lg flex items-center gap-2">
          <Check className="h-4 w-4" />
          <span>{successToast}</span>
        </div>
      )}

      {/* ========================================================================= */}
      {/* VIEW A: PROJECT ENGINEERING WORKSPACE (KANBAN BOARD & BACKLOG)            */}
      {/* ========================================================================= */}
      {selectedProject ? (
        <div className="space-y-5">
          {/* Workspace Top Header Toolbar */}
          <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-4 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div>
                <div className="flex items-center gap-2.5">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={closeProjectWorkspace}
                    className="h-7 px-2 text-xs text-zinc-400 hover:text-zinc-100 hover:bg-zinc-800"
                  >
                    <ArrowLeft className="h-3.5 w-3.5 mr-1" />
                    All Projects
                  </Button>
                  <div className="h-3.5 w-px bg-[#26262C]" />
                  <Badge variant="outline" className="font-mono font-semibold text-[11px] text-zinc-200 border-[#26262C]">
                    {selectedProject.key}
                  </Badge>
                  <h1 className="text-base font-semibold tracking-tight text-zinc-100">
                    {selectedProject.name}
                  </h1>
                </div>
                <p className="text-xs text-zinc-400 mt-1 pl-1">
                  {selectedProject.description || "Project engineering backlog and interactive delivery board."}
                </p>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                {selectedProject.healthScore !== null && selectedProject.healthScore !== undefined && (
                  <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-emerald-900/60 bg-emerald-950/20 text-xs font-mono font-medium text-emerald-400">
                    <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                    <span>{selectedProject.healthScore}% Lineage</span>
                  </div>
                )}

                <Button
                  size="sm"
                  onClick={() => {
                    setBacklogPrompt(`Design full microservice delivery for ${selectedProject.name}`);
                    setShowAiBacklogModal(true);
                  }}
                  className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                >
                  <Sparkles className="h-3.5 w-3.5" />
                  Sprint Planner
                </Button>

                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setShowAddReqModal(true)}
                  className="h-8 text-xs gap-1 border-[#26262C] bg-[#141418] text-zinc-300 hover:text-zinc-100"
                >
                  <Plus className="h-3.5 w-3.5" />
                  Requirement
                </Button>

                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setShowAddTaskModal(true)}
                  className="h-8 text-xs gap-1 border-[#26262C] bg-[#141418] text-zinc-300 hover:text-zinc-100"
                >
                  <Plus className="h-3.5 w-3.5" />
                  Task
                </Button>

                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleSyncGitActivity}
                  disabled={isSyncingGit}
                  className="h-8 text-xs gap-1.5 border-[#26262C] bg-[#141418] text-zinc-300 hover:text-zinc-100"
                >
                  {isSyncingGit ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <GitCommit className="h-3.5 w-3.5 text-zinc-400" />}
                  Sync Commits
                </Button>

                <Link href={`/projects/${selectedProject.id}/traceability`}>
                  <Button variant="outline" size="sm" className="h-8 text-xs gap-1 border-[#26262C] text-zinc-200">
                    <Layers className="h-3.5 w-3.5" />
                    Trace Spine
                  </Button>
                </Link>
              </div>
            </div>
          </div>

          {/* Project Metadata Strip */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-0.5">
              <span className="text-[10px] uppercase font-medium text-zinc-500 font-mono">Linked Repository</span>
              <div className="text-xs font-mono font-medium text-zinc-200 truncate">
                {selectedProject.repositoryName || "Unlinked"}
              </div>
            </div>
            <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-0.5">
              <span className="text-[10px] uppercase font-medium text-zinc-500 font-mono">Active Requirements</span>
              <div className="text-xs font-mono font-semibold text-zinc-100">
                {requirements.length} Requirements
              </div>
            </div>
            <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-0.5">
              <span className="text-[10px] uppercase font-medium text-zinc-500 font-mono">Total Tasks</span>
              <div className="text-xs font-mono font-semibold text-zinc-100">
                {tasks.length} Tasks ({tasks.filter((t) => t.status === "DONE").length} completed)
              </div>
            </div>
            <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-0.5">
              <span className="text-[10px] uppercase font-medium text-zinc-500 font-mono">Sprint Status</span>
              <div className="text-xs font-mono font-semibold text-emerald-400">
                ACTIVE CYCLE
              </div>
            </div>
          </div>

          {/* Active Search Filter Indicator */}
          {rawSearchQuery && (
            <div className="flex items-center justify-between px-3.5 py-2 rounded-md border border-[#26262C] bg-[#141418] text-xs text-zinc-300">
              <span className="flex items-center gap-2">
                <span>Filtering tasks by:</span>
                <span className="font-mono font-medium text-zinc-100 bg-[#0E0E11] px-2 py-0.5 rounded border border-[#26262C]">
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
            <div className="flex flex-col items-center justify-center p-16 text-zinc-400">
              <Loader2 className="h-6 w-6 animate-spin text-zinc-400 mb-2" />
              <p className="text-xs font-mono">Loading Kanban Board & Tasks...</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-5 gap-3">
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
                  <div key={col.id} className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-3 space-y-2.5 min-h-[520px] flex flex-col">
                    <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
                      <div className="flex items-center gap-1.5">
                        <span className={`h-2 w-2 rounded-full ${col.dotColor}`} />
                        <span className="text-xs font-semibold text-zinc-200 tracking-tight">{col.label}</span>
                      </div>
                      <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#141418] text-zinc-400 border border-[#26262C]">
                        {colTasks.length}
                      </span>
                    </div>

                    <div className="space-y-2 flex-1 overflow-y-auto">
                      {colTasks.length === 0 ? (
                        <div className="h-24 flex items-center justify-center rounded-md border border-dashed border-[#1C1C21] text-[11px] text-zinc-600">
                          Empty
                        </div>
                      ) : (
                        colTasks.map((t) => (
                          <div
                            key={t.id}
                            className="p-3 rounded-md bg-[#141418] border border-[#1C1C21] hover:border-[#26262C] transition-all space-y-2 group shadow-xs"
                          >
                            <div className="flex items-center justify-between">
                              <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                                {t.taskKey}
                              </Badge>
                              <Badge
                                variant="outline"
                                className={`text-[9px] font-mono ${
                                  t.priority === "CRITICAL"
                                    ? "text-rose-400 border-rose-900"
                                    : t.priority === "HIGH"
                                    ? "text-amber-400 border-amber-900"
                                    : "text-zinc-400 border-[#26262C]"
                                }`}
                              >
                                {t.priority}
                              </Badge>
                            </div>

                            <div>
                              <h4 className="text-xs font-semibold text-zinc-200 group-hover:text-zinc-100 transition-colors leading-snug">
                                {t.title}
                              </h4>
                              {t.description && (
                                <p className="text-[11px] text-zinc-400 line-clamp-2 mt-1 leading-relaxed">
                                  {t.description}
                                </p>
                              )}
                            </div>

                            {/* Linked requirement badge */}
                            {t.requirementKey && (
                              <div className="text-[10px] font-mono text-zinc-500 flex items-center gap-1">
                                <Tag className="h-3 w-3 text-zinc-500" />
                                <span>{t.requirementKey}</span>
                              </div>
                            )}

                            {/* Task Actions & Status Progression */}
                            <div className="pt-2 border-t border-[#1C1C21] flex items-center justify-between gap-1">
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => openLinkDialog(t)}
                                className="text-[10px] h-6 px-1.5 border-[#26262C] hover:bg-[#1C1C21] text-zinc-400 hover:text-zinc-200"
                                title="Attach Git commit, PR or branch"
                              >
                                <Link2 className="h-3 w-3 mr-0.5" />
                                Link
                              </Button>

                              <div className="flex items-center gap-1">
                                {t.status === "TODO" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "IN_PROGRESS")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-sky-950/40 border border-sky-800 text-sky-300 hover:bg-sky-900/50 font-medium font-mono"
                                  >
                                    → In Prog
                                  </button>
                                )}
                                {t.status === "IN_PROGRESS" && (
                                  <>
                                    <button
                                      onClick={() => handleTransitionTask(t, "IN_REVIEW")}
                                      className="text-[10px] px-2 py-0.5 rounded bg-amber-950/40 border border-amber-800 text-amber-300 hover:bg-amber-900/50 font-medium font-mono"
                                    >
                                      → Review
                                    </button>
                                    <button
                                      onClick={() => handleTransitionTask(t, "BLOCKED")}
                                      className="text-[10px] px-1.5 py-0.5 rounded bg-rose-950/40 border border-rose-800 text-rose-300 hover:bg-rose-900/50 font-medium font-mono"
                                      title="Mark Blocked"
                                    >
                                      Block
                                    </button>
                                  </>
                                )}
                                {t.status === "IN_REVIEW" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "DONE")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-emerald-950/40 border border-emerald-800 text-emerald-300 hover:bg-emerald-900/50 font-medium font-mono"
                                  >
                                    ✓ Done
                                  </button>
                                )}
                                {t.status === "BLOCKED" && (
                                  <button
                                    onClick={() => handleTransitionTask(t, "IN_PROGRESS")}
                                    className="text-[10px] px-2 py-0.5 rounded bg-sky-950/40 border border-sky-800 text-sky-300 hover:bg-sky-900/50 font-medium font-mono"
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
        <div className="space-y-6">
          {/* Page Header */}
          <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <FolderGit2 className="h-5 w-5 text-zinc-300" />
                  <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
                    Governed Projects
                  </h1>
                </div>
                <p className="text-xs text-zinc-400">
                  Manage projects, sprint requirements, and audit end-to-end code delivery spines for{" "}
                  <span className="text-zinc-200 font-medium">{workspaceDisplayName}</span>.
                </p>
              </div>

              <Button
                onClick={() => setShowCreateModal(true)}
                className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
              >
                <Plus className="h-3.5 w-3.5" />
                Create Project
              </Button>
            </div>
          </div>

          {error && (
            <div className="flex items-center gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300">
              <AlertCircle className="h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          {/* Loading state */}
          {isLoading && (
            <div className="flex flex-col items-center justify-center p-16 text-zinc-400">
              <Loader2 className="h-6 w-6 animate-spin text-zinc-400 mb-2" />
              <p className="text-xs font-mono">Fetching governed projects from Gateway...</p>
            </div>
          )}

          {/* Empty State */}
          {!isLoading && projects.length === 0 && (
            <div className="flex flex-col items-center justify-center rounded-lg border border-dashed border-[#26262C] p-16 text-center">
              <FolderGit2 className="h-10 w-10 text-zinc-600 mb-3" />
              <h3 className="text-sm font-semibold text-zinc-200">No projects found</h3>
              <p className="text-xs text-zinc-400 max-w-sm mt-1 mb-5">
                Get started by creating your first governed project to link requirements, tasks, and pull requests.
              </p>
              <Button onClick={() => setShowCreateModal(true)} className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
                <Plus className="h-3.5 w-3.5" />
                Create Project
              </Button>
            </div>
          )}

          {/* Projects Grid */}
          {!isLoading && projects.length > 0 && (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {projects.map((project) => (
                <Card
                  key={project.id}
                  className="p-4 border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C] transition-all flex flex-col justify-between"
                >
                  <CardHeader className="p-0 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <Badge variant="outline" className="font-mono font-semibold text-xs text-zinc-200 border-[#26262C]">
                        {project.key}
                      </Badge>
                      {project.healthScore !== null && project.healthScore !== undefined ? (
                        <div className="flex items-center gap-1.5 text-[11px] font-mono text-emerald-400">
                          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                          {project.healthScore}% Lineage
                        </div>
                      ) : (
                        <span className="text-[11px] text-zinc-500 font-mono">
                          Trace calculating...
                        </span>
                      )}
                    </div>
                    <div>
                      <CardTitle className="text-sm font-semibold text-zinc-100">
                        {project.name}
                      </CardTitle>
                      <CardDescription className="text-xs text-zinc-400 line-clamp-2 mt-1">
                        {project.description || "No project description provided."}
                      </CardDescription>
                    </div>
                  </CardHeader>

                  <CardContent className="p-0 space-y-3 pt-3 mt-3 border-t border-[#1C1C21]">
                    <div className="flex items-center justify-between text-xs text-zinc-400">
                      <div className="flex items-center gap-1">
                        <span className="font-mono font-semibold text-zinc-200">{project.requirementCount}</span>
                        <span>Requirements</span>
                      </div>
                      <div className="h-3 w-px bg-[#26262C]" />
                      <div className="flex items-center gap-1">
                        <span className="font-mono font-semibold text-zinc-200">{project.taskCount}</span>
                        <span>Tasks</span>
                      </div>
                    </div>

                    <div className="flex items-center justify-between text-[11px] text-zinc-500 font-mono">
                      <span className="truncate max-w-[180px]">
                        {project.repositoryName || "No repository linked"}
                      </span>
                      {project.repositoryName ? (
                        <span className="text-emerald-400 flex items-center gap-1">
                          <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                          Connected
                        </span>
                      ) : (
                        <span className="text-zinc-600">Unlinked</span>
                      )}
                    </div>

                    <div className="pt-2 flex items-center gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => openProjectWorkspace(project)}
                        className="flex-1 h-8 text-xs border-[#26262C] bg-[#141418] text-zinc-200 hover:text-white"
                      >
                        Kanban & Backlog
                      </Button>
                      <Link
                        href={`/projects/${project.id}/traceability`}
                        className="flex-1"
                      >
                        <Button size="sm" className="w-full h-8 text-xs gap-1 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
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

        <form onSubmit={handleCreate} className="space-y-3.5 mt-3">
          <div className="rounded-md border border-[#1C1C21] bg-[#141418] p-2.5 flex items-center justify-between text-xs">
            <div className="flex items-center gap-2 text-zinc-400">
              <span className="text-zinc-500 font-mono uppercase text-[10px]">Target Workspace:</span>
              <span className="font-semibold text-zinc-200">{workspaceDisplayName}</span>
            </div>
            <Badge
              variant="outline"
              className="text-zinc-300 border-[#26262C] font-mono text-[10px]"
            >
              {isPersonalWorkspace ? "PERSONAL" : "ORGANIZATION"}
            </Badge>
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div className="col-span-1 space-y-1">
              <label className="text-xs font-medium text-zinc-300">Project Key</label>
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
              <label className="text-xs font-medium text-zinc-300">Project Name</label>
              <Input
                placeholder="e.g. Identity & Security Core"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Description</label>
            <textarea
              placeholder="High-level architecture and scope description..."
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="h-20 w-full rounded-md border border-[#26262C] bg-[#09090B] p-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
            />
          </div>

          {/* Repository Selector Dropdown */}
          <div className="space-y-1">
            <label className="font-medium text-zinc-300 text-xs">Associated Repository</label>
            {availableRepositories.length === 0 ? (
              <div className="rounded-md border border-[#26262C] bg-[#09090B] p-2.5 text-xs text-zinc-400">
                No connected repositories found.{" "}
                <Link href="/repositories" className="text-zinc-200 underline">
                  Connect a repository
                </Link>{" "}
                first.
              </div>
            ) : (
              <select
                aria-label="Associated Repository"
                value={selectedRepoId}
                onChange={(e) => setSelectedRepoId(e.target.value)}
                className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
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
              className="h-8 text-xs border-[#26262C]"
            >
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={!name || !key || isSubmitting} className="h-8 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
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
              <label className="text-xs font-medium text-zinc-300">Feature or Sprint Goal</label>
              <textarea
                value={backlogPrompt}
                onChange={(e) => setBacklogPrompt(e.target.value)}
                rows={3}
                placeholder="e.g. Build real-time webhook retry queue with dead-letter alerting and tenant isolation."
                className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
                required
              />
            </div>

            <Button
              type="submit"
              size="sm"
              disabled={isGeneratingBacklog || !backlogPrompt.trim()}
              className="w-full h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
            >
              {isGeneratingBacklog ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Sparkles className="h-3.5 w-3.5" />}
              Generate Sprint Backlog
            </Button>
          </form>

          {/* Generated Backlog Preview */}
          {generatedBacklog && (
            <div className="p-4 rounded-md border border-[#26262C] bg-[#141418] space-y-3.5">
              <div className="flex items-center justify-between border-b border-[#26262C] pb-2">
                <span className="text-xs font-semibold text-zinc-200 flex items-center gap-1.5">
                  <Sparkles className="h-4 w-4 text-zinc-400" />
                  Generated Backlog ({generatedBacklog.requirements.length} Reqs, {generatedBacklog.tasks.length} Tasks)
                </span>
                <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                  Key: {generatedBacklog.project_key}
                </Badge>
              </div>

              {/* Requirements Preview */}
              <div className="space-y-1.5">
                <span className="text-xs font-medium text-zinc-300">Requirements:</span>
                <div className="space-y-1.5">
                  {generatedBacklog.requirements.map((r, rIdx) => (
                    <div key={rIdx} className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] text-xs space-y-1">
                      <div className="flex items-center justify-between">
                        <span className="font-semibold font-mono text-zinc-200">{r.identifier}</span>
                        <span className="text-zinc-300 font-medium">{r.title}</span>
                      </div>
                      <p className="text-[11px] text-zinc-400">{r.description}</p>
                    </div>
                  ))}
                </div>
              </div>

              {/* Tasks Preview */}
              <div className="space-y-1.5">
                <span className="text-xs font-medium text-zinc-300">Tasks:</span>
                <div className="space-y-1.5">
                  {generatedBacklog.tasks.map((t, tIdx) => (
                    <div key={tIdx} className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] text-xs flex items-center justify-between">
                      <div>
                        <div className="flex items-center gap-2">
                          <span className="font-semibold font-mono text-zinc-200">{t.task_key}</span>
                          <span className="text-zinc-300 font-medium">{t.title}</span>
                        </div>
                        <div className="text-[11px] text-zinc-500 mt-0.5">
                          Mapped to: <strong className="text-zinc-400">{t.requirement_identifier}</strong>
                        </div>
                      </div>
                      <Badge variant="outline" className="text-[9px] font-mono text-zinc-300 border-[#26262C]">
                        {t.priority}
                      </Badge>
                    </div>
                  ))}
                </div>
              </div>

              <div className="pt-2 border-t border-[#26262C] flex justify-end">
                <Button
                  size="sm"
                  onClick={handleImportBacklog}
                  disabled={isImportingBacklog}
                  className="h-8 text-xs gap-1.5 bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium"
                >
                  {isImportingBacklog ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Check className="h-3.5 w-3.5" />}
                  Import Backlog
                </Button>
              </div>
            </div>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => setShowAiBacklogModal(false)} className="h-8 text-xs border-[#26262C]">
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
            <label className="text-xs font-medium text-zinc-300">Requirement Identifier</label>
            <Input
              value={reqIdentifier}
              onChange={(e) => setReqIdentifier(e.target.value.toUpperCase())}
              placeholder="e.g. REQ-001"
              required
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Title</label>
            <Input
              value={reqTitle}
              onChange={(e) => setReqTitle(e.target.value)}
              placeholder="e.g. Multi-Tenant JWT Context Isolation"
              required
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Description</label>
            <textarea
              value={reqDescription}
              onChange={(e) => setReqDescription(e.target.value)}
              rows={3}
              placeholder="Detailed acceptance criteria..."
              className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
            />
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setShowAddReqModal(false)} className="h-8 text-xs border-[#26262C]">
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={isSubmittingReq || !reqIdentifier || !reqTitle} className="h-8 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
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
              <label className="text-xs font-medium text-zinc-300">Task Key</label>
              <Input
                value={taskKey}
                onChange={(e) => setTaskKey(e.target.value.toUpperCase())}
                placeholder="e.g. CORE-101"
                required
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-zinc-300">Priority</label>
              <select
                aria-label="Priority"
                value={taskPriority}
                onChange={(e) => setTaskPriority(e.target.value as TaskPriority)}
                className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
              >
                <option value="CRITICAL">CRITICAL</option>
                <option value="HIGH">HIGH</option>
                <option value="MEDIUM">MEDIUM</option>
                <option value="LOW">LOW</option>
              </select>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Title</label>
            <Input
              value={taskTitle}
              onChange={(e) => setTaskTitle(e.target.value)}
              placeholder="e.g. Enforce tenant filter on repository queries"
              required
            />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-zinc-300">Mapped Requirement</label>
            <select
              aria-label="Mapped Requirement"
              value={taskReqId}
              onChange={(e) => setTaskReqId(e.target.value)}
              className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2 text-xs text-zinc-200 focus:outline-none"
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
            <label className="text-xs font-medium text-zinc-300">Description</label>
            <textarea
              value={taskDesc}
              onChange={(e) => setTaskDesc(e.target.value)}
              rows={3}
              placeholder="Technical implementation instructions..."
              className="w-full bg-[#09090B] border border-[#26262C] rounded-md p-2.5 text-xs text-zinc-200 placeholder-zinc-500 focus:outline-none focus:border-zinc-400"
            />
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={() => setShowAddTaskModal(false)} className="h-8 text-xs border-[#26262C]">
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={isSubmittingTask || !taskKey || !taskTitle} className="h-8 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
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
            <div className="space-y-1.5">
              <span className="text-xs font-medium text-zinc-300">Existing Linked Artifacts:</span>
              {isLoadingLinks ? (
                <div className="p-4 text-center text-xs text-zinc-400">Loading links...</div>
              ) : taskLinks.length === 0 ? (
                <div className="p-4 rounded-md bg-[#09090B] border border-[#26262C] text-center text-xs text-zinc-500">
                  No code artifacts attached yet.
                </div>
              ) : (
                <div className="space-y-1.5">
                  {taskLinks.map((lk) => (
                    <div
                      key={lk.id}
                      className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] flex items-center justify-between text-xs"
                    >
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                          {lk.linkType}
                        </Badge>
                        <span className="font-mono text-zinc-200">{lk.externalRef}</span>
                      </div>
                      <span className="text-[10px] text-zinc-500 font-mono">
                        {formatDate(lk.createdAt)}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Attach Form */}
            <form onSubmit={handleAddCodeLink} className="p-3.5 rounded-md border border-[#26262C] bg-[#141418] space-y-3">
              <span className="text-xs font-semibold text-zinc-200">Attach New Code Reference:</span>
              <div className="grid grid-cols-3 gap-2">
                <div className="col-span-1 space-y-1">
                  <label className="text-[11px] font-medium text-zinc-300">Type</label>
                  <select
                    aria-label="Link Type"
                    value={linkType}
                    onChange={(e) => setLinkType(e.target.value as any)}
                    className="w-full bg-[#09090B] border border-[#26262C] rounded p-2 text-xs text-zinc-200 focus:outline-none"
                  >
                    <option value="COMMIT">Commit SHA</option>
                    <option value="PULL_REQUEST">Pull Request</option>
                    <option value="BRANCH">Branch</option>
                  </select>
                </div>
                <div className="col-span-2 space-y-1">
                  <label className="text-[11px] font-medium text-zinc-300">Reference</label>
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
                <Button type="submit" size="sm" disabled={isSubmittingLink || !linkExternalRef.trim()} className="h-7 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
                  {isSubmittingLink ? "Attaching..." : "Attach Link"}
                </Button>
              </div>
            </form>
          </div>

          <DialogFooter>
            <Button variant="outline" size="sm" onClick={() => setLinkTask(null)} className="h-8 text-xs border-[#26262C]">
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
