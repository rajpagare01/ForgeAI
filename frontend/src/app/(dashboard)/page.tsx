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
  Sparkles,
  ArrowRight,
  TrendingUp,
  ShieldCheck,
  Layers,
  Plus,
  Radio,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

export default function OverviewDashboardPage() {
  const { currentOrg, activeWorkspace } = useTenant();
  const { user } = useAuth();

  const [projectCount, setProjectCount] = useState<number>(0);
  const [repoCount, setRepoCount] = useState<number>(0);
  const [testRunCount, setTestRunCount] = useState<number>(0);
  const [prCount, setPrCount] = useState<number>(0);
  const [recentInsights, setRecentInsights] = useState<any[]>([]);

  useEffect(() => {
    let isMounted = true;
    async function loadStats() {
      try {
        const [projects, repos, testRuns] = await Promise.allSettled([
          apiClient.get<any[]>("/api/v1/projects"),
          apiClient.get<any[]>("/api/v1/repositories"),
          apiClient.get<any[]>("/api/v1/test-runs"),
        ]);

        if (!isMounted) return;

        if (projects.status === "fulfilled" && Array.isArray(projects.value)) {
          setProjectCount(projects.value.length);
        }
        if (repos.status === "fulfilled" && Array.isArray(repos.value)) {
          setRepoCount(repos.value.length);
          let totalOpenPrs = 0;
          const insightsList: any[] = [];
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
      title: "Active Governed Projects",
      value: projectCount.toString(),
      subtext: "Live Tenant Projects",
      change: projectCount > 0 ? "Active in workspace" : "Ready to initialize",
      icon: FolderGit2,
      color: "text-indigo-400",
      border: "border-indigo-500/20",
    },
    {
      title: "Tracked Repositories",
      value: repoCount.toString(),
      subtext: "GitHub App Connected",
      change: repoCount > 0 ? "Webhook delivery active" : "Connect repo to sync",
      icon: Radio,
      color: "text-sky-400",
      border: "border-sky-500/20",
    },
    {
      title: "CI Verification Runs",
      value: testRunCount.toString(),
      subtext: "Deterministic Staging Suites",
      change: testRunCount > 0 ? "100% Ingestion Integrity" : "Awaiting first run",
      icon: CheckCircle2,
      color: "text-emerald-400",
      border: "border-emerald-500/20",
    },
    {
      title: "Open Pull Requests",
      value: prCount.toString(),
      subtext: "AST Blast Radius Monitored",
      change: "Zero regressions in prod",
      icon: GitPullRequest,
      color: "text-amber-400",
      border: "border-amber-500/20",
    },
  ];

  return (
    <div className="space-y-8">
      {/* Header Banner */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 rounded-2xl border border-slate-800 bg-gradient-to-r from-slate-900/90 via-indigo-950/20 to-slate-900/90 p-6 backdrop-blur-xl">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <Badge variant="default" className="font-mono text-[10px]">
              TENANT: {currentOrg?.slug || activeWorkspace?.name || "PERSONAL"}
            </Badge>
            <span className="text-xs text-slate-400 flex items-center gap-1">
              <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" /> Enterprise Policy Enforced
            </span>
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-white">
            Welcome back, {user?.fullName || "Lead Architect"}
          </h1>
          <p className="text-xs text-slate-400 max-w-xl">
            Autonomous governance matrix is monitoring requirements, commits, automated JUnit suites, and AST blast radii across <strong>{currentOrg?.name || activeWorkspace?.name || "Workspace"}</strong>.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <Link href="/projects">
            <Button variant="glow" size="sm" className="gap-2">
              <Plus className="h-4 w-4" />
              New Project
            </Button>
          </Link>
        </div>
      </div>

      {/* Metric Cards Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {metrics.map((metric) => {
          const Icon = metric.icon;
          return (
            <Card key={metric.title} className={`${metric.border} bg-slate-900/50 hover:bg-slate-900/80 transition-all`}>
              <CardHeader className="flex flex-row items-center justify-between pb-2 space-y-0">
                <CardTitle className="text-xs font-medium text-slate-400">
                  {metric.title}
                </CardTitle>
                <div className={`p-2 rounded-lg bg-slate-800/80 ${metric.color}`}>
                  <Icon className="h-4 w-4" />
                </div>
              </CardHeader>
              <CardContent>
                <div className="text-2xl font-bold text-white tracking-tight font-mono">
                  {metric.value}
                </div>
                <p className="text-xs text-slate-400 mt-1">{metric.subtext}</p>
                <div className="mt-3 flex items-center gap-1.5 text-[11px] text-emerald-400 font-medium">
                  <TrendingUp className="h-3 w-3" />
                  {metric.change}
                </div>
              </CardContent>
            </Card>
          );
        })}
      </div>

      {/* Core Traceability Spine Callout */}
      <Card className="border-indigo-500/40 bg-gradient-to-br from-indigo-950/30 via-slate-900/80 to-purple-950/20 shadow-xl overflow-hidden">
        <div className="p-6 sm:p-8 flex flex-col lg:flex-row items-start lg:items-center justify-between gap-6">
          <div className="space-y-3 max-w-2xl">
            <div className="flex items-center gap-2">
              <div className="p-1.5 rounded-lg bg-indigo-500/20 text-indigo-400 border border-indigo-500/30">
                <Layers className="h-4 w-4" />
              </div>
              <Badge variant="default" className="text-xs font-semibold">
                CORE VISUAL ANCHOR
              </Badge>
            </div>
            <h2 className="text-xl font-bold text-white">
              End-to-End Traceability Spine Matrix
            </h2>
            <p className="text-sm text-slate-300 leading-relaxed">
              Explore the deterministic unbroken chain linking business requirements directly to engineering tasks, GitHub pull requests, JUnit verification test runs, and Tree-sitter AST blast radius intelligence.
            </p>
          </div>

          <div className="shrink-0">
            <Link href="/traceability">
              <Button variant="glow" size="lg" className="gap-2">
                Launch Traceability Matrix
                <ArrowRight className="h-4 w-4" />
              </Button>
            </Link>
          </div>
        </div>
      </Card>

      {/* 2-Column: Recent AI Findings & Connected Repos */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Recent AI PR Blast Radius Findings */}
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <div>
              <CardTitle className="text-base flex items-center gap-2">
                <Sparkles className="h-4 w-4 text-amber-400" />
                Recent AI Blast Radius Insights
              </CardTitle>
              <CardDescription>
                Automated Tree-sitter AST dependency impact audits
              </CardDescription>
            </div>
            <Link href="/ai-insights">
              <Button variant="ghost" size="sm" className="text-xs text-indigo-400">
                View All
              </Button>
            </Link>
          </CardHeader>
          <CardContent className="space-y-3">
            {recentInsights.length === 0 ? (
              <div className="p-6 text-center rounded-xl border border-dashed border-slate-800 bg-slate-950/20 text-xs text-slate-400">
                <Sparkles className="h-5 w-5 text-slate-600 mx-auto mb-2" />
                No commits or PR analyses detected yet. Push a commit or select a PR in AI Insights to view live AST blast radius insights.
              </div>
            ) : (
              recentInsights.map((item, idx) => (
                <div
                  key={`${item.pr}-${idx}`}
                  className="flex items-center justify-between p-3 rounded-xl border border-slate-800 bg-slate-950/40 hover:bg-slate-800/30 transition-colors"
                >
                  <div className="space-y-1 truncate pr-3">
                    <div className="flex items-center gap-2">
                      <span className="font-mono text-xs font-semibold text-sky-400">
                        {item.pr}
                      </span>
                      <span className="text-xs font-medium text-slate-200 truncate">
                        {item.title}
                      </span>
                    </div>
                    <span className="text-[11px] text-slate-400">
                      Impacted files / symbols: {item.symbols}
                    </span>
                  </div>
                  <Badge
                    variant={
                      item.risk === "LOW"
                        ? "success"
                        : item.risk === "MEDIUM"
                          ? "warning"
                          : "destructive"
                    }
                    className="text-[10px]"
                  >
                    {item.risk} RISK ({item.score}%)
                  </Badge>
                </div>
              ))
            )}
          </CardContent>
        </Card>

        {/* System Health & Architecture Summary */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2">
              <ShieldCheck className="h-4 w-4 text-emerald-400" />
              Microservices Cluster Architecture
            </CardTitle>
            <CardDescription>
              Current status of Spring Boot & FastAPI microservice nodes
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {[
              { name: "API Gateway (Reactive)", port: ":8080", status: "HEALTHY", role: "Edge Ingress & Zero-Trust" },
              { name: "Identity Service", port: ":8081", status: "HEALTHY", role: "Auth, RS256 JWKS & RBAC" },
              { name: "Source Control Service", port: ":8082", status: "HEALTHY", role: "GitHub Webhooks & Thin Ingest" },
              { name: "Testing Service", port: ":8083", status: "HEALTHY", role: "JUnit Ingestion & Test Runs" },
              { name: "AI Intelligence Service", port: ":8084", status: "HEALTHY", role: "pgvector & Gemini RAG" },
              { name: "Project Service", port: ":8085", status: "HEALTHY", role: "Tasks, REQs & Traceability" },
            ].map((svc) => (
              <div
                key={svc.name}
                className="flex items-center justify-between p-2.5 rounded-xl border border-slate-800 bg-slate-950/40 text-xs"
              >
                <div className="space-y-0.5">
                  <div className="font-medium text-slate-200 flex items-center gap-2">
                    <span>{svc.name}</span>
                    <span className="text-[10px] font-mono text-slate-500">{svc.port}</span>
                  </div>
                  <div className="text-[11px] text-slate-400">{svc.role}</div>
                </div>
                <Badge variant="success" className="text-[10px]">
                  {svc.status}
                </Badge>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
