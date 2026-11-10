"use client";

import React, { useState, useEffect, useCallback } from "react";
import { useTenant } from "@/lib/tenant-context";
import { useAuth } from "@/lib/auth-context";
import { apiClient, formatUserError } from "@/lib/api-client";
import {
  Activity,
  ShieldCheck,
  RefreshCw,
  AlertTriangle,
  CheckCircle2,
  GitPullRequest,
  Cpu,
  RotateCcw,
  Clock,
  Layers,
  Zap,
  Lock,
  Copy,
  Check,
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
  useAuth();

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  const [outboxStats, setOutboxStats] = useState<OutboxStats | null>(null);
  const [deadLetters, setDeadLetters] = useState<DeadLetterEvent[]>([]);
  const [githubStats, setGithubStats] = useState<GitHubStats | null>(null);
  const [circuitBreakers, setCircuitBreakers] = useState<CircuitBreakerItem[]>([]);
  const [traceabilityStats, setTraceabilityStats] = useState<TraceabilityStats | null>(null);
  const [aiStats, setAiStats] = useState<AIStats | null>(null);
  const [auditEvents, setAuditEvents] = useState<AuditEventItem[]>([]);
  const [replayingId, setReplayingId] = useState<string | null>(null);
  const [replaySuccess, setReplaySuccess] = useState<string | null>(null);
  const [copiedTraceId, setCopiedTraceId] = useState<string | null>(null);

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
        ,
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

  const handleCopyTraceId = (traceId: string) => {
    navigator.clipboard.writeText(traceId);
    setCopiedTraceId(traceId);
    setTimeout(() => setCopiedTraceId(null), 2000);
  };

  return (
    <div className="space-y-6">
      {/* Top Header */}
      <div className="relative rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-5 before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-white/[0.06]">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="space-y-1">
            <div className="flex items-center gap-2.5">
              <Activity className="h-5 w-5 text-zinc-300" />
              <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
                Production Observability & Outbox Operations
              </h1>
              <Badge variant="outline" className="font-mono text-[10px] border-emerald-900 text-emerald-400 bg-emerald-950/20">
                LIVE TELEMETRY
              </Badge>
            </div>
            <p className="text-xs text-zinc-400">
              Real-time circuit breaker resilience, transactional outbox queue integrity, and partitioned immutable audit trail.
            </p>
          </div>
          <div className="flex items-center gap-2.5">
            <Badge variant="outline" className="font-mono text-[11px] bg-[#141418] text-zinc-300 border-[#26262C]">
              {currentOrg?.name || activeWorkspace?.name || "Personal Workspace"}
            </Badge>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                setRefreshing(true);
                fetchData();
              }}
              disabled={refreshing || loading}
              className="h-8 text-xs border-[#26262C] bg-[#141418] text-zinc-200"
            >
              <RefreshCw className={`h-3.5 w-3.5 mr-1.5 ${refreshing || loading ? "animate-spin" : ""}`} />
              Refresh
            </Button>
          </div>
        </div>
      </div>

      {errorMsg && (
        <div className="rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300 flex items-center gap-2.5">
          <AlertTriangle className="h-4 w-4 shrink-0 text-rose-400" />
          <span>{errorMsg}</span>
        </div>
      )}

      {replaySuccess && (
        <div className="rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3 text-xs text-emerald-300 flex items-center gap-2.5">
          <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-400" />
          <span>{replaySuccess}</span>
        </div>
      )}

      {/* KPI Overview Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
        {/* Outbox Health */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-2.5">
          <div className="flex items-center justify-between text-xs text-zinc-400">
            <span className="font-medium">Transactional Outbox</span>
            <Layers className="h-3.5 w-3.5 text-zinc-500" />
          </div>
          <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums">
            {outboxStats ? outboxStats.totalUnpublished : 0}
            <span className="text-xs font-normal font-sans text-zinc-500 ml-1.5">unprocessed</span>
          </div>
          <div className="text-[11px] text-zinc-400 space-y-1 pt-2 border-t border-[#1C1C21] font-mono">
            <div className="flex justify-between">
              <span className="text-zinc-500">Pending:</span>
              <span className="text-zinc-200">{outboxStats?.pending ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Retrying:</span>
              <span className="text-amber-400">{outboxStats?.retrying ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Dead Letter:</span>
              <span className={outboxStats && outboxStats.deadLetter > 0 ? "text-rose-400 font-semibold" : "text-emerald-400"}>
                {outboxStats?.deadLetter ?? 0}
              </span>
            </div>
          </div>
        </Card>

        {/* GitHub API & Webhooks */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-2.5">
          <div className="flex items-center justify-between text-xs text-zinc-400">
            <span className="font-medium">GitHub Integration</span>
            <GitPullRequest className="h-3.5 w-3.5 text-zinc-500" />
          </div>
          <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums">
            {githubStats ? githubStats.activeInstallations : 0}
            <span className="text-xs font-normal font-sans text-zinc-500 ml-1.5">active apps</span>
          </div>
          <div className="text-[11px] text-zinc-400 space-y-1 pt-2 border-t border-[#1C1C21] font-mono">
            <div className="flex justify-between">
              <span className="text-zinc-500">Rate Limit Left:</span>
              <span className="text-zinc-200">{githubStats?.rateLimitRemaining ?? 5000}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Quarantined:</span>
              <span className="text-amber-400">{githubStats?.quarantinedInstallations ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Suspended:</span>
              <span className="text-rose-400">{githubStats?.suspendedInstallations ?? 0}</span>
            </div>
          </div>
        </Card>

        {/* Traceability Matrix */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-2.5">
          <div className="flex items-center justify-between text-xs text-zinc-400">
            <span className="font-medium">Traceability Health</span>
            <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
          </div>
          <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums">
            {traceabilityStats ? traceabilityStats.syncTotal : 0}
            <span className="text-xs font-normal font-sans text-zinc-500 ml-1.5">sync cycles</span>
          </div>
          <div className="text-[11px] text-zinc-400 space-y-1 pt-2 border-t border-[#1C1C21] font-mono">
            <div className="flex justify-between">
              <span className="text-zinc-500">Projects Evaluated:</span>
              <span className="text-zinc-200">{traceabilityStats?.projectsProcessed ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Sync Failures:</span>
              <span className={traceabilityStats && traceabilityStats.syncFailures > 0 ? "text-rose-400" : "text-emerald-400"}>
                {traceabilityStats?.syncFailures ?? 0}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Gaps Detected:</span>
              <span className="text-amber-400">{traceabilityStats?.gapsDetected ?? 0}</span>
            </div>
          </div>
        </Card>

        {/* AI Orchestrator */}
        <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-2.5">
          <div className="flex items-center justify-between text-xs text-zinc-400">
            <span className="font-medium">AI Service & Latency</span>
            <Cpu className="h-3.5 w-3.5 text-zinc-500" />
          </div>
          <div className="text-2xl font-semibold text-zinc-100 font-mono tabular-nums">
            {aiStats ? aiStats.ai_analysis_total : 0}
            <span className="text-xs font-normal font-sans text-zinc-500 ml-1.5">analyses</span>
          </div>
          <div className="text-[11px] text-zinc-400 space-y-1 pt-2 border-t border-[#1C1C21] font-mono">
            <div className="flex justify-between">
              <span className="text-zinc-500">Success Rate:</span>
              <span className="text-emerald-400">
                {aiStats && aiStats.ai_analysis_total > 0
                  ? `${Math.round((aiStats.ai_analysis_success_total / aiStats.ai_analysis_total) * 100)}%`
                  : "100%"}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">Fallback Invocations:</span>
              <span className="text-amber-400">{aiStats?.ai_fallback_total ?? 0}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-zinc-500">p95 Latency:</span>
              <span className="text-zinc-200">{aiStats?.latency_p95_ms ?? 0} ms</span>
            </div>
          </div>
        </Card>
      </div>

      {/* Circuit Breakers & Dependency Resilience */}
      <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
        <div className="pb-2 border-b border-[#1C1C21]">
          <CardTitle className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
            <Zap className="h-3.5 w-3.5 text-amber-400" />
            Circuit Breaker Resilience Matrix
          </CardTitle>
          <CardDescription className="text-[11px] text-zinc-500 mt-0.5">
            Fast-fail thresholds protecting microservices against downstream cascading degradation.
          </CardDescription>
        </div>
        <div>
          {circuitBreakers.length === 0 ? (
            <div className="text-xs text-zinc-400 py-3 text-center font-mono">
              All registered circuit breakers healthy (CLOSED, 0 consecutive failures).
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {circuitBreakers.map((cb) => (
                <div
                  key={cb.serviceName}
                  className="rounded-md border border-[#1C1C21] bg-[#141418] p-3 space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <span className="font-semibold text-xs text-zinc-200">{cb.serviceName}</span>
                    <Badge
                      variant="outline"
                      className={`font-mono text-[10px] ${
                        cb.state === "CLOSED"
                          ? "border-emerald-900 text-emerald-400 bg-emerald-950/20"
                          : cb.state === "HALF_OPEN"
                          ? "border-amber-900 text-amber-400 bg-amber-950/20"
                          : "border-rose-900 text-rose-400 bg-rose-950/20"
                      }`}
                    >
                      {cb.state}
                    </Badge>
                  </div>
                  <div className="text-[11px] text-zinc-400 flex justify-between font-mono">
                    <span className="text-zinc-500">Consecutive Failures:</span>
                    <span className="text-zinc-200">{cb.consecutiveFailures}</span>
                  </div>
                  <div className="text-[11px] text-zinc-400 flex justify-between font-mono">
                    <span className="text-zinc-500">Reset Timeout:</span>
                    <span className="text-zinc-200">{cb.resetTimeoutSeconds}s</span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </Card>

      {/* Dead-Letter Outbox Queue & Replay Action */}
      <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
        <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
          <div>
            <CardTitle className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
              <RotateCcw className="h-3.5 w-3.5 text-rose-400" />
              Dead-Letter Outbox Queue
            </CardTitle>
            <CardDescription className="text-[11px] text-zinc-500 mt-0.5">
              Events exceeding max delivery retries preserved with full audit context for tenant-scoped replay.
            </CardDescription>
          </div>
          <Badge variant="outline" className="font-mono text-[10px] border-[#26262C] text-zinc-300">
            {deadLetters.length} Dead Letters
          </Badge>
        </div>

        <div>
          {deadLetters.length === 0 ? (
            <div className="rounded-md border border-dashed border-[#26262C] p-8 text-center text-zinc-400">
              <CheckCircle2 className="mx-auto h-6 w-6 text-emerald-400 mb-2" />
              <p className="text-xs font-medium text-zinc-200">No Poison-Pill Events</p>
              <p className="text-[11px] text-zinc-500 mt-1">All transactional outbox events published cleanly.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs text-zinc-300">
                <thead className="bg-[#0B0B0E] text-[10px] uppercase text-zinc-500 border-b border-[#1C1C21] font-mono">
                  <tr>
                    <th className="py-2 px-3">Event Type</th>
                    <th className="py-2 px-3">Aggregate ID</th>
                    <th className="py-2 px-3">Attempts</th>
                    <th className="py-2 px-3">Dead Letter At</th>
                    <th className="py-2 px-3">Last Error</th>
                    <th className="py-2 px-3 text-right">Action</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#1C1C21]">
                  {deadLetters.map((event) => (
                    <tr key={event.id} className="hover:bg-[#141418]">
                      <td className="py-2 px-3 font-mono text-xs text-zinc-200">{event.eventType}</td>
                      <td className="py-2 px-3 font-mono text-xs text-zinc-400">{event.aggregateId}</td>
                      <td className="py-2 px-3 font-mono text-xs tabular-nums">{event.attemptCount} / {event.maxAttempts}</td>
                      <td className="py-2 px-3 font-mono text-xs text-zinc-400">
                        {event.deadLetterAt ? new Date(event.deadLetterAt).toLocaleTimeString() : "N/A"}
                      </td>
                      <td className="py-2 px-3 text-xs text-rose-300 max-w-xs truncate" title={event.lastError}>
                        {event.lastError || "Unknown failure"}
                      </td>
                      <td className="py-2 px-3 text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => handleReplay(event.id)}
                          disabled={replayingId === event.id}
                          className="h-7 text-[11px] border-[#26262C] bg-[#141418] text-zinc-200 hover:bg-zinc-800"
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
        </div>
      </Card>

      {/* Immutable Audit Trail */}
      <Card className="p-4 border-[#1C1C21] bg-[#0E0E11] space-y-3">
        <div className="flex items-center justify-between pb-2 border-b border-[#1C1C21]">
          <div>
            <CardTitle className="text-xs font-semibold text-zinc-200 flex items-center gap-2">
              <Lock className="h-3.5 w-3.5 text-zinc-400" />
              Immutable Governance Audit Log
            </CardTitle>
            <CardDescription className="text-[11px] text-zinc-500 mt-0.5">
              Append-only audit trail strictly partitioned by organization. PostgreSQL trigger-enforced immutability.
            </CardDescription>
          </div>
          <Badge variant="outline" className="font-mono text-[10px] border-emerald-900 text-emerald-400 bg-emerald-950/20">
            IMMUTABLE TRIGGER ACTIVE
          </Badge>
        </div>

        <div>
          {auditEvents.length === 0 ? (
            <div className="rounded-md border border-dashed border-[#26262C] p-8 text-center text-zinc-400">
              <Clock className="mx-auto h-6 w-6 text-zinc-500 mb-2" />
              <p className="text-xs font-medium text-zinc-200">No Audit Events Recorded Yet</p>
              <p className="text-[11px] text-zinc-500 mt-1">Actions such as link creation, snapshotting, and replays will appear here.</p>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs text-zinc-300">
                <thead className="bg-[#0B0B0E] text-[10px] uppercase text-zinc-500 border-b border-[#1C1C21] font-mono">
                  <tr>
                    <th className="py-2 px-3">Timestamp</th>
                    <th className="py-2 px-3">Event Type</th>
                    <th className="py-2 px-3">Entity</th>
                    <th className="py-2 px-3">Action</th>
                    <th className="py-2 px-3">Outcome</th>
                    <th className="py-2 px-3">Trace ID</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-[#1C1C21]">
                  {auditEvents.map((ae) => (
                    <tr key={ae.id} className="hover:bg-[#141418]">
                      <td className="py-2 px-3 font-mono text-[11px] text-zinc-400">
                        {new Date(ae.createdAt).toLocaleString()}
                      </td>
                      <td className="py-2 px-3 font-mono text-xs text-zinc-200">{ae.eventType}</td>
                      <td className="py-2 px-3 font-mono text-[11px] text-zinc-400">
                        {ae.entityType}:{ae.entityId.slice(0, 8)}
                      </td>
                      <td className="py-2 px-3 text-xs font-medium text-zinc-200">{ae.action}</td>
                      <td className="py-2 px-3 text-xs">
                        <Badge
                          variant="outline"
                          className={`font-mono text-[10px] ${
                            ae.outcome === "SUCCESS"
                              ? "border-emerald-900 text-emerald-400 bg-emerald-950/20"
                              : "border-rose-900 text-rose-400 bg-rose-950/20"
                          }`}
                        >
                          {ae.outcome}
                        </Badge>
                      </td>
                      <td className="py-2 px-3 font-mono text-[11px] text-zinc-400">
                        {ae.traceId ? (
                          <button
                            onClick={() => handleCopyTraceId(ae.traceId!)}
                            className="inline-flex items-center gap-1.5 hover:text-zinc-200 transition-colors cursor-pointer"
                            title="Copy full Trace ID"
                          >
                            <span>{ae.traceId.slice(0, 14)}...</span>
                            {copiedTraceId === ae.traceId ? (
                              <Check className="h-3 w-3 text-emerald-400" />
                            ) : (
                              <Copy className="h-3 w-3 text-zinc-600" />
                            )}
                          </button>
                        ) : (
                          "N/A"
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
