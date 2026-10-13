"use client";

import React, { useState, Suspense } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Task, TaskStatus, TaskPriority } from "@/types";
import {
  FolderGit2,
  Layers,
  Plus,
  GitPullRequest,
  CheckCircle2,
  Clock,
  ArrowRight,
  ExternalLink,
  Tag,
  User,
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

const INITIAL_TASKS: Task[] = [
  {
    id: "t-1",
    projectId: "proj-forgeai-core",
    taskKey: "TASK-201",
    requirementKey: "REQ-501",
    title: "Implement Distributed Redis Revocation Token Filter",
    description: "Intercept all Bearer tokens at the API gateway layer against blacklisted JTI hashes.",
    status: "DONE",
    priority: "CRITICAL",
    assigneeName: "Raj Pagare",
    branchName: "feat/jwt-revocation-redis",
    linkedPrNumber: 42,
    updatedAt: "2026-10-13T09:00:00Z",
  },
  {
    id: "t-2",
    projectId: "proj-forgeai-core",
    taskKey: "TASK-205",
    requirementKey: "REQ-502",
    title: "Tree-sitter Parser Integration with Blast Radius Engine",
    description: "Extract AST nodes from modified source files to compute dependency call edges.",
    status: "IN_REVIEW",
    priority: "HIGH",
    assigneeName: "Elena Rostova",
    branchName: "feat/tree-sitter-ast",
    linkedPrNumber: 47,
    updatedAt: "2026-10-13T08:30:00Z",
  },
  {
    id: "t-3",
    projectId: "proj-forgeai-core",
    taskKey: "TASK-188",
    requirementKey: "REQ-409",
    title: "Harmonize CHAR(40) & JSONB Schema Types in Hibernate 6",
    description: "Annotate commitSha with @JdbcTypeCode(SqlTypes.CHAR) and payload with SqlTypes.JSON.",
    status: "DONE",
    priority: "HIGH",
    assigneeName: "Raj Pagare",
    branchName: "fix/hibernate-column-types",
    linkedPrNumber: 39,
    updatedAt: "2026-10-13T07:45:00Z",
  },
  {
    id: "t-4",
    projectId: "proj-forgeai-core",
    taskKey: "TASK-220",
    requirementKey: "REQ-515",
    title: "Kafka Event Publisher for Source Control Webhooks",
    description: "Transition transactional webhook delivery to the outbox event table.",
    status: "IN_PROGRESS",
    priority: "CRITICAL",
    assigneeName: "Marcus Vance",
    branchName: "feat/outbox-kafka-pipeline",
    linkedPrNumber: 51,
    updatedAt: "2026-10-13T06:00:00Z",
  },
  {
    id: "t-5",
    projectId: "proj-forgeai-core",
    taskKey: "TASK-231",
    requirementKey: "REQ-520",
    title: "Automated Flaky Test Quarantine in Testing Service",
    description: "Detect non-deterministic test run failures across 3 consecutive staging commits.",
    status: "TODO",
    priority: "MEDIUM",
    assigneeName: "Elena Rostova",
    updatedAt: "2026-10-13T05:00:00Z",
  },
];

const COLUMNS: { id: TaskStatus; label: string; color: string }[] = [
  { id: "TODO", label: "To Do", color: "border-slate-700" },
  { id: "IN_PROGRESS", label: "In Progress", color: "border-sky-500/40" },
  { id: "IN_REVIEW", label: "In Review", color: "border-purple-500/40" },
  { id: "DONE", label: "Verified & Done", color: "border-emerald-500/40" },
];

function ProjectDetailContent() {
  const params = useParams();
  const projectId = (params?.projectId as string) || "proj-forgeai-core";

  const [tasks, setTasks] = useState<Task[]>(INITIAL_TASKS);
  const [showTaskModal, setShowTaskModal] = useState(false);
  const [taskTitle, setTaskTitle] = useState("");
  const [taskKey, setTaskKey] = useState("");
  const [reqKey, setReqKey] = useState("REQ-501");
  const [priority, setPriority] = useState<TaskPriority>("HIGH");
  const [assignee, setAssignee] = useState("");

  const handleCreateTask = (e: React.FormEvent) => {
    e.preventDefault();
    if (!taskTitle) return;

    const newTask: Task = {
      id: `t-${Date.now()}`,
      projectId,
      taskKey: taskKey || `TASK-${Math.floor(200 + Math.random() * 50)}`,
      requirementKey: reqKey,
      title: taskTitle,
      description: "Added to project sprint board.",
      status: "TODO",
      priority,
      assigneeName: assignee || "Lead Engineer",
      updatedAt: new Date().toISOString(),
    };

    setTasks([...tasks, newTask]);
    setShowTaskModal(false);
    setTaskTitle("");
    setTaskKey("");
    setAssignee("");
  };

  const moveTask = (taskId: string, newStatus: TaskStatus) => {
    setTasks((prev) =>
      prev.map((t) => (t.id === taskId ? { ...t, status: newStatus } : t))
    );
  };

  return (
    <div className="space-y-8">
      {/* Project Banner with Traceability Anchor link */}
      <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-6 rounded-2xl border border-slate-800 bg-slate-900/60 p-6 backdrop-blur-xl">
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Badge variant="default" className="font-mono text-xs">
              PROJECT: FORGE-CORE
            </Badge>
            <span className="text-xs text-slate-400">
              Repository: <span className="font-mono text-slate-200">forgeai/governance-core</span>
            </span>
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white">
            ForgeAI Core Governance Platform
          </h1>
          <p className="text-xs text-slate-400 max-w-2xl">
            Sprint task execution board linked deterministically to engineering requirements and verified PR blast radii.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowTaskModal(true)}
            className="gap-1.5"
          >
            <Plus className="h-4 w-4" />
            Add Task
          </Button>
          <Link href={`/projects/${projectId}/traceability`}>
            <Button variant="glow" size="sm" className="gap-2">
              <Layers className="h-4 w-4" />
              Open Traceability Spine Matrix
              <ArrowRight className="h-3.5 w-3.5" />
            </Button>
          </Link>
        </div>
      </div>

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
                        <span className="flex items-center gap-1 font-mono text-sky-400">
                          <GitPullRequest className="h-3 w-3" />
                          #{task.linkedPrNumber}
                        </span>
                      ) : (
                        <span className="text-slate-600">No PR yet</span>
                      )}

                      <span className="flex items-center gap-1 text-slate-300">
                        <User className="h-3 w-3 text-slate-500" />
                        {task.assigneeName}
                      </span>
                    </div>

                    {/* Quick Move Trigger */}
                    <div className="flex items-center justify-end gap-1 pt-1">
                      {col.id !== "TODO" && (
                        <button
                          onClick={() => moveTask(task.id, "TODO")}
                          className="text-[10px] text-slate-500 hover:text-slate-300 px-1 py-0.5 rounded"
                          title="Move to Todo"
                        >
                          ← Todo
                        </button>
                      )}
                      {col.id !== "IN_PROGRESS" && (
                        <button
                          onClick={() => moveTask(task.id, "IN_PROGRESS")}
                          className="text-[10px] text-slate-500 hover:text-slate-300 px-1 py-0.5 rounded"
                          title="Move to In Progress"
                        >
                          {col.id === "TODO" ? "Progress →" : "← Progress"}
                        </button>
                      )}
                      {col.id !== "DONE" && (
                        <button
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
                placeholder="TASK-240"
                value={taskKey}
                onChange={(e) => setTaskKey(e.target.value.toUpperCase())}
              />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium text-slate-300">Bound Requirement</label>
              <Input
                placeholder="REQ-501"
                value={reqKey}
                onChange={(e) => setReqKey(e.target.value.toUpperCase())}
                required
              />
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium text-slate-300">Task Summary</label>
            <Input
              placeholder="e.g. Implement Redis JTI hash revocation filter"
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
                placeholder="e.g. Raj Pagare"
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
            <Button type="submit" variant="glow" size="sm" disabled={!taskTitle}>
              Create Task
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

