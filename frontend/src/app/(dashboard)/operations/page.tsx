"use client";

import React, { useState, useEffect, useCallback } from "react";
import { useTenant } from "@/lib/tenant-context";
import { useAuth } from "@/lib/auth-context";
import { apiClient, formatUserError } from "@/lib/api-client";
import {
  Activity,
  ShieldCheck,
  ShieldAlert,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  XCircle,
  GitPullRequest,
  Cpu,
  RotateCcw,
  Clock,
  Layers,
  Zap,
  Server,
  Lock,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface OutboxStats {
  pending: number;
  retrying: number;
  deadLetter: number;
  totalUnpublished: number;
  rateLimitRemaining: number;
}

interface DeadLetterEvent {
  id: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  attemptCount: number;
  maxAttempts: number;
  status: string;
  lastError?: string;
  deadLetterAt?: string;
  createdAt: string;
}

interface GitHubStats {
  activeInstallations: number;
  quarantinedInstallations: number;
  suspendedInstallations: number;
  rateLimitRemaining: number;
  rateLimitReset: number;
}

interface CircuitBreakerItem {
  serviceName: string;
  state: "CLOSED" | "HALF_OPEN" | "OPEN";
  isOpen: boolean;
  consecutiveFailures: number;
  openedAt?: string;
  resetTimeoutSeconds: number;
}

interface TraceabilityStats {
  syncTotal: number;
  syncFailures: number;
  degradedTotal: number;
  projectsProcessed: number;
  gapsDetected: number;
}

interface AIStats {
  ai_analysis_total: number;
  ai_analysis_success_total: number;
  ai_analysis_failure_total: number;
  ai_provider_failures: number;
  ai_fallback_total: number;
  latency_p50_ms: number;
  latency_p95_ms: number;
  latency_p99_ms: number;
}

interface AuditEventItem {
  id: string;
  organizationId: string;
  actorUserId?: string;
  eventType: string;
  entityType: string;
  entityId: string;
  action: string;
  outcome: string;
  correlationId?: string;
  traceId?: string;
  createdAt: string;
  metadata?: Record<string, any>;
}

export default function OperationsDashboardPage() {
  const { currentOrg, activeWorkspace, isLoading: isTenantLoading } = useTenant();
  const { user } = useAuth();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const [outboxStats, setOutboxStats] = useState<OutboxStats | null>(null);
  const [deadLetters, setDeadLetters] = useState<DeadLetterEvent[]>([]);
  const [githubStats, setGithubStats] = useState<GitHubStats | null>(null);
  const [circuitBreakers, setCircuitBreakers] = useState<CircuitBreakerItem[]>([]);
  const [traceabilityStats, setTraceabilityStats] = useState<TraceabilityStats | null>(null);
  const [aiStats, setAiStats] = useState<AIStats | null>(null);
  const [aiHealth, setAiHealth] = useState<any>(null);
  const [auditEvents, setAuditEvents] = useState<AuditEventItem[]>([]);
  const [replayingId, setReplayingId] = useState<string | null>(null);
  const [replaySuccess, setReplaySuccess] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    if (isTenantLoading) return;
    if (!currentOrg?.id && !activeWorkspace?.id) {
      setLoading(false);
      setRefreshing(false);
      return;
    }
    try {
      setErrorMsg(null);
      const [
        outboxRes,
        deadLetterRes,
        githubRes,
        circuitRes,
        traceRes,
        aiMetricsRes,
        aiHealthRes,
        auditRes,
      ] = await Promise.allSettled([
        apiClient.get<OutboxStats>("/api/v1/operations/outbox/stats"),
        apiClient.get<DeadLetterEvent[]>("/api/v1/operations/outbox/dead-letter"),
        apiClient.get<GitHubStats>("/api/v1/operations/github/stats"),
        apiClient.get<CircuitBreakerItem[]>("/api/v1/operations/circuit-breakers"),
        apiClient.get<TraceabilityStats>("/api/v1/operations/traceability/stats"),
        apiClient.get<AIStats>("/api/v1/ai/metrics"),
        apiClient.get<any>("/api/v1/ai/health"),
        apiClient.get<AuditEventItem[]>("/api/v1/operations/audit-events"),
      ]);

      if (outboxRes.status === "fulfilled" && outboxRes.value) {
        setOutboxStats(outboxRes.value);
      }
      if (deadLetterRes.status === "fulfilled" && Array.isArray(deadLetterRes.value)) {
        setDeadLetters(deadLetterRes.value);
      }
      if (githubRes.status === "fulfilled" && githubRes.value) {
        setGithubStats(githubRes.value);
      }
      if (circuitRes.status === "fulfilled" && Array.isArray(circuitRes.value)) {
        setCircuitBreakers(circuitRes.value);
      }
      if (traceRes.status === "fulfilled" && traceRes.value) {
        setTraceabilityStats(traceRes.value);
      }
      if (aiMetricsRes.status === "fulfilled" && aiMetricsRes.value) {
        setAiStats(aiMetricsRes.value);
      }
      if (aiHealthRes.status === "fulfilled" && aiHealthRes.value) {
        setAiHealth(aiHealthRes.value);
      }
      if (auditRes.status === "fulfilled" && Array.isArray(auditRes.value)) {
        setAuditEvents(auditRes.value);
      }
    } catch (err: any) {
      console.error("Operations data fetching error:", err);
      setErrorMsg(err.message || "Failed to load operational telemetry");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [currentOrg?.id, activeWorkspace?.id, isTenantLoading]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleReplay = async (eventId: string) => {
    if (!currentOrg?.id && !activeWorkspace?.id) return;
    setReplayingId(eventId);
    setReplaySuccess(null);
    try {
      await apiClient.post(`/api/v1/operations/outbox/events/${eventId}/replay`, {});
      setReplaySuccess(`Successfully scheduled replay for outbox event ${eventId}.`);
      await fetchData();
    } catch (err: any) {
      setErrorMsg(formatUserError(err, "Unable to replay the outbox event right now."));
    } finally {
      setReplayingId(null);
    }
  };

  return (
    <div className="space-y-8 p-6 max-w-7xl mx-auto">
      {/* Top Header */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-800 pb-5">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold tracking-tight text-white flex items-center gap-2">
              <Activity className="h-6 w-6 text-indigo-400" />
              Production Observability & Governance
            </h1>
            <Badge variant="outline" className="border-indigo-500/40 text-indigo-400 bg-indigo-500/10">
              Live Telemetry
            </Badge>
          </div>
          <p className="text-sm text-slate-400 mt-1">
            Real-time health indicators, circuit breaker resilience, outbox reliability, and immutable audit logs.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Badge variant="default" className="bg-slate-800 text-slate-200 border border-slate-700">
            Workspace: {currentOrg?.name || activeWorkspace?.name || "Personal Workspace"}
          </Badge>
          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              setRefreshing(true);
              fetchData();
            }}
            disabled={refreshing}
            className="border-slate-700 text-slate-200 hover:bg-slate-800"
          >
            <RefreshCw className={`h-4 w-4 mr-2 ${refreshing ? "animate-spin" : ""}`} />
            Refresh
          </Button>
        </div>
      </div>

      {errorMsg && (
        <div className="rounded-lg border border-red-500/40 bg-red-950/20 p-4 text-sm text-red-400 flex items-center gap-3">
          <AlertTriangle className="h-5 w-5 flex-shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {replaySuccess && (
        <div className="rounded-lg border border-emerald-500/40 bg-emerald-950/20 p-4 text-sm text-emerald-400 flex items-center gap-3">
          <CheckCircle2 className="h-5 w-5 flex-shrink-0" />
          <span>{replaySuccess}</span>
        </div>
      )}

      {/* KPI Overview Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Outbox Health */}
        <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
          <CardHeader className="pb-2">
            <CardDescription className="text-slate-400 flex items-center justify-between">
              Transactional Outbox
              <Layers className="h-4 w-4 text-indigo-400" />
            </CardDescription>
            <CardTitle className="text-2xl font-bold text-white">
              {outboxStats ? outboxStats.totalUnpublished : 0}
              <span className="text-xs font-normal text-slate-400 ml-2">unprocessed</span>
            </CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-slate-400 space-y-1">
            <div className="flex justify-between">
              <span>Pending:</span>
              <span className="font-semibold text-slate-200">{outboxStats?.pending ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span>Retrying:</span>
              <span className="font-semibold text-amber-400">{outboxStats?.retrying ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span>Dead Letter:</span>
              <span className={`font-semibold ${outboxStats && outboxStats.deadLetter > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                {outboxStats?.deadLetter ?? 0}
              </span>
            </div>
          </CardContent>
        </Card>

        {/* GitHub API & Webhooks */}
        <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
          <CardHeader className="pb-2">
            <CardDescription className="text-slate-400 flex items-center justify-between">
              GitHub Integration
              <GitPullRequest className="h-4 w-4 text-purple-400" />
            </CardDescription>
            <CardTitle className="text-2xl font-bold text-white">
              {githubStats ? githubStats.activeInstallations : 0}
              <span className="text-xs font-normal text-slate-400 ml-2">active apps</span>
            </CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-slate-400 space-y-1">
            <div className="flex justify-between">
              <span>Rate Limit Left:</span>
              <span className="font-semibold text-slate-200">{githubStats?.rateLimitRemaining ?? 5000}</span>
            </div>
            <div className="flex justify-between">
              <span>Quarantined:</span>
              <span className="font-semibold text-amber-400">{githubStats?.quarantinedInstallations ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span>Suspended:</span>
              <span className="font-semibold text-rose-400">{githubStats?.suspendedInstallations ?? 0}</span>
            </div>
          </CardContent>
        </Card>

        {/* Traceability Matrix */}
        <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
          <CardHeader className="pb-2">
            <CardDescription className="text-slate-400 flex items-center justify-between">
              Traceability Health
              <ShieldCheck className="h-4 w-4 text-emerald-400" />
            </CardDescription>
            <CardTitle className="text-2xl font-bold text-white">
              {traceabilityStats ? traceabilityStats.syncTotal : 0}
              <span className="text-xs font-normal text-slate-400 ml-2">sync cycles</span>
            </CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-slate-400 space-y-1">
            <div className="flex justify-between">
              <span>Projects Evaluated:</span>
              <span className="font-semibold text-slate-200">{traceabilityStats?.projectsProcessed ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span>Sync Failures:</span>
              <span className={`font-semibold ${traceabilityStats && traceabilityStats.syncFailures > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                {traceabilityStats?.syncFailures ?? 0}
              </span>
            </div>
            <div className="flex justify-between">
              <span>Gaps Detected:</span>
              <span className="font-semibold text-amber-400">{traceabilityStats?.gapsDetected ?? 0}</span>
            </div>
          </CardContent>
        </Card>

        {/* AI Orchestrator */}
        <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
          <CardHeader className="pb-2">
            <CardDescription className="text-slate-400 flex items-center justify-between">
              AI Service & Fallback
              <Cpu className="h-4 w-4 text-sky-400" />
            </CardDescription>
            <CardTitle className="text-2xl font-bold text-white">
              {aiStats ? aiStats.ai_analysis_total : 0}
              <span className="text-xs font-normal text-slate-400 ml-2">analyses</span>
            </CardTitle>
          </CardHeader>
          <CardContent className="text-xs text-slate-400 space-y-1">
            <div className="flex justify-between">
              <span>Success Rate:</span>
              <span className="font-semibold text-emerald-400">
                {aiStats && aiStats.ai_analysis_total > 0
                  ? `${Math.round((aiStats.ai_analysis_success_total / aiStats.ai_analysis_total) * 100)}%`
                  : "100%"}
              </span>
            </div>
            <div className="flex justify-between">
              <span>Fallback Invocations:</span>
              <span className="font-semibold text-amber-400">{aiStats?.ai_fallback_total ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span>p95 Latency:</span>
              <span className="font-semibold text-slate-200">{aiStats?.latency_p95_ms ?? 0} ms</span>
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Circuit Breakers & Dependency Resilience */}
      <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
        <CardHeader>
          <CardTitle className="text-lg font-semibold text-white flex items-center gap-2">
            <Zap className="h-5 w-5 text-amber-400" />
            Circuit Breaker Resilience Status
          </CardTitle>
          <CardDescription className="text-slate-400">
            Automated fast-fail thresholds protecting the system against downstream cascading degradation.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {circuitBreakers.length === 0 ? (
            <div className="text-sm text-slate-400 py-3 text-center">
              All registered circuit breakers healthy (CLOSED, 0 failures).
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              {circuitBreakers.map((cb) => (
                <div
                  key={cb.serviceName}
                  className="rounded-lg border border-slate-800 bg-slate-950/60 p-4 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-sm text-white">{cb.serviceName}</span>
                    <Badge
                      variant="outline"
                      className={
                        cb.state === "CLOSED"
                          ? "border-emerald-500/40 text-emerald-400 bg-emerald-500/10"
                          : cb.state === "HALF_OPEN"
                          ? "border-amber-500/40 text-amber-400 bg-amber-500/10"
                          : "border-rose-500/40 text-rose-400 bg-rose-500/10"
                      }
                    >
                      {cb.state}
                    </Badge>
                  </div>
                  <div className="text-xs text-slate-400 flex justify-between">
                    <span>Consecutive Failures:</span>
                    <span className="text-slate-200 font-mono">{cb.consecutiveFailures}</span>
                  </div>
                  <div className="text-xs text-slate-400 flex justify-between">
                    <span>Reset Timeout:</span>
                    <span className="text-slate-200 font-mono">{cb.resetTimeoutSeconds}s</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </CardContent>
      </Card>

      {/* Dead-Letter Outbox Queue & Replay Action */}
      <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
        <CardHeader className="flex flex-row items-center justify-between">
          <div>
            <CardTitle className="text-lg font-semibold text-white flex items-center gap-2">
              <RotateCcw className="h-5 w-5 text-rose-400" />
              Poison-Pill Dead-Letter Queue
            </CardTitle>
            <CardDescription className="text-slate-400">
              Events that exceeded max retry attempts (5) preserved with full audit context. Tenant-scoped replay available.
            </CardDescription>
          </div>
          <Badge variant="outline" className="border-slate-700 text-slate-300">
            {deadLetters.length} Dead Letters
          </Badge>
        </CardHeader>
        <CardContent>
          {deadLetters.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-800 p-8 text-center text-slate-400">
              <CheckCircle2 className="mx-auto h-8 w-8 text-emerald-400 mb-2" />
              <p className="text-sm font-medium text-slate-300">No Poison-Pill Events</p>
              <p className="text-xs text-slate-400 mt-1">All outbox events published cleanly without terminal dead-lettering.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-300">
                <thead className="bg-slate-950/80 text-xs uppercase text-slate-400 border-b border-slate-800">
                  <tr>
                    <th className="py-2.5 px-3">Event Type</th>
                    <th className="py-2.5 px-3">Aggregate ID</th>
                    <th className="py-2.5 px-3">Attempts</th>
                    <th className="py-2.5 px-3">Dead Letter At</th>
                    <th className="py-2.5 px-3">Last Error</th>
                    <th className="py-2.5 px-3 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {deadLetters.map((event) => (
                    <tr key={event.id} className="hover:bg-slate-800/30">
                      <td className="py-2.5 px-3 font-mono text-xs text-indigo-300">{event.eventType}</td>
                      <td className="py-2.5 px-3 font-mono text-xs text-slate-400">{event.aggregateId}</td>
                      <td className="py-2.5 px-3 text-xs">{event.attemptCount} / {event.maxAttempts}</td>
                      <td className="py-2.5 px-3 text-xs text-slate-400">
                        {event.deadLetterAt ? new Date(event.deadLetterAt).toLocaleTimeString() : "N/A"}
                      </td>
                      <td className="py-2.5 px-3 text-xs text-rose-300 max-w-xs truncate" title={event.lastError}>
                        {event.lastError || "Unknown failure"}
                      </td>
                      <td className="py-2.5 px-3 text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleReplay(event.id)}
                          disabled={replayingId === event.id}
                          className="h-7 text-xs border-indigo-500/40 text-indigo-300 hover:bg-indigo-500/20"
                        >
                          {replayingId === event.id ? "Replaying..." : "Safe Replay"}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Immutable Audit Trail */}
      <Card className="border-slate-800 bg-slate-900/60 backdrop-blur-sm">
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-lg font-semibold text-white flex items-center gap-2">
                <Lock className="h-5 w-5 text-indigo-400" />
                Immutable Governance Audit Log
              </CardTitle>
              <CardDescription className="text-slate-400">
                Append-only audit trail strictly partitioned by organization. PostgreSQL-enforced immutability.
              </CardDescription>
            </div>
            <Badge variant="outline" className="border-emerald-500/40 text-emerald-400 bg-emerald-500/10">
              Immutability Trigger Active
            </Badge>
          </div>
        </CardHeader>
        <CardContent>
          {auditEvents.length === 0 ? (
            <div className="rounded-lg border border-dashed border-slate-800 p-8 text-center text-slate-400">
              <Clock className="mx-auto h-8 w-8 text-slate-400 mb-2" />
              <p className="text-sm font-medium text-slate-300">No Audit Events Recorded Yet</p>
              <p className="text-xs text-slate-400 mt-1">Actions such as link creation, snapshotting, and replays will appear here.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm text-slate-300">
                <thead className="bg-slate-950/80 text-xs uppercase text-slate-400 border-b border-slate-800">
                  <tr>
                    <th className="py-2.5 px-3">Timestamp</th>
                    <th className="py-2.5 px-3">Event Type</th>
                    <th className="py-2.5 px-3">Entity</th>
                    <th className="py-2.5 px-3">Action</th>
                    <th className="py-2.5 px-3">Outcome</th>
                    <th className="py-2.5 px-3">Trace ID</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60">
                  {auditEvents.map((ae) => (
                    <tr key={ae.id} className="hover:bg-slate-800/30">
                      <td className="py-2.5 px-3 text-xs text-slate-400">
                        {new Date(ae.createdAt).toLocaleString()}
                      </td>
                      <td className="py-2.5 px-3 font-mono text-xs text-indigo-300">{ae.eventType}</td>
                      <td className="py-2.5 px-3 text-xs text-slate-300">
                        {ae.entityType}:{ae.entityId.slice(0, 8)}...
                      </td>
                      <td className="py-2.5 px-3 text-xs font-semibold">{ae.action}</td>
                      <td className="py-2.5 px-3 text-xs">
                        <Badge
                          variant="outline"
                          className={
                            ae.outcome === "SUCCESS"
                              ? "border-emerald-500/40 text-emerald-400 bg-emerald-500/10"
                              : "border-rose-500/40 text-rose-400 bg-rose-500/10"
                          }
                        >
                          {ae.outcome}
                        </Badge>
                      </td>
                      <td className="py-2.5 px-3 font-mono text-xs text-slate-400">
                        {ae.traceId ? ae.traceId.slice(0, 16) + "..." : "N/A"}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
