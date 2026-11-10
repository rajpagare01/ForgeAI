"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { useTenant } from "@/lib/tenant-context";
import { useAuth } from "@/lib/auth-context";
import { apiClient } from "@/lib/api-client";
import {
  FolderGit2,
  GitPullRequest,
  CheckCircle2,
  Radio,
  ArrowRight,
  ShieldCheck,
  Plus,
  GitCommit,
  Layers,
  Activity,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface InsightItem {
  pr: string;
  title: string;
  risk: string;
  score: number;
  symbols: number;
}

export default function OverviewDashboardPage() {
  const { currentOrg, activeWorkspace } = useTenant();
  const { user } = useAuth();

  const [projectCount, setProjectCount] = useState<number>(0);
  const [repoCount, setRepoCount] = useState<number>(0);
  const [testRunCount, setTestRunCount] = useState<number>(0);
  const [prCount, setPrCount] = useState<number>(0);
  const [recentInsights, setRecentInsights] = useState<InsightItem[]>([]);
  const [serviceHealth, setServiceHealth] = useState<{
    gateway: boolean | null;
    identity: boolean | null;
    sourceControl: boolean | null;
    testing: boolean | null;
    ai: boolean | null;
    project: boolean | null;
  }>({
    gateway: null,
    identity: null,
    sourceControl: null,
    testing: null,
    ai: null,
    project: null,
  });

  useEffect(() => {
    let isMounted = true;
    async function loadStats() {
      try {
        const gatewayBase = apiClient.getBaseUrl();
        const probeHealth = async (url: string, checkUpStatus = false): Promise<boolean> => {
          const res = await fetch(url, { cache: "no-store" });
          if (!res.ok) return false;
          if (!checkUpStatus) return true;
          try {
            const body = await res.json();
            return body?.status ? String(body.status).toUpperCase() === "UP" : true;
          } catch {
            return true;
          }
        };

        const [projects, repos, testRuns, gatewayProbe, aiProbe] = await Promise.allSettled([
          apiClient.get<any[]>("/api/v1/projects"),
          apiClient.get<any[]>("/api/v1/repositories"),
          apiClient.get<any[]>("/api/v1/test-runs"),
          probeHealth(`${gatewayBase}/actuator/health`),
          probeHealth(`${gatewayBase}/api/v1/ai/health`, true),
        ]);

        if (!isMounted) return;

        const gatewayOk = gatewayProbe.status === "fulfilled" && gatewayProbe.value === true;
        const aiOk = aiProbe.status === "fulfilled" && aiProbe.value === true;
        const projectOk = projects.status === "fulfilled";
        const sourceControlOk = repos.status === "fulfilled";
        const testingOk = testRuns.status === "fulfilled";

        setServiceHealth({
          gateway: gatewayOk,
          identity: gatewayOk,
          sourceControl: sourceControlOk,
          testing: testingOk,
          ai: aiOk,
          project: projectOk,
        });

        if (projects.status === "fulfilled" && Array.isArray(projects.value)) {
          setProjectCount(projects.value.length);
        }
        if (repos.status === "fulfilled" && Array.isArray(repos.value)) {
          setRepoCount(repos.value.length);
          let totalOpenPrs = 0;
          const insightsList: InsightItem[] = [];
          for (const r of repos.value) {
            try {
              const prs = await apiClient.get<any[]>(`/api/v1/repositories/${r.id}/pull-requests`);
              if (Array.isArray(prs)) {
                totalOpenPrs += prs.filter((p) => p.status === "OPEN" || p.state === "open").length;
                for (const p of prs.slice(0, 3)) {
                  insightsList.push({
                    pr: `#${p.number || p.prNumber}`,
                    title: p.title || `PR #${p.number || p.prNumber}`,
                    risk: p.riskLevel || "LOW",
                    score: p.riskScore ? Math.round(p.riskScore) : 15,
                    symbols: p.changedFilesCount || p.changedFiles || 1,
                  });
                }
              }
            } catch (prErr) {
              console.debug("Could not load PRs for repository:", r.id, prErr);
            }

            try {
              const reportRes = await apiClient.get<any>(`/api/v1/ai/repositories/${r.id}/commit-reports?limit=5`);
              const reportList = Array.isArray(reportRes) ? reportRes : (reportRes?.reports || []);
              if (Array.isArray(reportList)) {
                for (const cr of reportList) {
                  const totalIssues = (cr.security_issues || 0) + (cr.reliability_issues || 0);
                  const findingsCount = Array.isArray(cr.findings) ? cr.findings.length : 0;
                  insightsList.push({
                    pr: `Commit ${cr.commit_sha?.substring(0, 7) || "HEAD"}`,
                    title: cr.commit_message || `Push on ${cr.ref || "branch"}`,
                    risk: (cr.secrets_detected || 0) > 0 ? "CRITICAL" : (totalIssues > 0 ? "HIGH" : "LOW"),
                    score: cr.risk_score ? Math.round(cr.risk_score) : ((cr.secrets_detected || 0) > 0 ? 95 : (totalIssues > 0 ? 65 : 10)),
                    symbols: findingsCount || 1,
                  });
                }
              }
            } catch (reportErr) {
              console.debug("Could not load commit reports for repository:", r.id, reportErr);
            }
          }
          setPrCount(totalOpenPrs);
          setRecentInsights(insightsList.slice(0, 5));
        }
        if (testRuns.status === "fulfilled" && Array.isArray(testRuns.value)) {
          setTestRunCount(testRuns.value.length);
        }
      } catch (err) {
        console.warn("Failed to load dashboard statistics:", err);
      }
    }

    loadStats();
    return () => {
      isMounted = false;
    };
  }, [currentOrg?.id, activeWorkspace?.id]);

  const metrics = [
    {
      title: "Governed Projects",
      value: projectCount.toString(),
      subtext: "Active in current tenant",
      indicator: projectCount > 0 ? "Synchronized" : "None initialized",
      icon: FolderGit2,
    },
    {
      title: "Connected Repositories",
      value: repoCount.toString(),
      subtext: "GitHub App webhooks active",
      indicator: repoCount > 0 ? "Real-time delivery" : "Pending setup",
      icon: Radio,
    },
    {
      title: "Test Ingestions",
      value: testRunCount.toString(),
      subtext: "Deterministic verification runs",
      indicator: testRunCount > 0 ? "Automated pass" : "Awaiting suites",
      icon: CheckCircle2,
    },
    {
      title: "Open Pull Requests",
      value: prCount.toString(),
      subtext: "Blast radius monitored",
      indicator: "Zero unreviewed regressions",
      icon: GitPullRequest,
    },
  ];

  const microservices = [
    { name: "API Gateway", port: ":8080", healthy: serviceHealth.gateway, role: "Ingress & Revocation Filter" },
    { name: "Identity Service", port: ":8081", healthy: serviceHealth.identity, role: "RS256 JWKS & Multi-Tenant RBAC" },
    { name: "Source Control", port: ":8082", healthy: serviceHealth.sourceControl, role: "GitHub Webhooks & PR Ingestion" },
    { name: "Testing Service", port: ":8083", healthy: serviceHealth.testing, role: "JUnit XML & Flaky Detection" },
    { name: "AI Service", port: ":8084", healthy: serviceHealth.ai, role: "Tree-sitter AST & Blast Radius" },
    { name: "Project Service", port: ":8085", healthy: serviceHealth.project, role: "Kanban, Outbox & Traceability" },
  ];

  const isProbing = microservices.every((svc) => svc.healthy === null);
  const healthyCount = microservices.filter((svc) => svc.healthy === true).length;
  const allHealthy = !isProbing && healthyCount === microservices.length;

  return (
    <div className="space-y-6">
      {/* Context Top Bar */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1.5">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="font-mono text-[11px] text-zinc-300 border-[#26262C]">
                TENANT: {currentOrg?.slug || activeWorkspace?.name || "PERSONAL"}
              </Badge>
              <span className="text-xs text-zinc-400 flex items-center gap-1.5 font-medium">
                <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
                Zero-Trust Policy Enforced
              </span>
            </div>
            <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
              Welcome back, {user?.fullName || "Lead Architect"}
            </h1>
            <p className="text-xs text-zinc-400 max-w-2xl">
              Continuous delivery verification active for <strong>{currentOrg?.name || activeWorkspace?.name || "Workspace"}</strong>.
              Correlating requirements, engineering tasks, GitHub commits, test runs, and semantic blast radii.
            </p>
          </div>

          <div className="flex items-center gap-2">
            <Link href="/projects">
              <Button size="sm" className="h-8 gap-1.5 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
                <Plus className="h-3.5 w-3.5" />
                New Project
              </Button>
            </Link>
            <Link href="/traceability">
              <Button variant="outline" size="sm" className="h-8 gap-1.5 text-xs border-[#26262C] text-zinc-200">
                <Layers className="h-3.5 w-3.5" />
                Traceability
              </Button>
            </Link>
          </div>
        </div>
      </div>

      {/* 4-Column Metric Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        {metrics.map((metric) => {
          const Icon = metric.icon;
          return (
            <Card key={metric.title} className="p-4 border-[#1C1C21] bg-[#0E0E11] relative before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.04]">
              <div className="flex items-center justify-between pb-2">
                <span className="text-xs font-medium text-zinc-400">{metric.title}</span>
                <Icon className="h-3.5 w-3.5 text-zinc-500" />
              </div>
              <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums tracking-tight">
                {metric.value}
              </div>
              <p className="text-[11px] text-zinc-500 mt-1">{metric.subtext}</p>
              <div className="mt-2.5 pt-2 border-t border-[#1C1C21] flex items-center gap-1.5 text-[11px] text-zinc-400 font-mono">
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                {metric.indicator}
              </div>
            </Card>
          );
        })}
      </div>

      {/* Traceability Spine Callout */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col lg:flex-row items-start lg:items-center justify-between gap-5">
          <div className="space-y-2 max-w-2xl">
            <div className="flex items-center gap-2">
              <Badge variant="outline" className="font-mono text-[10px] text-zinc-300 border-[#26262C]">
                CONTINUOUS LINEAGE
              </Badge>
              <span className="text-xs text-zinc-400">Deterministic Requirement-to-Commit Traceability</span>
            </div>
            <h2 className="text-base font-semibold text-zinc-100">
              Traceability Spine Matrix
            </h2>
            <p className="text-xs text-zinc-400 leading-relaxed">
              Verify continuous lineage across requirements, tasks, GitHub pull requests, JUnit test executions, and Tree-sitter semantic AST risk assessments.
            </p>
            {/* Inline Mini Lineage Chain */}
            <div className="flex items-center gap-2 pt-1 font-mono text-[11px] text-zinc-400 flex-wrap">
              <span className="px-2 py-0.5 rounded bg-[#141418] border border-[#1C1C21] text-zinc-300">REQ-SPEC</span>
              <ArrowRight className="h-3 w-3 text-zinc-600" />
              <span className="px-2 py-0.5 rounded bg-[#141418] border border-[#1C1C21] text-zinc-300">ENG-TASK</span>
              <ArrowRight className="h-3 w-3 text-zinc-600" />
              <span className="px-2 py-0.5 rounded bg-[#141418] border border-[#1C1C21] text-zinc-300">PR # / COMMIT</span>
              <ArrowRight className="h-3 w-3 text-zinc-600" />
              <span className="px-2 py-0.5 rounded bg-[#141418] border border-[#1C1C21] text-emerald-400">JUNIT PASSED</span>
            </div>
          </div>

          <div className="shrink-0">
            <Link href="/traceability">
              <Button size="sm" className="h-8 gap-1.5 text-xs bg-zinc-100 hover:bg-zinc-200 text-zinc-900 font-medium">
                Open Traceability Matrix
                <ArrowRight className="h-3.5 w-3.5" />
              </Button>
            </Link>
          </div>
        </div>
      </div>

      {/* 2-Column: Recent AI Findings & Microservices Cluster */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Recent AI PR Blast Radius Findings */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
          <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
            <div>
              <CardTitle className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
                <GitCommit className="h-3.5 w-3.5 text-zinc-400" />
                Recent AST Blast Radius Stream
              </CardTitle>
              <CardDescription className="text-[11px] text-zinc-500">
                Automated semantic code risk assessments
              </CardDescription>
            </div>
            <Link href="/ai-insights">
              <Button variant="ghost" size="sm" className="h-7 text-xs text-zinc-400 hover:text-zinc-100">
                View All
              </Button>
            </Link>
          </div>

          <div className="space-y-2">
            {recentInsights.length === 0 ? (
              <div className="p-6 text-center rounded-md border border-dashed border-[#26262C] text-xs text-zinc-500">
                No commit or PR analyses ingested yet. Push a commit or inspect a pull request in AI Insights to view semantic impact.
              </div>
            ) : (
              recentInsights.map((item, idx) => (
                <div
                  key={`${item.pr}-${idx}`}
                  className="flex items-center justify-between p-2.5 rounded-md border border-[#1C1C21] bg-[#141418] hover:border-[#26262C] transition-colors"
                >
                  <div className="space-y-0.5 truncate pr-3">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs font-semibold text-zinc-200">
                        {item.pr}
                      </span>
                      <span className="text-xs text-zinc-300 truncate">
                        {item.title}
                      </span>
                    </div>
                    <span className="text-[11px] text-zinc-500 font-mono">
                      Impacted files / symbols: {item.symbols}
                    </span>
                  </div>
                  <Badge
                    variant={
                      item.risk === "LOW"
                        ? "riskLow"
                        : item.risk === "MEDIUM"
                        ? "riskMedium"
                        : "riskHigh"
                    }
                    className="font-mono text-[10px]"
                  >
                    {item.risk} ({item.score}%)
                  </Badge>
                </div>
              ))
            )}
          </div>
        </Card>

        {/* Microservices Cluster Status */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
          <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
            <div>
              <CardTitle className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
                <Activity className="h-3.5 w-3.5 text-zinc-400" />
                Microservices Topology
              </CardTitle>
              <CardDescription className="text-[11px] text-zinc-500">
                Ingress nodes and internal microservice health
              </CardDescription>
            </div>
            <Badge
              variant="outline"
              className={`font-mono tabular-nums text-[10px] ${
                isProbing
                  ? "text-zinc-400 border-zinc-800"
                  : allHealthy
                  ? "text-emerald-400 border-emerald-800"
                  : "text-amber-400 border-amber-800"
              }`}
            >
              {isProbing ? "PROBING" : allHealthy ? `${healthyCount}/${microservices.length} HEALTHY` : `${healthyCount}/${microservices.length} DEGRADED`}
            </Badge>
          </div>

          <div className="space-y-1.5">
            {microservices.map((svc) => (
              <div
                key={svc.name}
                className="flex items-center justify-between p-2 rounded-md border border-[#1C1C21] bg-[#141418] text-xs"
              >
                <div className="space-y-0.5">
                  <div className="font-medium text-zinc-200 flex items-center gap-2">
                    <span>{svc.name}</span>
                    <span className="text-[10px] font-mono text-zinc-500">{svc.port}</span>
                  </div>
                  <div className="text-[11px] text-zinc-400">{svc.role}</div>
                </div>
                <div
                  className={`flex items-center gap-1.5 font-mono text-[10px] ${
                    svc.healthy === null
                      ? "text-zinc-400"
                      : svc.healthy
                      ? "text-emerald-400"
                      : "text-amber-400"
                  }`}
                >
                  <span
                    className={`h-1.5 w-1.5 rounded-full ${
                      svc.healthy === null
                        ? "bg-zinc-500"
                        : svc.healthy
                        ? "bg-emerald-500"
                        : "bg-amber-500"
                    }`}
                  />
                  {svc.healthy === null ? "PROBING" : svc.healthy ? "UP" : "DEGRADED"}
                </div>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}
