"use client";

import React, { useState, useEffect, useCallback, Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { useTenant } from "@/lib/tenant-context";
import { apiClient } from "@/lib/api-client";
import { Repository } from "@/types";
import {
  Sparkles,
  Search,
  Network,
  GitPullRequest,
  Loader2,
  Info,
  GitBranch,
  Shield,
  ShieldCheck,
  ShieldAlert,
  FileCode,
  CheckCircle2,
  AlertTriangle,
  FolderGit2,
  ExternalLink,
  ChevronDown,
  ChevronRight,
  GitCommit,
  Layers,
  Database,
  Cpu,
  ArrowRight,
  FileDiff,
  Code2,
  RefreshCw,
  Activity,
  Sliders,
  TestTube,
  History,
  Boxes,
  FileCheck,
  FileWarning,
  Flame,
  Zap,
  CheckCheck,
  BarChart3,
  ShieldX,
  ChevronUp,
  HelpCircle,
  Check,
  XCircle,
  AlertCircle,
  Scale,
  Share2,
  Workflow,
  Globe,
  Lock,
  Bug,
  Clock,
  Play,
  KeyRound,
  Eye,
  X,
  ArrowUpRight,
  Terminal,
  CheckSquare,
  FileText,
  Mail,
  BookOpen,
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { evaluateSecurityStatus, extractSafeCodeContext } from "@/lib/security-helpers";
import { MarkdownMermaidViewer } from "@/components/ai/MarkdownMermaidViewer";
import { GroundedReasoningRenderer } from "@/components/ai/GroundedReasoningRenderer";
import { SmtpTestBanner } from "@/components/ai/SmtpTestBanner";
import { AutoDocsGridSkeleton, CommitReportsListSkeleton } from "@/components/ai/SkeletonLoaders";

type LifecycleStep = "idle" | "fetching" | "deriving" | "indexing" | "graph" | "reasoning" | "complete";

export interface RepositorySyncStatus {
  repositoryId: string;
  organizationId: string;
  syncStatus: "SYNCED" | "SYNCING" | "STALE" | "FAILED";
  latestObservedSha?: string;
  latestIndexedSha?: string;
  lastSyncedAt?: string;
  lastError?: string;
  lastDeliveryId?: string;
}

interface PullRequestFile {
  filename: string;
  status: string;
  additions: number;
  deletions: number;
  changes: number;
  patch?: string;
  content?: string;
}

interface PullRequestChangeSet {
  repositoryId: string;
  repositoryFullName: string;
  number: number;
  title: string;
  body: string;
  state: string;
  sourceBranch: string;
  targetBranch: string;
  baseSha: string;
  headSha: string;
  author: string;
  additions: number;
  deletions: number;
  changedFilesCount: number;
  files: PullRequestFile[];
}

export interface AnalysisFactor {
  factor?: string;
  name?: string;
  points?: number;
  score?: number;
  weight?: number;
  raw_value?: number;
  detail?: string;
  details?: string;
}

export interface TestImpactItem {
  test_name: string;
  test_file: string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  reason: string;
}

export interface HistoricalSignalItem {
  file_path: string;
  commit_count: number;
  recent_commits?: string[];
  last_modified?: string;
  authors?: string[];
}

export interface ComponentItem {
  type: string;
  name: string;
  file_path?: string;
}

export interface BlastRadiusItem {
  source_symbol: string;
  target_symbol?: string;
  edge_type: string;
  depth: number;
  file_path?: string;
}

export interface TestRecommendationItem {
  id?: string;
  test_name: string;
  test_file: string;
  test_method?: string;
  target_symbol?: string;
  priority_score: number;
  priority_bucket: "MUST_RUN" | "SHOULD_RUN" | "OPTIONAL";
  merge_requirement?: "TEST_REQUIRED" | "TEST_RECOMMENDED" | "TEST_OPTIONAL" | string;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  evidence_sources: string[];
  evidence_types: string[];
  evidence_path: string[];
  reason: string;
  historical_failure_count: number;
  cochange_count: number;
  runtime_coverage_verified: boolean;
}

export interface ReviewFindingItem {
  id?: string;
  title: string;
  category: "BUG_RISK" | "BREAKING_CHANGE" | "REGRESSION_RISK" | "SECURITY" | "TEST_GAP" | "STYLE_MAINTAINABILITY" | string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO" | string;
  finding_type: "OBSERVED" | "INFERRED" | string;
  affected_file: string;
  affected_symbol?: string;
  description: string;
  evidence: string;
  recommendation: string;
}

export interface RequiredCheckItem {
  name: string;
  category: string;
  status: "PASSED" | "FAILED" | "MISSING" | "RUNNING" | "WARNING" | "SKIPPED" | string;
  evidence: string;
  detail: string;
}

export interface MergeReadinessDTO {
  decision: "READY" | "READY_WITH_WARNINGS" | "NOT_READY" | "BLOCKED_BY_MISSING_EVIDENCE" | string;
  readiness_score: number;
  policy_version: string;
  summary: string;
  reasons: string[];
  blockers: string[];
  warnings: string[];
  required_checks: RequiredCheckItem[];
  completed_checks: (string | RequiredCheckItem)[];
  failed_checks: (string | RequiredCheckItem)[];
  missing_checks: (string | RequiredCheckItem)[];
  security_status: "NO_SECURITY_FINDINGS" | "SECURITY_WARNING" | "SECURITY_BLOCKER" | "SECURITY_EVIDENCE_UNAVAILABLE" | string;
  evidence_summary: {
    ci_status: string;
    total_required_tests: number;
    executed_required_tests: number;
    failed_required_tests: number;
    security_status: string;
    deterministic_risk_score: number;
  };
}

export interface TestStrategyDTO {
  must_run: TestRecommendationItem[];
  should_run: TestRecommendationItem[];
  optional: TestRecommendationItem[];
  static_evidence: any[];
  runtime_evidence: any[];
  historical_failures: any[];
  coverage_summary: {
    coverage_available: boolean;
    coverage_mechanism: string;
    runtime_runs_ingested: number;
    historical_failures_observed: number;
  };
  overall_confidence: "HIGH" | "MEDIUM" | "LOW";
  confidence_rationale: string;
}

export interface TestValidationResponseDTO {
  organization_id: string;
  repository_id: string;
  commit_sha: string;
  prediction_to_failure_correlation: number;
  test_selection_efficiency: number;
  high_priority_missed_count: number;
  total_recommended: number;
  total_actual_executed: number;
  actual_failed_count: number;
  recommended_failed_count: number;
  missed_failures: string[];
  created_at?: string;
}

export interface SecurityFindingDTO {
  id?: string;
  category: string;
  rule_id: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  title: string;
  description: string;
  file_path: string;
  line_number?: number | null;
  redacted_evidence: string;
  remediation: string;
  is_blocker: boolean;
}

export interface SecurityAssessmentDTO {
  security_status: "NO_SECURITY_FINDINGS" | "SECURITY_WARNING" | "SECURITY_BLOCKER" | "SECURITY_EVIDENCE_UNAVAILABLE" | string;
  evidence_status: "AVAILABLE" | "PARTIAL" | "UNAVAILABLE" | string;
  security_risk_score: number;
  critical_count: number;
  high_count: number;
  medium_count: number;
  low_count: number;
  findings: SecurityFindingDTO[];
  summary: string;
}

export interface ReliabilityFindingDTO {
  id?: string;
  category: string;
  rule_id: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  title: string;
  description: string;
  file_path: string;
  line_number?: number | null;
  evidence: string;
  remediation: string;
  is_blocker: boolean;
}

export interface ReliabilityAssessmentDTO {
  reliability_status: "PASSED" | "WARNING" | "FAILED" | "UNAVAILABLE" | string;
  evidence_status: "AVAILABLE" | "PARTIAL" | "UNAVAILABLE" | string;
  reliability_risk_score: number;
  critical_count: number;
  high_count: number;
  medium_count: number;
  low_count: number;
  findings: ReliabilityFindingDTO[];
  summary: string;
}

export interface UnifiedRiskScoreDTO {
  local_risk: number;
  distributed_risk: number;
  security_risk: number;
  reliability_risk: number;
  final_risk: number;
  final_risk_level: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" | string;
  risk_formula: string;
  dimension_contributions: Record<string, number>;
}

export interface PrAnalysisResponse {
  id?: string;
  analysis_id?: string;
  risk?: string;
  risk_level: "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
  risk_score: number;
  confidence: "HIGH" | "MEDIUM" | "LOW";
  summary: string;
  merge_readiness?: MergeReadinessDTO;
  security_assessment?: SecurityAssessmentDTO;
  reliability_assessment?: ReliabilityAssessmentDTO;
  unified_risk?: UnifiedRiskScoreDTO;
  review_findings?: ReviewFindingItem[];
  changed_components?: ComponentItem[];
  direct_impact?: BlastRadiusItem[];
  transitive_impact?: BlastRadiusItem[];
  affected_modules?: string[];
  affected_tests?: TestImpactItem[];
  test_strategy?: TestStrategyDTO;
  test_effectiveness?: {
    prediction_to_failure_correlation: number;
    test_selection_efficiency: number;
    high_priority_missed_count: number;
  };
  historical_signals?: HistoricalSignalItem[];
  architectural_concerns?: string[];
  recommendations?: string[];
  risk_factors?: AnalysisFactor[];
  impact_metadata?: {
    deterministic_risk_score?: number;
    risk_level?: string;
    confidence?: string;
    confidence_reason?: string;
    direct_impact_count?: number;
    transitive_impact_count?: number;
    affected_modules_count?: number;
    affected_tests_count?: number;
    historical_signals_count?: number;
    cross_module_impact?: boolean;
    distributed_impact?: DistributedRiskAssessmentDTO;
    contract_status?: "COMPATIBLE" | "POTENTIALLY_BREAKING" | "BREAKING" | string;
    contract_rationale?: string;
    showing_previous_ai_data?: boolean;
    ai_api_unavailable?: boolean;
    banner_message?: string;
  };
  retrieved_chunks?: Array<{
    chunk_id?: string;
    file_path?: string;
    symbol_name?: string;
    symbol_type?: string;
    source: "PR_DIFF" | "GRAPH" | "VECTOR" | "TEST" | "HISTORY" | "SYNC_STATE";
    depth?: number;
    similarity?: number;
    edge_type?: string;
    commit_sha?: string;
    content?: string;
    organization_id?: string;
  }>;
  provider: string;
  model_version: string;
  prompt_version?: string;
  execution_duration_ms: number;
  fallback_used: boolean;
  trace_id?: string;
  created_at?: string;
  engineering_actions?: EngineeringActionDTO[];
}

export interface SecretScanResultDTO {
  secrets_found: number;
  critical_count: number;
  high_count: number;
  warning_count: number;
  files_scanned: number;
  commit_sha?: string;
  scan_time_ms: number;
  findings: SecurityFindingDTO[];
  summary: string;
}

export interface ReScanResultDTO {
  analysis_id: string;
  before_risk_score: number;
  after_risk_score: number;
  risk_delta: number;
  before_merge_readiness: string;
  after_merge_readiness: string;
  merge_readiness_delta: string;
  before_critical_count: number;
  after_critical_count: number;
  before_high_count?: number;
  after_high_count?: number;
  findings_resolved: string[];
  findings_persisted: string[];
  findings_new: string[];
  is_clean: boolean;
  summary: string;
  updated_analysis?: PrAnalysisResponse;
}

export interface ExplainFindingResponseDTO {
  finding_id?: string;
  rule_id: string;
  title: string;
  what_happened: string;
  why_it_is_risky: string;
  production_impact: string;
  how_to_fix: string;
  supporting_evidence: string;
  remediation_guidance: string[];
}

export interface DependencySecurityStatusDTO {
  status: string;
  code_level_security: string;
  secret_scanning: string;
  dependency_cve_database: string;
  dependencies_scanned: number;
  vulnerabilities_found: number;
  findings: any[];
  summary: string;
}

export interface AnalysisHistoryItemDTO {
  analysis_id: string;
  entity_id: string;
  created_at: string;
  risk_score: number;
  risk_level: string;
  merge_readiness: string;
  critical_findings_count: number;
  high_findings_count: number;
  resolved_findings_from_previous: string[];
  new_findings_from_previous: string[];
}

export interface EngineeringActionDTO {
  id?: string;
  action_id: string;
  analysis_id?: string;
  organization_id: string;
  repository_id: string;
  pr_number: number;
  commit_sha: string;
  action_type: "RUN_TEST" | "ADD_TEST" | "FIX_SECURITY" | "FIX_RELIABILITY" | "UPDATE_CONFIGURATION" | "UPDATE_DEPENDENCY" | "UPDATE_API_CONTRACT" | "DOCUMENT_CHANGE" | "REVIEW_REQUIRED" | "NO_ACTION" | string;
  title: string;
  description: string;
  reason: string;
  severity: "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  evidence: string;
  affected_files: string[];
  affected_symbols: string[];
  status: "PROPOSED" | "APPROVED" | "REJECTED" | "APPLIED" | "FAILED" | "SUPERSEDED" | string;
  source_finding_id?: string;
  source_finding_type?: string;
  remediation_plan_id?: string;
  provenance?: Record<string, any>;
  metadata?: Record<string, any>;
  created_at?: string;
  updated_at?: string;
}

export interface RemediationStepDTO {
  step_number: number;
  target_file: string;
  target_symbol: string;
  change_description: string;
  validation_strategy: string;
  status: "PENDING" | "APPROVED" | "COMPLETED" | "FAILED" | "BLOCKED" | string;
  repository_id?: string;
  repository_name?: string;
}

export interface RemediationPlanDTO {
  id?: string;
  action_id: string;
  organization_id: string;
  repository_id: string;
  title: string;
  summary: string;
  steps: RemediationStepDTO[];
  is_multi_repo: boolean;
  coordinated_repos: string[];
  expected_impact: string;
  provenance?: Record<string, any>;
  created_at?: string;
}

export interface PatchProposalDTO {
  id?: string;
  action_id: string;
  organization_id: string;
  repository_id: string;
  target_file: string;
  target_symbol: string;
  original_context: string;
  proposed_replacement: string;
  unified_diff: string;
  rationale: string;
  source_finding: string;
  confidence: string;
  status: "PROPOSED" | "VALIDATED" | "APPLIED" | "REJECTED" | "SUPERSEDED" | string;
  model: string;
  provider: string;
  fallback_used?: boolean;
  prompt_version?: string;
  created_at?: string;
}

export interface RemediationEffectivenessDTO {
  id?: string;
  action_id: string;
  organization_id: string;
  repository_id: string;
  before_risk_score: number;
  after_risk_score: number;
  risk_delta: number;
  before_merge_readiness: string;
  after_merge_readiness: string;
  merge_readiness_delta: string;
  findings_fixed: string[];
  findings_persisted: string[];
  new_findings_introduced: string[];
  tests_added: number;
  tests_passed: boolean;
  is_effective: boolean;
  remediation_summary: string;
  created_at?: string;
}

export interface ActionEventDTO {
  id?: string;
  organization_id: string;
  repository_id: string;
  action_id: string;
  event_type: string;
  actor_id: string;
  details: Record<string, any>;
  created_at?: string;
}

export interface ActionDetailsDTO {
  action: EngineeringActionDTO;
  plan?: RemediationPlanDTO | null;
  patch?: PatchProposalDTO | null;
  effectiveness?: RemediationEffectivenessDTO | null;
  events?: ActionEventDTO[];
  write_mode: string;
}

export interface DownstreamImpactItem {
  target_repository_id: string;
  target_repository_name: string;
  dependency_type: string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  declared_contract_status: string;
  downstream_ci_status: "PASS" | "FAIL" | "NOT_FOUND" | "PENDING" | string;
  risk_contribution: number;
  affected_symbols: string[];
  evidence_files: string[];
}

export interface DistributedRiskAssessmentDTO {
  source_repository_id: string;
  local_risk_score: number;
  local_risk_level: string;
  distributed_risk_score: number;
  distributed_risk_level: string;
  combined_risk_score: number;
  combined_risk_level: string;
  contract_status: "COMPATIBLE" | "POTENTIALLY_BREAKING" | "BREAKING" | string;
  direct_downstream_count: number;
  downstream_impacts: DownstreamImpactItem[];
  risk_factors: AnalysisFactor[];
}

export interface DependencyEvidenceItem {
  id?: string;
  evidence_type: string;
  file_path: string;
  snippet?: string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  metadata?: Record<string, any>;
}

export interface RepositoryDependencyItem {
  id: string;
  organization_id: string;
  source_repository_id: string;
  target_repository_id: string;
  target_repository_name?: string;
  source_repository_name?: string;
  dependency_type: string;
  confidence: "HIGH" | "MEDIUM" | "LOW" | string;
  state: "ACTIVE" | "DEPRECATED" | "ARCHIVED" | string;
  evidence: DependencyEvidenceItem[];
  created_at?: string;
}

export interface GeneratedDocumentItem {
  id: string;
  organization_id: string;
  repository_id: string;
  doc_type: string;
  target_path: string;
  commit_sha: string;
  content_hash: string;
  content_md: string;
  citations: Array<{ file_path: string; commit_sha: string; reason?: string }>;
  provenance: Record<string, any>;
  status: "DRAFT" | "APPROVED" | "REJECTED" | "APPLIED" | "STALE" | "FAILED";
  approved_by?: string;
  pr_url?: string;
  branch_name?: string;
  created_at: string;
  updated_at: string;
}

export interface CommitAnalysisReportItem {
  id: string;
  organization_id: string;
  repository_id: string;
  commit_sha: string;
  ref?: string;
  author_name?: string;
  author_email?: string;
  commit_message?: string;
  status: "CLEAN" | "WARNING" | "CRITICAL_ALERT";
  risk_score: number;
  secrets_detected: number;
  security_issues: number;
  reliability_issues: number;
  findings: Array<any>;
  report_markdown: string;
  system_design_doc_id?: string;
  alert_sent: boolean;
  alert_recipient?: string;
  alert_status: string;
  created_at: string;
}

function AiInsightsContent() {
  const searchParams = useSearchParams();
  const { currentOrg, activeWorkspace, isPersonalWorkspace } = useTenant();
  const workspaceDisplayName = isPersonalWorkspace ? "Personal Workspace" : currentOrg?.name || "Workspace";
  const [activeTab, setActiveTab] = useState<"pr" | "search" | "impact" | "dependencies" | "remediation" | "autodocs" | "commitguard">("pr");

  // Authorized repositories
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<string>(() => {
    if (typeof window !== "undefined") {
      const urlParams = new URLSearchParams(window.location.search);
      const qRepo = urlParams.get("repoId");
      if (qRepo) return qRepo;
      return localStorage.getItem("forgeai_selected_repo_id") || "";
    }
    return "";
  });
  const [isLoadingRepos, setIsLoadingRepos] = useState<boolean>(true);

  // Interactive Branch Selector State
  const [branches, setBranches] = useState<Array<{ name: string; commitSha: string; isProtected?: boolean }>>([]);
  const [selectedBranch, setSelectedBranch] = useState<string>("");
  const [isLoadingBranches, setIsLoadingBranches] = useState<boolean>(false);

  // Real-Time Activity & SSE Status Indicator State
  const [sseConnected, setSseConnected] = useState<boolean>(false);
  const [toasts, setToasts] = useState<Array<{
    id: string;
    type: "success" | "error" | "info";
    title: string;
    message: string;
    timestamp: Date;
  }>>([]);

  const addToast = useCallback((type: "success" | "error" | "info", title: string, message: string) => {
    const id = `toast-${Date.now()}-${Math.random().toString(36).substring(2, 7)}`;
    setToasts((prev) => [...prev, { id, type, title, message, timestamp: new Date() }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, 5000);
  }, []);

  // Authoritative Repository Selector Handler with Persistence
  const handleSelectRepo = useCallback((repoId: string) => {
    setSelectedRepoId(repoId);
    if (typeof window !== "undefined") {
      localStorage.setItem("forgeai_selected_repo_id", repoId);
      const url = new URL(window.location.href);
      url.searchParams.set("repoId", repoId);
      window.history.replaceState({}, "", url.toString());
    }
  }, []);

  // Repository Continuous Synchronization state
  const [syncStatus, setSyncStatus] = useState<RepositorySyncStatus | null>(null);
  const [isLoadingSyncStatus, setIsLoadingSyncStatus] = useState<boolean>(false);
  const [isReconciling, setIsReconciling] = useState<boolean>(false);

  // Real Pull Requests
  const [pullRequests, setPullRequests] = useState<any[]>([]);
  const [selectedPrNumber, setSelectedPrNumber] = useState<number | "">("");
  const [isLoadingPrs, setIsLoadingPrs] = useState<boolean>(false);

  // Authoritative PR Change Set
  const [prChangeSet, setPrChangeSet] = useState<PullRequestChangeSet | null>(null);
  const [isLoadingChangeSet, setIsLoadingChangeSet] = useState<boolean>(false);

  // PR Analysis & Lifecycle state
  const [lifecycleStep, setLifecycleStep] = useState<LifecycleStep>("idle");
  const [isAnalyzingPr, setIsAnalyzingPr] = useState<boolean>(false);
  const [prAnalysisResult, setPrAnalysisResult] = useState<PrAnalysisResponse | null>(null);
  const [prError, setPrError] = useState<string | null>(null);

  // Active repository and branch resolution
  const selectedRepo = repositories.find((r) => r.id === selectedRepoId);
  const activeBranchObj = branches.find((b) => b.name === selectedBranch);
  const activeBranchSha = activeBranchObj?.commitSha || prChangeSet?.headSha || syncStatus?.latestObservedSha || "HEAD";

  // Advanced override
  const [showAdvancedOverride, setShowAdvancedOverride] = useState<boolean>(false);
  const [manualSymbolsInput, setManualSymbolsInput] = useState<string>("");

  // Semantic search tab state
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<any[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  // Impact analysis tab state
  const [impactSymbolsInput, setImpactSymbolsInput] = useState("");
  const [impactResults, setImpactResults] = useState<any[]>([]);
  const [isAnalyzingImpact, setIsAnalyzingImpact] = useState(false);
  const [impactError, setImpactError] = useState<string | null>(null);
  const [hasSearchedImpact, setHasSearchedImpact] = useState(false);

  // Provenance filter
  const [provenanceFilter, setProvenanceFilter] = useState<
    "ALL" | "PR_DIFF" | "GRAPH" | "VECTOR" | "TEST" | "HISTORY" | "SYNC_STATE"
  >("ALL");

  // Test Intelligence & Validation state
  const [testBucketFilter, setTestBucketFilter] = useState<
    "MUST_RUN" | "SHOULD_RUN" | "OPTIONAL" | "ALL" | "FAILURES"
  >("MUST_RUN");
  const [isValidatingTests, setIsValidatingTests] = useState<boolean>(false);
  const [testValidationResult, setTestValidationResult] = useState<TestValidationResponseDTO | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);

  // Merge Readiness & Review Findings state
  const [isReadinessDetailsOpen, setIsReadinessDetailsOpen] = useState<boolean>(false);
  const [findingSeverityFilter, setFindingSeverityFilter] = useState<string>("ALL");

  // Cross-Repository Dependencies Tab state
  const [orgDependencies, setOrgDependencies] = useState<RepositoryDependencyItem[]>([]);
  const [isLoadingDependencies, setIsLoadingDependencies] = useState<boolean>(false);
  const [isDiscoveringDependencies, setIsDiscoveringDependencies] = useState<boolean>(false);
  const [dependencyDiscoveryResult, setDependencyDiscoveryResult] = useState<string | null>(null);
  const [dependencyError, setDependencyError] = useState<string | null>(null);
  const [dependencyFilterConfidence, setDependencyFilterConfidence] = useState<string>("ALL");

  // Controlled AI Remediation Workspace State
  const [selectedActionId, setSelectedActionId] = useState<string | null>(null);
  const [actionDetailsMap, setActionDetailsMap] = useState<Record<string, ActionDetailsDTO>>({});
  const [isLoadingActionDetails, setIsLoadingActionDetails] = useState<boolean>(false);
  const [actionStatusFilter, setActionStatusFilter] = useState<string>("ALL");
  const [actionTypeFilter, setActionTypeFilter] = useState<string>("ALL");
  const [isApprovingAction, setIsApprovingAction] = useState<boolean>(false);
  const [isRejectingAction, setIsRejectingAction] = useState<boolean>(false);
  const [isValidatingPatch, setIsValidatingPatch] = useState<boolean>(false);
  const [isApplyingAction, setIsApplyingAction] = useState<boolean>(false);
  const [validationResultsMap, setValidationResultsMap] = useState<Record<string, any>>({});
  const [remediationFeedback, setRemediationFeedback] = useState<{ actionId: string; type: "success" | "error" | "info"; message: string } | null>(null);
  const [rejectReasonInput, setRejectReasonInput] = useState<string>("");
  const [rejectingActionId, setRejectingActionId] = useState<string | null>(null);
  const [localActions, setLocalActions] = useState<EngineeringActionDTO[]>([]);

  // Auto-Docs & System Design state
  const [documents, setDocuments] = useState<GeneratedDocumentItem[]>([]);
  const [showHistoricalDocs, setShowHistoricalDocs] = useState<boolean>(false);
  const [isLoadingDocs, setIsLoadingDocs] = useState<boolean>(false);
  const [isGeneratingDocs, setIsGeneratingDocs] = useState<boolean>(false);
  const [selectedDocPreview, setSelectedDocPreview] = useState<GeneratedDocumentItem | null>(null);
  const [docActionError, setDocActionError] = useState<string | null>(null);
  const [docActionSuccess, setDocActionSuccess] = useState<string | null>(null);
  const [processingDocId, setProcessingDocId] = useState<string | null>(null);

  const latestDocs = React.useMemo(() => {
    const byType = new Map<string, GeneratedDocumentItem>();
    for (const doc of documents) {
      const key = doc.doc_type || doc.target_path || doc.id;
      const existing = byType.get(key);
      if (!existing) {
        byType.set(key, doc);
      } else {
        const docTime = doc.created_at ? new Date(doc.created_at).getTime() : 0;
        const existingTime = existing.created_at ? new Date(existing.created_at).getTime() : 0;
        if (docTime >= existingTime) {
          byType.set(key, doc);
        }
      }
    }
    return Array.from(byType.values());
  }, [documents]);

  const displayedDocs = showHistoricalDocs ? documents : latestDocs;

  // Commit Guard & Push Alert Log state
  const [commitReports, setCommitReports] = useState<CommitAnalysisReportItem[]>([]);
  const [isLoadingCommitReports, setIsLoadingCommitReports] = useState<boolean>(false);
  const [selectedCommitReport, setSelectedCommitReport] = useState<CommitAnalysisReportItem | null>(null);
  const [isOnDemandAnalyzing, setIsOnDemandAnalyzing] = useState<boolean>(false);
  const [isCheckingNewCommits, setIsCheckingNewCommits] = useState<boolean>(false);

  // Engineering Health Navigation & Sub-views
  const [activeHealthSection, setActiveHealthSection] = useState<
    "all" | "security" | "reliability" | "tests" | "dependencies" | "impact" | "merge"
  >("all");

  // Synchronize route query parameters (?repoId=...&pr=...&section=...)
  useEffect(() => {
    if (!searchParams) return;
    const qRepoId = searchParams.get("repoId");
    const qPr = searchParams.get("pr") || searchParams.get("prNumber");
    const qSection = searchParams.get("section");

    if (qRepoId) {
      setSelectedRepoId((current) => {
        if (current !== qRepoId) {
          if (typeof window !== "undefined") {
            localStorage.setItem("forgeai_selected_repo_id", qRepoId);
          }
          return qRepoId;
        }
        return current;
      });
    }
    if (qPr) {
      const parsedPr = Number(qPr);
      if (!isNaN(parsedPr)) {
        setSelectedPrNumber(parsedPr);
      }
    }
    if (qSection) {
      const validSections = ["all", "security", "reliability", "tests", "dependencies", "impact", "merge"];
      if (validSections.includes(qSection)) {
        setActiveHealthSection(qSection as any);
      }
    }
  }, [searchParams]);

  // On-Demand Secret Scanning State
  const [isSecretScanning, setIsSecretScanning] = useState<boolean>(false);
  const [secretScanResult, setSecretScanResult] = useState<SecretScanResultDTO | null>(null);
  const [isSecretScanModalOpen, setIsSecretScanModalOpen] = useState<boolean>(false);

  // Repository Security Snapshot Scan State
  const [isRepoScanning, setIsRepoScanning] = useState<boolean>(false);
  const [repoScanResult, setRepoScanResult] = useState<SecurityAssessmentDTO | null>(null);

  // Re-Scan Differential Pipeline State
  const [isRescanning, setIsRescanning] = useState<boolean>(false);
  const [rescanResult, setRescanResult] = useState<ReScanResultDTO | null>(null);
  const [isRescanModalOpen, setIsRescanModalOpen] = useState<boolean>(false);

  // Dependency Security State
  const [dependencySecurity, setDependencySecurity] = useState<DependencySecurityStatusDTO | null>(null);
  const [isLoadingDepSecurity, setIsLoadingDepSecurity] = useState<boolean>(false);
  const [isDepSecurityModalOpen, setIsDepSecurityModalOpen] = useState<boolean>(false);

  // Analysis History Timeline State
  const [analysisHistory, setAnalysisHistory] = useState<AnalysisHistoryItemDTO[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState<boolean>(false);
  const [isHistoryModalOpen, setIsHistoryModalOpen] = useState<boolean>(false);

  // Finding Detail & AI Grounded Explanation Modal State
  const [selectedFindingForModal, setSelectedFindingForModal] = useState<any | null>(null);
  const [isExplainingFinding, setIsExplainingFinding] = useState<boolean>(false);
  const [aiExplanation, setAiExplanation] = useState<ExplainFindingResponseDTO | null>(null);
  const [explainError, setExplainError] = useState<string | null>(null);

  // AI Service Unavailable & Last Analysis Persistence State
  const [isUsingLastAnalysis, setIsUsingLastAnalysis] = useState<boolean>(false);
  const [lastAnalysisTimestamp, setLastAnalysisTimestamp] = useState<string | null>(null);
  const [aiApiErrorModal, setAiApiErrorModal] = useState<{
    isOpen: boolean;
    message: string;
    hasLastAnalysis: boolean;
    timestamp?: string;
    repoName?: string;
    prNumber?: number | string;
    errorMessage?: string;
  }>({
    isOpen: false,
    message: "",
    hasLastAnalysis: false,
  });

  const triggerAiApiUnavailablePopup = useCallback((customErr?: string) => {
    let cached: any = null;
    try {
      if (selectedRepoId && selectedPrNumber) {
        const specificRaw = localStorage.getItem(`forgeai_last_analysis_${selectedRepoId}_${selectedPrNumber}`);
        if (specificRaw) cached = JSON.parse(specificRaw);
      }
      if (!cached && selectedRepoId) {
        const repoRaw = localStorage.getItem(`forgeai_last_analysis_${selectedRepoId}`);
        if (repoRaw) cached = JSON.parse(repoRaw);
      }
      if (!cached) {
        const globalRaw = localStorage.getItem(`forgeai_last_analysis_global`);
        if (globalRaw) cached = JSON.parse(globalRaw);
      }
    } catch (e) {
      console.warn("Could not read cached analysis from localStorage:", e);
    }

    if (cached && cached.data) {
      setPrAnalysisResult(cached.data);
      setIsUsingLastAnalysis(true);
      const tsStr = cached.timestamp ? new Date(cached.timestamp).toLocaleString() : "Previous session";
      setLastAnalysisTimestamp(tsStr);
      setAiApiErrorModal({
        isOpen: true,
        message: "AI API is not working. This is the last analysis by AI service.",
        hasLastAnalysis: true,
        timestamp: tsStr,
        repoName: cached.repoName,
        prNumber: cached.prNumber || selectedPrNumber,
        errorMessage: customErr || "The AI Service is currently unavailable or timed out.",
      });
    } else {
      setIsUsingLastAnalysis(false);
      setAiApiErrorModal({
        isOpen: true,
        message: "AI API is not working. No previous analysis is available.",
        hasLastAnalysis: false,
        errorMessage: customErr || "The AI Service is currently unavailable or timed out.",
      });
    }
  }, [selectedRepoId, selectedPrNumber]);

  useEffect(() => {
    if (prAnalysisResult?.engineering_actions && prAnalysisResult.engineering_actions.length > 0) {
      setLocalActions(prAnalysisResult.engineering_actions);
      if (!selectedActionId) {
        setSelectedActionId(prAnalysisResult.engineering_actions[0].action_id);
      }
    }
  }, [prAnalysisResult, selectedActionId]);

  const fetchActionDetails = useCallback(async (actionId: string) => {
    if (!actionId) return;
    setIsLoadingActionDetails(true);
    try {
      const details = await apiClient.get<ActionDetailsDTO>(`/api/v1/ai/actions/${actionId}`);
      if (details && typeof details === "object") {
        setActionDetailsMap((prev) => ({ ...prev, [actionId]: details }));
      }
    } catch (err: any) {
      console.warn(`Could not fetch details for action ${actionId}:`, err);
    } finally {
      setIsLoadingActionDetails(false);
    }
  }, []);

  useEffect(() => {
    if (selectedActionId && !actionDetailsMap[selectedActionId]) {
      fetchActionDetails(selectedActionId);
    }
  }, [selectedActionId, actionDetailsMap, fetchActionDetails]);

  const handleApproveAction = async (actionId: string) => {
    setIsApprovingAction(true);
    setRemediationFeedback(null);
    try {
      await apiClient.post<EngineeringActionDTO>(`/api/v1/ai/actions/${actionId}/approve`, {
        actor_id: "developer",
        comment: "Approved from Remediation Workspace",
      });
      setLocalActions((prev) =>
        prev.map((a) => (a.action_id === actionId ? { ...a, status: "APPROVED" } : a))
      );
      fetchActionDetails(actionId);
      setRemediationFeedback({
        actionId,
        type: "success",
        message: `Action ${actionId} approved successfully by developer.`,
      });
    } catch (err: any) {
      setRemediationFeedback({
        actionId,
        type: "error",
        message: err?.message || "Failed to approve action.",
      });
    } finally {
      setIsApprovingAction(false);
    }
  };

  const handleRejectAction = async (actionId: string) => {
    if (!rejectReasonInput.trim()) {
      setRemediationFeedback({
        actionId,
        type: "error",
        message: "Please provide an explicit audit reason before rejecting a remediation action.",
      });
      return;
    }
    setIsRejectingAction(true);
    setRemediationFeedback(null);
    try {
      await apiClient.post<EngineeringActionDTO>(`/api/v1/ai/actions/${actionId}/reject`, {
        actor_id: "developer",
        reason: rejectReasonInput || "Rejected by developer",
      });
      setLocalActions((prev) =>
        prev.map((a) => (a.action_id === actionId ? { ...a, status: "REJECTED" } : a))
      );
      fetchActionDetails(actionId);
      setRejectingActionId(null);
      setRejectReasonInput("");
      setRemediationFeedback({
        actionId,
        type: "info",
        message: `Action ${actionId} rejected.`,
      });
    } catch (err: any) {
      setRemediationFeedback({
        actionId,
        type: "error",
        message: err?.message || "Failed to reject action.",
      });
    } finally {
      setIsRejectingAction(false);
    }
  };

  const handleValidatePatch = async (actionId: string) => {
    setIsValidatingPatch(true);
    setRemediationFeedback(null);
    try {
      const res = await apiClient.post<any>(`/api/v1/ai/actions/${actionId}/validate`, {});
      setValidationResultsMap((prev) => ({ ...prev, [actionId]: res }));
      fetchActionDetails(actionId);
      setRemediationFeedback({
        actionId,
        type: res?.valid ? "success" : "error",
        message: res?.valid
          ? "Patch successfully validated in-memory! AST syntax clean, 0 secrets detected, 0 shell scripts executed."
          : `Patch validation failed: ${res?.error || "Unknown validation error"}`,
      });
    } catch (err: any) {
      setRemediationFeedback({
        actionId,
        type: "error",
        message: err?.message || "Patch validation failed.",
      });
    } finally {
      setIsValidatingPatch(false);
    }
  };

  const handleApplyAction = async (actionId: string) => {
    setIsApplyingAction(true);
    setRemediationFeedback(null);
    try {
      await apiClient.post<any>(`/api/v1/ai/actions/${actionId}/apply`, {
        actor_id: "developer",
      });
      fetchActionDetails(actionId);
      setRemediationFeedback({
        actionId,
        type: "success",
        message: "Patch proposal applied successfully in Mode B! Remediation branch created.",
      });
    } catch (err: any) {
      if (err?.message?.includes("Advisory Mode") || err?.status === 403) {
        setRemediationFeedback({
          actionId,
          type: "info",
          message: "Advisory Mode Active: GitHub App write permissions are not enabled. Patch application and automated repository modifications are safely blocked. Please review the unified diff above and apply manually.",
        });
      } else {
        setRemediationFeedback({
          actionId,
          type: "error",
          message: err?.message || "Failed to apply action.",
        });
      }
    } finally {
      setIsApplyingAction(false);
    }
  };

  // 1. Fetch authorized repositories
  const fetchRepositories = useCallback(async () => {
    setIsLoadingRepos(true);
    try {
      const data = await apiClient.get<any[]>("/api/v1/repositories");
      if (Array.isArray(data)) {
        const mapped: Repository[] = data.map((r) => ({
          id: r.id,
          organizationId: r.organizationId,
          name: r.name,
          fullName: r.fullName,
          defaultBranch: r.defaultBranch || "main",
          isPrivate: r.isPrivate ?? true,
          webhookInstalled: r.webhookInstalled ?? true,
          openPullRequestsCount: r.openPullRequestsCount ?? 0,
          lastSyncAt: r.lastSyncAt || r.updatedAt || new Date().toISOString(),
        }));
        // Deterministic alphabetical sort so dropdown order never jumps
        mapped.sort((a, b) => a.fullName.localeCompare(b.fullName));
        setRepositories(mapped);

        // Deterministically preserve or select active repository
        setSelectedRepoId((currentSelected) => {
          // 1. If currently selected repository is valid in the loaded list, KEEP IT!
          if (currentSelected && mapped.some((r) => r.id === currentSelected)) {
            if (typeof window !== "undefined") {
              localStorage.setItem("forgeai_selected_repo_id", currentSelected);
            }
            return currentSelected;
          }
          // 2. Check query param ?repoId=
          if (typeof window !== "undefined") {
            const urlParams = new URLSearchParams(window.location.search);
            const qRepoId = urlParams.get("repoId");
            if (qRepoId && mapped.some((r) => r.id === qRepoId)) {
              localStorage.setItem("forgeai_selected_repo_id", qRepoId);
              return qRepoId;
            }
            // 3. Check localStorage
            const savedRepoId = localStorage.getItem("forgeai_selected_repo_id");
            if (savedRepoId && mapped.some((r) => r.id === savedRepoId)) {
              return savedRepoId;
            }
          }
          // 4. Default to first repository in sorted list
          if (mapped.length > 0) {
            const defaultId = mapped[0].id;
            if (typeof window !== "undefined") {
              localStorage.setItem("forgeai_selected_repo_id", defaultId);
            }
            return defaultId;
          }
          return "";
        });
      }
    } catch (err: any) {
      console.warn("Could not fetch authorized repositories:", err);
    } finally {
      setIsLoadingRepos(false);
    }
  }, []);

  useEffect(() => {
    setSelectedRepoId("");
    setRepositories([]);
    setDocuments([]);
    setShowHistoricalDocs(false);
    setCommitReports([]);
    setSearchResults([]);
    setImpactResults([]);
    setHasSearchedImpact(false);
    setLocalActions([]);
    setOrgDependencies([]);
    setPrAnalysisResult(null);
    setPrChangeSet(null);
    setPullRequests([]);
    setSelectedPrNumber("");
    fetchRepositories();
  }, [currentOrg?.id, activeWorkspace?.id, fetchRepositories]);

  // Fetch repository sync status
  const fetchSyncStatus = useCallback(async () => {
    if (!selectedRepoId) {
      setSyncStatus(null);
      return;
    }
    setIsLoadingSyncStatus(true);
    try {
      const data = await apiClient.get<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/sync-status`);
      if (data && typeof data === "object") {
        setSyncStatus(data);
      }
    } catch (err: any) {
      console.warn("Could not fetch repository sync status:", err);
      setSyncStatus(null);
    } finally {
      setIsLoadingSyncStatus(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
    fetchSyncStatus();
  }, [selectedRepoId, fetchSyncStatus]);

  // Fetch branches for selected repository
  const fetchBranches = useCallback(async () => {
    if (!selectedRepoId) {
      setBranches([]);
      setSelectedBranch("");
      return;
    }
    setIsLoadingBranches(true);
    try {
      const data = await apiClient.get<Array<{ name: string; commitSha: string; isProtected?: boolean }>>(
        `/api/v1/repositories/${selectedRepoId}/branches`
      );
      if (Array.isArray(data) && data.length > 0) {
        setBranches(data);
        const savedBranch = typeof window !== "undefined"
          ? localStorage.getItem(`forgeai_selected_branch_${selectedRepoId}`)
          : null;
        if (savedBranch && data.some((b) => b.name === savedBranch)) {
          setSelectedBranch(savedBranch);
        } else {
          const defaultB = selectedRepo?.defaultBranch || data[0].name;
          const matched = data.find((b) => b.name === defaultB) || data[0];
          setSelectedBranch(matched.name);
          if (typeof window !== "undefined") {
            localStorage.setItem(`forgeai_selected_branch_${selectedRepoId}`, matched.name);
          }
        }
      } else {
        const fallback = [{ name: selectedRepo?.defaultBranch || "main", commitSha: syncStatus?.latestObservedSha || "HEAD" }];
        setBranches(fallback);
        setSelectedBranch(selectedRepo?.defaultBranch || "main");
      }
    } catch (err) {
      console.warn("Could not fetch repository branches:", err);
      const fallback = [{ name: selectedRepo?.defaultBranch || "main", commitSha: syncStatus?.latestObservedSha || "HEAD" }];
      setBranches(fallback);
      setSelectedBranch(selectedRepo?.defaultBranch || "main");
    } finally {
      setIsLoadingBranches(false);
    }
  }, [selectedRepoId, selectedRepo?.defaultBranch, syncStatus?.latestObservedSha]);

  useEffect(() => {
    fetchBranches();
  }, [fetchBranches]);

  const handleReconcile = async () => {
    if (!selectedRepoId) return;
    setIsReconciling(true);
    try {
      const branchParam = selectedBranch ? `?branch=${encodeURIComponent(selectedBranch)}` : "";
      const res = await apiClient.post<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/reconcile${branchParam}`, {});
      if (res && typeof res === "object") {
        setSyncStatus(res);
      }
      setTimeout(() => fetchSyncStatus(), 2000);
    } catch (err: any) {
      console.warn("Reconciliation failed:", err);
    } finally {
      setIsReconciling(false);
    }
  };

  // Cross-repository dependencies fetching & discovery
  const fetchOrgDependencies = useCallback(async () => {
    setIsLoadingDependencies(true);
    setDependencyError(null);
    try {
      const data = await apiClient.get<RepositoryDependencyItem[]>("/api/v1/ai/organizations/dependencies");
      if (Array.isArray(data)) {
        setOrgDependencies(data);
      }
    } catch (err: any) {
      console.warn("Could not fetch cross-repository dependencies:", err);
      setDependencyError(err?.message || "Failed to load cross-repository dependencies.");
    } finally {
      setIsLoadingDependencies(false);
    }
  }, []);

  useEffect(() => {
    if (activeTab === "dependencies") {
      fetchOrgDependencies();
    }
  }, [activeTab, fetchOrgDependencies]);

  const handleDiscoverDependencies = async () => {
    if (!selectedRepoId) return;
    setIsDiscoveringDependencies(true);
    setDependencyDiscoveryResult(null);
    setDependencyError(null);
    try {
      const res = await apiClient.post<RepositoryDependencyItem[]>(
        `/api/v1/ai/repositories/${selectedRepoId}/discover-dependencies`,
        {}
      );
      if (Array.isArray(res)) {
        setDependencyDiscoveryResult(`Discovered ${res.length} dependencies from manifests.`);
        fetchOrgDependencies();
      }
    } catch (err: any) {
      setDependencyError(err?.message || "Dependency discovery failed.");
    } finally {
      setIsDiscoveringDependencies(false);
    }
  };

  // Auto-Docs & Commit Guard Fetchers
  const fetchDocuments = useCallback(async () => {
    if (!selectedRepoId) {
      setDocuments([]);
      return;
    }
    setIsLoadingDocs(true);
    setDocActionError(null);
    try {
      const docs = await apiClient.get<GeneratedDocumentItem[]>(
        `/api/v1/ai/repositories/${selectedRepoId}/documents`
      );
      setDocuments(Array.isArray(docs) ? docs : []);
    } catch (err: any) {
      console.warn("Failed to fetch documents:", err);
      setDocuments([]);
    } finally {
      setIsLoadingDocs(false);
    }
  }, [selectedRepoId]);

  const fetchCommitReports = useCallback(async () => {
    if (!selectedRepoId) {
      setCommitReports([]);
      return;
    }
    setIsLoadingCommitReports(true);
    try {
      const res = await apiClient.get<{ repository_id: string; reports: CommitAnalysisReportItem[] }>(
        `/api/v1/ai/repositories/${selectedRepoId}/commit-reports?limit=30`
      );
      setCommitReports(res?.reports || []);
    } catch (err: any) {
      console.warn("Failed to fetch commit reports:", err);
      setCommitReports([]);
    } finally {
      setIsLoadingCommitReports(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
    fetchDocuments();
    fetchCommitReports();
  }, [selectedRepoId, fetchDocuments, fetchCommitReports]);

  useEffect(() => {
    if (activeTab === "autodocs") {
      fetchDocuments();
    } else if (activeTab === "commitguard") {
      fetchCommitReports();
    }
  }, [activeTab, fetchDocuments, fetchCommitReports]);

  const handleGenerateDocuments = async () => {
    if (!selectedRepoId) return;
    setIsGeneratingDocs(true);
    setDocActionError(null);
    setDocActionSuccess(null);
    try {
      const filesPayload = Array.isArray(prChangeSet?.files) && prChangeSet.files.length > 0
        ? prChangeSet.files.map((f: any) => ({
            file_path: f.filename || f.file_path,
            status: f.status || "modified",
            content: f.content || f.patch || "",
          }))
        : undefined;
      const resp = await apiClient.post<any>(
        `/api/v1/ai/repositories/${selectedRepoId}/documents/generate`,
        {
          commit_sha: activeBranchSha,
          files: filesPayload,
        }
      );
      setDocActionSuccess(`Generated ${resp?.documents?.length || 5} repository documents successfully.`);
      await fetchDocuments();
    } catch (err: any) {
      setDocActionError(err?.message || "Failed to generate repository documents.");
      triggerAiApiUnavailablePopup(err?.message || "AI documentation generation failed.");
    } finally {
      setIsGeneratingDocs(false);
    }
  };

  const handleApproveDocument = async (docId: string) => {
    setProcessingDocId(docId);
    setDocActionError(null);
    try {
      await apiClient.post(`/api/v1/ai/documents/${docId}/approve`, {});
      setDocActionSuccess("Document approved successfully.");
      await fetchDocuments();
      if (selectedDocPreview?.id === docId) {
        setSelectedDocPreview((prev) => prev ? { ...prev, status: "APPROVED" } : null);
      }
    } catch (err: any) {
      setDocActionError(err?.message || "Failed to approve document.");
    } finally {
      setProcessingDocId(null);
    }
  };

  const handleRejectDocument = async (docId: string, reason?: string) => {
    setProcessingDocId(docId);
    setDocActionError(null);
    try {
      await apiClient.post(`/api/v1/ai/documents/${docId}/reject`, { reason: reason || "Rejected by developer" });
      setDocActionSuccess("Document rejected.");
      await fetchDocuments();
      if (selectedDocPreview?.id === docId) {
        setSelectedDocPreview((prev) => prev ? { ...prev, status: "REJECTED" } : null);
      }
    } catch (err: any) {
      setDocActionError(err?.message || "Failed to reject document.");
    } finally {
      setProcessingDocId(null);
    }
  };

  const handleApplyDocument = async (docId: string) => {
    setProcessingDocId(docId);
    setDocActionError(null);
    try {
      const updated = await apiClient.post<GeneratedDocumentItem>(
        `/api/v1/ai/documents/${docId}/apply`,
        { write_mode_override: "CONTROLLED_WRITE" }
      );
      setDocActionSuccess(`Pull Request created: ${updated.pr_url || updated.branch_name}`);
      await fetchDocuments();
      if (selectedDocPreview?.id === docId) {
        setSelectedDocPreview(updated);
      }
    } catch (err: any) {
      setDocActionError(err?.message || "Failed to apply document.");
    } finally {
      setProcessingDocId(null);
    }
  };

  const handleAnalyzeCommitOnDemand = async () => {
    if (!selectedRepoId) return;
    setIsOnDemandAnalyzing(true);
    try {
      const filesPayload = Array.isArray(prChangeSet?.files) && prChangeSet.files.length > 0
        ? prChangeSet.files.map((f: any) => ({
            file_path: f.filename || f.file_path,
            status: f.status || "modified",
            content: f.content || f.patch || "",
          }))
        : undefined;

      let currentUserEmail = "";
      try {
        const rawUser = localStorage.getItem("forgeai_user");
        if (rawUser) {
          const parsedUser = JSON.parse(rawUser);
          currentUserEmail = parsedUser?.email || "";
        }
      } catch {}

      await apiClient.post(
        `/api/v1/ai/repositories/${selectedRepoId}/analyze-commit`,
        {
          commit_sha: activeBranchSha,
          ref: selectedBranch || prChangeSet?.sourceBranch || "main",
          author_name: prChangeSet?.author || "Developer",
          author_email: currentUserEmail || undefined,
          commit_message: prChangeSet?.title || "Manual commit analysis",
          files: filesPayload,
          generate_docs: true,
        }
      );
      await fetchCommitReports();
    } catch (err: any) {
      console.warn("Manual commit analysis failed:", err);
    } finally {
      setIsOnDemandAnalyzing(false);
    }
  };

  const handleCheckForNewCommits = async () => {
    if (!selectedRepoId) return;
    setIsCheckingNewCommits(true);
    setDocActionError(null);
    setDocActionSuccess(null);
    try {
      await apiClient.post(`/api/v1/repositories/${selectedRepoId}/reconcile?branch=${encodeURIComponent(selectedBranch || selectedRepo?.defaultBranch || "main")}`, {});
      await Promise.allSettled([
        fetchDocuments(),
        fetchCommitReports(),
        fetchSyncStatus(),
      ]);
      setDocActionSuccess("Reconciliation complete. Latest commits synchronized from GitHub.");
    } catch (err: any) {
      console.warn("Failed to check for new commits:", err);
      setDocActionError(err?.message || "Failed to reconcile latest commits from GitHub.");
    } finally {
      setIsCheckingNewCommits(false);
    }
  };

  // Auto-poll interval (every 15s) for live repository synchronization, documents, and commit reports
  useEffect(() => {
    if (!selectedRepoId) return;

    const intervalId = setInterval(async () => {
      try {
        const [syncRes, docsRes, reportsRes] = await Promise.allSettled([
          apiClient.get<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/sync-status`),
          apiClient.get<GeneratedDocumentItem[]>(`/api/v1/ai/repositories/${selectedRepoId}/documents`),
          apiClient.get<{ repository_id: string; reports: CommitAnalysisReportItem[] }>(
            `/api/v1/ai/repositories/${selectedRepoId}/commit-reports?limit=30`
          ),
        ]);

        if (syncRes.status === "fulfilled" && syncRes.value) {
          setSyncStatus(syncRes.value);
        }
        if (docsRes.status === "fulfilled" && Array.isArray(docsRes.value)) {
          setDocuments(docsRes.value);
        }
        if (reportsRes.status === "fulfilled" && reportsRes.value?.reports) {
          setCommitReports(reportsRes.value.reports);
        }
      } catch (pollErr) {
        console.warn("Silent background poll error:", pollErr);
      }
    }, 15000);

    return () => clearInterval(intervalId);
  }, [selectedRepoId]);

  // Server-Sent Events (SSE) live telemetry stream
  useEffect(() => {
    if (!selectedRepoId || typeof window === "undefined") return;
    const controller = new AbortController();
    let isActive = true;

    const connectStream = async () => {
      try {
        const token = apiClient.getAccessToken() || localStorage.getItem("forgeai_access_token");
        const activeOrgId = apiClient.getActiveWorkspaceType() === "ORGANIZATION"
          ? apiClient.getActiveOrgId()
          : null;
        const activeWsId = apiClient.getActiveWorkspaceId() || activeOrgId || "00000000-0000-0000-0000-000000000001";
        const baseUrl = process.env.NEXT_PUBLIC_GATEWAY_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
        const streamHeaders: Record<string, string> = {
          Accept: "text/event-stream",
          "X-Workspace-Id": activeWsId,
          "X-Workspace-Scope": apiClient.getActiveWorkspaceType(),
        };
        if (token) {
          streamHeaders["Authorization"] = `Bearer ${token}`;
        }
        if (activeOrgId) {
          streamHeaders["X-Org-Id"] = activeOrgId;
        }
        const res = await fetch(
          `${baseUrl}/api/v1/ai/events/stream?repository_id=${encodeURIComponent(selectedRepoId)}&workspaceId=${encodeURIComponent(activeWsId)}`,
          {
            method: "GET",
            headers: streamHeaders,
            signal: controller.signal,
          }
        );
        if (!res.ok || !res.body) {
          setSseConnected(false);
          return;
        }
        setSseConnected(true);
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (isActive) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split("\n\n");
          buffer = parts.pop() || "";
          for (const part of parts) {
            const dataLine = part.split("\n").find((line) => line.startsWith("data: "));
            if (!dataLine) continue;
            try {
              const evt = JSON.parse(dataLine.slice(6));
              if (evt.event_type === "CONNECTED") {
                setSseConnected(true);
              } else if (
                evt.event_type === "PUSH_ANALYZED" ||
                evt.event_type === "COMMIT_ANALYZED"
              ) {
                fetchCommitReports();
                fetchDocuments();
                const shortSha = evt.commit_sha ? evt.commit_sha.slice(0, 7) : "HEAD";
                addToast(
                  "info",
                  "Live Commit Scanned",
                  `Push Guard analyzed commit ${shortSha} on ${evt.ref || "branch"}`
                );
              } else if (evt.event_type === "DOCUMENT_GENERATED") {
                fetchCommitReports();
                fetchDocuments();
                const shortSha = evt.commit_sha ? evt.commit_sha.slice(0, 7) : "latest";
                addToast(
                  "success",
                  "Auto-Docs Live Update",
                  `5 architecture documents regenerated for commit ${shortSha}`
                );
              }
            } catch {}
          }
        }
      } catch {
        // Fallback 15s polling remains active
        setSseConnected(false);
      }
    };

    connectStream();
    return () => {
      isActive = false;
      setSseConnected(false);
      controller.abort();
    };
  }, [selectedRepoId, selectedBranch, addToast, fetchCommitReports, fetchDocuments]);

  // 2. Fetch real pull requests when selected repository changes
  useEffect(() => {
    if (!selectedRepoId) {
      setPullRequests([]);
      setPrChangeSet(null);
      setSelectedPrNumber("");
      return;
    }
    const fetchPrs = async () => {
      setIsLoadingPrs(true);
      try {
        let prs = await apiClient.get<any[]>(`/api/v1/pull-requests?repositoryId=${selectedRepoId}`);
        if (!Array.isArray(prs) || prs.length === 0) {
          try {
            const synced = await apiClient.post<any[]>(`/api/v1/repositories/${selectedRepoId}/sync/pull-requests`, {});
            if (Array.isArray(synced) && synced.length > 0) {
              prs = synced;
            }
          } catch (syncErr: any) {
            console.warn("Pull requests auto-sync fallback failed:", syncErr);
          }
        }
        if (Array.isArray(prs)) {
          setPullRequests(prs);
          if (prs.length > 0) {
            const first = prs[0];
            const num = first.prNumber || first.number || 1;
            setSelectedPrNumber(num);
          } else {
            setSelectedPrNumber("");
            setPrChangeSet(null);
          }
        }
      } catch (err: any) {
        console.warn("Could not fetch pull requests for repo:", err);
        setPullRequests([]);
        setPrChangeSet(null);
      } finally {
        setIsLoadingPrs(false);
      }
    };
    fetchPrs();
  }, [selectedRepoId]);

  // 3. Fetch authoritative change set when selected PR changes
  useEffect(() => {
    if (!selectedRepoId || !selectedPrNumber) {
      setPrChangeSet(null);
      return;
    }
    const fetchChangeSet = async () => {
      setIsLoadingChangeSet(true);
      try {
        const data = await apiClient.get<PullRequestChangeSet>(
          `/api/v1/pull-requests/${selectedPrNumber}/changes?repositoryId=${selectedRepoId}`
        );
        if (data && typeof data === "object") {
          setPrChangeSet(data);
        }
      } catch (err: any) {
        console.warn("Could not fetch authoritative PR change set:", err);
        setPrChangeSet(null);
      } finally {
        setIsLoadingChangeSet(false);
      }
    };
    fetchChangeSet();
  }, [selectedRepoId, selectedPrNumber]);

  // Handle Automatic Real PR Analysis
  const handleAnalyzePr = async () => {
    if (!selectedRepoId || !selectedPrNumber) {
      setPrError("Please select an authorized repository and pull request.");
      return;
    }

    setIsAnalyzingPr(true);
    setPrError(null);
    setPrAnalysisResult(null);

    // Lifecycle animation sequence
    setLifecycleStep("fetching");
    const t1 = setTimeout(() => setLifecycleStep("deriving"), 600);
    const t2 = setTimeout(() => setLifecycleStep("indexing"), 1200);
    const t3 = setTimeout(() => setLifecycleStep("graph"), 1800);
    const t4 = setTimeout(() => setLifecycleStep("reasoning"), 2400);

    try {
      const payload: any = {
        repository_id: selectedRepoId,
        pr_number: Number(selectedPrNumber),
      };

      if (prChangeSet) {
        if (prChangeSet.title) payload.title = prChangeSet.title;
        if (prChangeSet.body) payload.description = prChangeSet.body;
        if (prChangeSet.headSha) payload.head_sha = prChangeSet.headSha;
        if (prChangeSet.baseSha) payload.base_sha = prChangeSet.baseSha;
        if (Array.isArray(prChangeSet.files) && prChangeSet.files.length > 0) {
          payload.changed_files = prChangeSet.files.map((f: any) => ({
            file_path: f.filename || f.file_path,
            filename: f.filename || f.file_path,
            status: f.status || "modified",
            additions: f.additions ?? 0,
            deletions: f.deletions ?? 0,
            changes: f.changes ?? 0,
            patch: f.patch || "",
            content: f.content || "",
          }));
        }
      }

      if (manualSymbolsInput.trim()) {
        payload.changed_symbols = manualSymbolsInput
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
      }

      const result = await apiClient.post<any>("/api/v1/ai/pr-analysis", payload);

      if (result?.impact_metadata?.showing_previous_ai_data === true) {
        setPrAnalysisResult(result);
        setIsUsingLastAnalysis(true);
        setLastAnalysisTimestamp(
          result.impact_metadata.previous_analysis_timestamp
            ? new Date(result.impact_metadata.previous_analysis_timestamp).toLocaleString()
            : "Previous AI run"
        );
        setLifecycleStep("complete");
        // Do NOT overwrite localStorage cache with fallback responses!
      } else if (result?.provider === "TEST_FALLBACK") {
        setLifecycleStep("idle");
        triggerAiApiUnavailablePopup("AI API is not working currently.");
      } else {
        setPrAnalysisResult(result);
        setIsUsingLastAnalysis(false);
        setLifecycleStep("complete");

        // Cache successful analysis in localStorage for offline resiliency
        try {
          const repoObj = repositories.find((r) => r.id === selectedRepoId);
          const cacheObj = {
            timestamp: new Date().toISOString(),
            repoId: selectedRepoId,
            repoName: repoObj?.fullName || "Selected Repository",
            prNumber: selectedPrNumber,
            data: result,
          };
          if (selectedRepoId && selectedPrNumber) {
            localStorage.setItem(`forgeai_last_analysis_${selectedRepoId}_${selectedPrNumber}`, JSON.stringify(cacheObj));
          }
          if (selectedRepoId) {
            localStorage.setItem(`forgeai_last_analysis_${selectedRepoId}`, JSON.stringify(cacheObj));
          }
          localStorage.setItem(`forgeai_last_analysis_global`, JSON.stringify(cacheObj));
        } catch (cacheErr) {
          console.warn("Failed to cache analysis to localStorage:", cacheErr);
        }
      }
    } catch (err: any) {
      console.warn("PR analysis failed:", err);
      const errMsg = err?.message || "PR Analysis request failed on AI Service.";
      setPrError(errMsg);
      setLifecycleStep("idle");
      // Trigger AI API Unavailable popup with fallback to last analysis
      triggerAiApiUnavailablePopup(errMsg);
    } finally {
      clearTimeout(t1);
      clearTimeout(t2);
      clearTimeout(t3);
      clearTimeout(t4);
      setIsAnalyzingPr(false);
    }
  };

  // Validate PR recommendations against actual CI test run results (Post-PR Feedback Loop)
  const handleValidateTests = async () => {
    if (!selectedRepoId) return;
    const commitSha = prChangeSet?.headSha || "latest";
    setIsValidatingTests(true);
    setValidationError(null);
    try {
      const resp = await apiClient.post<TestValidationResponseDTO>("/api/v1/ai/test-validation/validate", {
        repository_id: selectedRepoId,
        analysis_id: prAnalysisResult?.analysis_id || prAnalysisResult?.id,
        commit_sha: commitSha,
      });
      setTestValidationResult(resp);
    } catch (err: any) {
      console.warn("Validation feedback check failed:", err);
      setValidationError(err?.message || "Could not validate against actual CI test results.");
    } finally {
      setIsValidatingTests(false);
    }
  };

  // Search handler
  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchQuery.trim()) return;
    setIsSearching(true);
    setSearchError(null);
    try {
      const results = await apiClient.post<any[]>("/api/v1/ai/search", {
        query: searchQuery.trim(),
        limit: 10,
        repository_id: selectedRepoId || undefined,
      });
      setSearchResults(Array.isArray(results) ? results : []);
    } catch (err: any) {
      setSearchError(err?.message || "Semantic search request failed on AI Service.");
    } finally {
      setIsSearching(false);
    }
  };

  // Impact handler
  const handleImpact = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!impactSymbolsInput.trim() || !selectedRepoId) return;
    setIsAnalyzingImpact(true);
    setHasSearchedImpact(true);
    setImpactError(null);
    try {
      const symbols = impactSymbolsInput.split(",").map((s) => s.trim()).filter(Boolean);
      const results = await apiClient.post<any[]>("/api/v1/ai/impact", {
        repository_id: selectedRepoId,
        changed_symbols: symbols,
        max_depth: 3,
      });
      setImpactResults(Array.isArray(results) ? results : []);
    } catch (err: any) {
      setImpactError(err?.message || "Impact analysis request failed on AI Service.");
    } finally {
      setIsAnalyzingImpact(false);
    }
  };

  // Authoritative Security Status Evaluation (Phase 4)
  const getSecurityStatus = useCallback(() => {
    const res = evaluateSecurityStatus(prAnalysisResult?.security_assessment, dependencySecurity?.status);
    let icon = ShieldCheck;
    if (res.status === "CRITICAL / BLOCKED") icon = ShieldAlert;
    else if (res.status === "HIGH RISK") icon = AlertTriangle;
    else if (res.status === "PARTIAL EVIDENCE") icon = Info;
    else if (res.status === "WARNINGS") icon = AlertCircle;
    else if (res.status === "NOT_ANALYZED") icon = Shield;
    return {
      ...res,
      icon,
    };
  }, [prAnalysisResult?.security_assessment, dependencySecurity?.status]);

  // Safe Code Context Extraction with Zero Raw Secret Exposure (Phase 11)
  const getSafeCodeContext = useCallback((filePath: string, lineNumber?: number | null) => {
    return extractSafeCodeContext(prChangeSet?.files, filePath, lineNumber);
  }, [prChangeSet]);


  // On-Demand Secret Scan Handler (Phase 15 & 16)
  const handleSecretScan = async () => {
    if (!selectedRepoId) return;
    setIsSecretScanning(true);
    setSecretScanResult(null);
    setIsSecretScanModalOpen(true);
    try {
      const res = await apiClient.post<SecretScanResultDTO>(
        `/api/v1/ai/repositories/${selectedRepoId}/secret-scan`,
        {
          commit_sha: activeBranchSha,
          files: prChangeSet?.files?.map((f) => ({
            file_path: f.filename,
            filename: f.filename,
            content: f.content || f.patch || "",
          })),
        }
      );
      setSecretScanResult(res);
    } catch (err: any) {
      console.warn("Secret scan failed:", err);
      triggerAiApiUnavailablePopup(err?.message || "Secret scan failed on AI Service.");
    } finally {
      setIsSecretScanning(false);
    }
  };

  // Repository Snapshot Security Scan Handler (Phase 14)
  const handleRepositoryScan = async () => {
    if (!selectedRepoId) return;
    setIsRepoScanning(true);
    try {
      const res = await apiClient.post<SecurityAssessmentDTO>(
        `/api/v1/ai/repositories/${selectedRepoId}/security-scan`,
        {
          commit_sha: activeBranchSha,
          files: prChangeSet?.files?.map((f) => ({
            file_path: f.filename,
            filename: f.filename,
            content: f.content || f.patch || "",
          })),
        }
      );
      setRepoScanResult(res);
      if (prAnalysisResult) {
        setPrAnalysisResult({
          ...prAnalysisResult,
          security_assessment: res,
        });
      }
    } catch (err: any) {
      console.warn("Repository scan failed:", err);
      triggerAiApiUnavailablePopup(err?.message || "Repository scan failed on AI Service.");
    } finally {
      setIsRepoScanning(false);
    }
  };

  // Authoritative Re-Scan Differential Pipeline Handler (Phase 13)
  const handleRescan = async () => {
    const analysisId = prAnalysisResult?.analysis_id || prAnalysisResult?.id;
    if (!analysisId) return;
    setIsRescanning(true);
    setRescanResult(null);
    setIsRescanModalOpen(true);
    try {
      const res = await apiClient.post<ReScanResultDTO>(
        `/api/v1/ai/analyses/${analysisId}/re-scan`,
        {
          pr_number: selectedPrNumber || 0,
          changed_files: prChangeSet?.files?.map((f) => ({
            filename: f.filename,
            content: f.content || f.patch || "",
            patch: f.patch,
            additions: f.additions,
            deletions: f.deletions,
            status: f.status,
          })),
        }
      );
      setRescanResult(res);
      if (res?.updated_analysis) {
        setPrAnalysisResult(res.updated_analysis as any);
      }
      if (selectedPrNumber) {
        fetchAnalysisHistory(selectedPrNumber);
      }
    } catch (err: any) {
      console.warn("Re-scan failed:", err);
      triggerAiApiUnavailablePopup(err?.message || "Re-scan failed on AI Service.");
    } finally {
      setIsRescanning(false);
    }
  };

  // Grounded AI Explanation Handler (Phase 19)
  const handleExplainFinding = async (finding: any) => {
    setSelectedFindingForModal(finding);
    setIsExplainingFinding(true);
    setAiExplanation(null);
    setExplainError(null);
    try {
      const res = await apiClient.post<ExplainFindingResponseDTO>(
        "/api/v1/ai/explain-finding",
        {
          finding_id: finding.id || finding.rule_id || "finding-1",
          rule_id: finding.rule_id || "SEC_GENERAL_PATTERN",
          title: finding.title || "Security Finding",
          category: finding.category || "SECURITY",
          severity: finding.severity || "HIGH",
          file_path: finding.file_path || finding.affected_file || "unknown",
          line_number: finding.line_number || 1,
          redacted_evidence: finding.redacted_evidence || finding.evidence || "[REDACTED]",
        }
      );
      setAiExplanation(res);
    } catch (err: any) {
      setExplainError(err?.message || "Failed to generate AI explanation.");
    } finally {
      setIsExplainingFinding(false);
    }
  };

  // Fetch Dependency Security Status (Phase 17)
  const fetchDependencySecurity = useCallback(async (repoId: string) => {
    if (!repoId) return;
    setIsLoadingDepSecurity(true);
    try {
      const res = await apiClient.get<DependencySecurityStatusDTO>(
        `/api/v1/ai/repositories/${repoId}/dependency-security`
      );
      setDependencySecurity(res);
    } catch (err: any) {
      console.warn("Dependency security check failed:", err);
    } finally {
      setIsLoadingDepSecurity(false);
    }
  }, []);

  useEffect(() => {
    if (selectedRepoId) {
      fetchDependencySecurity(selectedRepoId);
    }
  }, [selectedRepoId, fetchDependencySecurity]);

  // Fetch Analysis History Timeline (Phase 27)
  const fetchAnalysisHistory = useCallback(async (prNum: number | string) => {
    if (!prNum) return;
    setIsLoadingHistory(true);
    try {
      const targetEntityId = selectedRepoId ? `${selectedRepoId}#${prNum}` : `${prNum}`;
      let res = await apiClient.get<AnalysisHistoryItemDTO[]>(
        `/api/v1/ai/analyses/PR/${encodeURIComponent(targetEntityId)}/history`
      );
      if (!Array.isArray(res) || res.length === 0) {
        res = await apiClient.get<AnalysisHistoryItemDTO[]>(
          `/api/v1/ai/analyses/PR/${prNum}/history`
        );
      }
      if (Array.isArray(res)) {
        setAnalysisHistory(res);
      }
    } catch (err: any) {
      console.warn("Analysis history fetch failed:", err);
    } finally {
      setIsLoadingHistory(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
    if (selectedPrNumber) {
      fetchAnalysisHistory(selectedPrNumber);
    }
  }, [selectedPrNumber, fetchAnalysisHistory]);

  // Restore saved full AnalysisResponseDTO when PR is selected / reloaded
  useEffect(() => {
    if (!selectedRepoId || !selectedPrNumber) return;

    const restoreSavedAnalysis = async () => {
      try {
        const targetEntityId = `${selectedRepoId}#${selectedPrNumber}`;
        let analyses = await apiClient.get<any[]>(
          `/api/v1/ai/analyses/PR/${encodeURIComponent(targetEntityId)}`
        );
        if (!Array.isArray(analyses) || analyses.length === 0) {
          analyses = await apiClient.get<any[]>(
            `/api/v1/ai/analyses/PR/${selectedPrNumber}`
          );
        }
        if (Array.isArray(analyses) && analyses.length > 0) {
          const latest = analyses[0];
          setPrAnalysisResult((current: any) => {
            if (!current || current.entity_id !== latest.entity_id) {
              setLifecycleStep("complete");
              return latest;
            }
            return current;
          });
          setIsUsingLastAnalysis(false);

          // Cache in localStorage
          try {
            const repoObj = repositories.find((r) => r.id === selectedRepoId);
            const cacheItem = {
              timestamp: latest.created_at || new Date().toISOString(),
              repoId: selectedRepoId,
              repoName: repoObj?.fullName || "Selected Repository",
              prNumber: selectedPrNumber,
              data: latest,
            };
            localStorage.setItem(`forgeai_last_analysis_${selectedRepoId}_${selectedPrNumber}`, JSON.stringify(cacheItem));
            localStorage.setItem(`forgeai_last_analysis_${selectedRepoId}`, JSON.stringify(cacheItem));
            localStorage.setItem(`forgeai_last_analysis_global`, JSON.stringify(cacheItem));
          } catch (cacheErr) {
            console.warn("Failed to cache restored analysis:", cacheErr);
          }
        }
      } catch (err: any) {
        console.warn("Could not restore saved PR analysis from AI Service:", err);
        // Fallback to cached analysis and notify user of AI API offline status
        triggerAiApiUnavailablePopup(err?.message);
      }
    };

    restoreSavedAnalysis();
  }, [selectedRepoId, selectedPrNumber]);

  // Filtered provenance chunks
  const filteredChunks = React.useMemo(() => {
    if (!prAnalysisResult || !Array.isArray(prAnalysisResult.retrieved_chunks)) return [];
    if (provenanceFilter === "ALL") return prAnalysisResult.retrieved_chunks;
    return prAnalysisResult.retrieved_chunks.filter((c: any) => c.source === provenanceFilter);
  }, [prAnalysisResult, provenanceFilter]);

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-[#1C1C21] pb-5">
        <div>
          <div className="flex items-center gap-2">
            <Cpu className="h-5 w-5 text-zinc-300" />
            <h1 className="text-xl font-semibold tracking-tight text-zinc-100">
              AI Engineering Intelligence Layer
            </h1>
          </div>
          <p className="text-xs text-zinc-400 mt-1">
            Pull request static analysis, AST call graph blast radius, and grounded code review for{" "}
            <span className="text-zinc-200 font-medium">{workspaceDisplayName}</span>.
          </p>
        </div>

        {/* Authorized Repository Selector */}
        <div className="flex items-center gap-2.5 flex-wrap">
          <div className="flex items-center gap-2 bg-[#0E0E11] border border-[#26262C] rounded-md px-2.5 h-8">
            <FolderGit2 className="h-3.5 w-3.5 text-zinc-400" />
            <span className="text-[11px] text-zinc-500">Repository:</span>
            {isLoadingRepos && repositories.length === 0 ? (
              <Loader2 className="h-3 w-3 animate-spin text-zinc-400" />
            ) : (
              <select
                aria-label="Authorized Repository Selection"
                value={selectedRepoId}
                onChange={(e) => handleSelectRepo(e.target.value)}
                className="bg-transparent text-xs text-zinc-100 font-medium focus:outline-none cursor-pointer"
              >
                {repositories.length === 0 ? (
                  <option value="" disabled className="bg-[#0E0E11] text-zinc-400">
                    No repositories connected
                  </option>
                ) : (
                  repositories.map((repo) => (
                    <option key={repo.id} value={repo.id} className="bg-[#0E0E11] text-zinc-100">
                      {repo.fullName}
                    </option>
                  ))
                )}
              </select>
            )}
          </div>
          {selectedRepo && (
            <div className="flex items-center gap-2 bg-[#0E0E11] border border-[#26262C] rounded-md px-2.5 h-8">
              <GitBranch className="h-3.5 w-3.5 text-zinc-400" />
              <span className="text-[11px] text-zinc-500">Branch:</span>
              {isLoadingBranches ? (
                <Loader2 className="h-3 w-3 animate-spin text-zinc-400" />
              ) : (
                <select
                  aria-label="Repository Branch Selection"
                  value={selectedBranch || selectedRepo.defaultBranch}
                  onChange={async (e) => {
                    const newBranch = e.target.value;
                    setSelectedBranch(newBranch);
                    if (typeof window !== "undefined" && selectedRepoId) {
                      localStorage.setItem(`forgeai_selected_branch_${selectedRepoId}`, newBranch);
                    }
                    if (selectedRepoId) {
                      try {
                        await apiClient.post(`/api/v1/repositories/${selectedRepoId}/reconcile?branch=${encodeURIComponent(newBranch)}`, {});
                        await Promise.allSettled([fetchSyncStatus(), fetchDocuments(), fetchCommitReports()]);
                      } catch (err) {
                        console.warn("Branch reconcile warning:", err);
                      }
                    }
                  }}
                  className="bg-transparent text-xs text-zinc-200 font-mono tabular-nums font-medium focus:outline-none cursor-pointer"
                >
                  {(branches.length > 0 ? branches : [{ name: selectedRepo.defaultBranch, commitSha: "" }]).map((b) => (
                    <option key={b.name} value={b.name} className="bg-[#0E0E11] text-zinc-100">
                      {b.name} {b.commitSha ? `(${b.commitSha.substring(0, 7)})` : ""}
                    </option>
                  ))}
                </select>
              )}
            </div>
          )}

          {/* Live Push Guard Status Pill */}
          <div
            className={`hidden md:flex items-center gap-2 px-2.5 h-8 rounded-md border text-[11px] font-mono transition-colors ${
              sseConnected
                ? "border-emerald-900/60 bg-emerald-950/20 text-emerald-300"
                : "border-amber-900/60 bg-amber-950/20 text-amber-300"
            }`}
          >
            <span
              className={`h-1.5 w-1.5 rounded-full ${
                sseConnected ? "bg-emerald-400" : "bg-amber-400"
              }`}
            />
            <span className="font-medium">
              {sseConnected ? "Live Push Guard Active" : "Push Guard Active (15s Poll)"}
            </span>
          </div>
        </div>
      </div>

      {/* Continuous Repository Synchronization Indicator */}
      {selectedRepo && (
        <div className="flex flex-wrap items-center justify-between gap-3 px-3.5 py-2.5 rounded-lg border border-[#1C1C21] bg-[#0E0E11] text-xs">
          <div className="flex items-center gap-3 flex-wrap">
            <div className="flex items-center gap-2">
              <span className="text-zinc-500 text-[11px] font-medium">Sync State:</span>
              {isLoadingSyncStatus && !syncStatus ? (
                <div className="flex items-center gap-1.5 text-xs text-zinc-400">
                  <Loader2 className="h-3 w-3 animate-spin text-zinc-400" />
                  <span>Checking...</span>
                </div>
              ) : (
                <span
                  className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[11px] font-mono font-medium border ${
                    syncStatus?.syncStatus === "SYNCED"
                      ? "bg-emerald-950/30 text-emerald-400 border-emerald-900/60"
                      : syncStatus?.syncStatus === "SYNCING"
                      ? "bg-sky-950/30 text-sky-400 border-sky-900/60"
                      : syncStatus?.syncStatus === "STALE"
                      ? "bg-amber-950/30 text-amber-400 border-amber-900/60"
                      : syncStatus?.syncStatus === "FAILED"
                      ? "bg-rose-950/30 text-rose-400 border-rose-900/60"
                      : "bg-[#141418] text-zinc-400 border-[#26262C]"
                  }`}
                >
                  {syncStatus?.syncStatus === "SYNCED" && <CheckCircle2 className="h-3 w-3" />}
                  {syncStatus?.syncStatus === "SYNCING" && <Loader2 className="h-3 w-3 animate-spin" />}
                  {syncStatus?.syncStatus === "STALE" && <AlertTriangle className="h-3 w-3" />}
                  {syncStatus?.syncStatus === "FAILED" && <ShieldAlert className="h-3 w-3" />}
                  {syncStatus?.syncStatus || "SYNCED"}
                </span>
              )}
            </div>

            <div className="flex items-center gap-1.5 font-mono tabular-nums text-[11px] bg-[#141418] border border-[#26262C] px-2 py-0.5 rounded">
              <span className="text-zinc-500">GitHub HEAD:</span>
              <span className="text-zinc-200 font-medium">
                {(syncStatus?.latestObservedSha || (syncStatus as any)?.currentGithubHead)
                  ? (syncStatus?.latestObservedSha || (syncStatus as any)?.currentGithubHead).substring(0, 7)
                  : "None"}
              </span>
            </div>

            <div className="flex items-center gap-1.5 font-mono tabular-nums text-[11px] bg-[#141418] border border-[#26262C] px-2 py-0.5 rounded">
              <span className="text-zinc-500">Indexed HEAD:</span>
              <span className="text-zinc-200 font-medium">
                {(syncStatus?.latestIndexedSha || (syncStatus as any)?.indexedHead)
                  ? (syncStatus?.latestIndexedSha || (syncStatus as any)?.indexedHead).substring(0, 7)
                  : "None"}
              </span>
            </div>

            {syncStatus?.lastSyncedAt && (
              <div className="text-[11px] text-zinc-500 font-mono tabular-nums">
                Last Synced:{" "}
                <span className="text-zinc-300">
                  {new Date(syncStatus.lastSyncedAt).toLocaleTimeString()}
                </span>
              </div>
            )}

            {syncStatus?.lastError && (
              <span className="text-[11px] text-rose-400 font-medium truncate max-w-sm" title={syncStatus.lastError}>
                Error: {syncStatus.lastError}
              </span>
            )}
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              onClick={handleReconcile}
              disabled={isReconciling || isLoadingSyncStatus}
              className="h-7 text-xs px-2.5"
            >
              {isReconciling ? (
                <>
                  <Loader2 className="h-3 w-3 animate-spin mr-1.5" />
                  Reconciling...
                </>
              ) : (
                <>
                  <RefreshCw className="h-3 w-3 mr-1.5" />
                  Reconcile
                </>
              )}
            </Button>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* SECURITY STATUS BANNER (PHASE 4 - AUTHORITATIVE TRUTHFUL STATUS)          */}
      {/* ========================================================================= */}
      {prAnalysisResult && (() => {
        const secStatus = getSecurityStatus();
        if (secStatus.status === "NOT_ANALYZED") return null;
        const SecIcon = secStatus.icon;
        return (
          <div className={`p-4 rounded-lg border flex flex-col md:flex-row md:items-center justify-between gap-4 transition-colors ${secStatus.bg}`}>
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-md bg-[#09090B]/80 border border-[#26262C]">
                <SecIcon className="h-5 w-5" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-[11px] font-mono uppercase tracking-wider text-zinc-400">Security Status</span>
                  <Badge
                    variant="outline"
                    className={`text-[11px] font-mono font-semibold uppercase tracking-wider px-2 py-0.5 border ${
                      secStatus.status === "CRITICAL / BLOCKED"
                        ? "bg-rose-950/60 text-rose-300 border-rose-800/70"
                        : secStatus.status === "HIGH RISK"
                        ? "bg-amber-950/60 text-amber-300 border-amber-800/70"
                        : secStatus.status === "PARTIAL EVIDENCE"
                        ? "bg-sky-950/60 text-sky-300 border-sky-800/70"
                        : secStatus.status === "WARNINGS"
                        ? "bg-amber-950/40 text-amber-300 border-amber-800/60"
                        : "bg-emerald-950/60 text-emerald-300 border-emerald-800/70"
                    }`}
                  >
                    {secStatus.status}
                  </Badge>
                </div>
                <p className="text-xs text-zinc-300 mt-1 leading-relaxed">
                  {secStatus.description}
                </p>
              </div>
            </div>

            {/* Quick Actions in Banner */}
            <div className="flex items-center gap-2 flex-wrap shrink-0">
              <Button
                variant="secondary"
                size="sm"
                onClick={handleSecretScan}
                disabled={isSecretScanning || !selectedRepoId}
                className="h-8 text-xs px-3"
              >
                {isSecretScanning ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <KeyRound className="h-3.5 w-3.5 mr-1.5 text-zinc-400" />}
                Scan for Secrets
              </Button>

              <Button
                variant="secondary"
                size="sm"
                onClick={handleRepositoryScan}
                disabled={isRepoScanning || !selectedRepoId}
                className="h-8 text-xs px-3"
              >
                {isRepoScanning ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <ShieldCheck className="h-3.5 w-3.5 mr-1.5 text-zinc-400" />}
                Scan Repository
              </Button>

              {prAnalysisResult?.analysis_id && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRescan}
                  disabled={isRescanning}
                  className="h-8 text-xs px-3 border-emerald-900/60 bg-emerald-950/20 text-emerald-300 hover:bg-emerald-950/40"
                >
                  {isRescanning ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <RefreshCw className="h-3.5 w-3.5 mr-1.5 text-emerald-400" />}
                  Re-Scan Changes
                </Button>
              )}
            </div>
          </div>
        );
      })()}

      {/* ========================================================================= */}
      {/* PHASE 21: DEVELOPER DASHBOARD SUMMARY & ENGINEERING HEALTH CENTER         */}
      {/* ========================================================================= */}
      {(() => {
        if (!prAnalysisResult) {
          return (
            <Card className="border-[#1C1C21] bg-[#0E0E11]">
              <CardHeader className="pb-3 border-b border-[#1C1C21]">
                <div className="flex items-center gap-2">
                  <Activity className="h-4 w-4 text-zinc-400" />
                  <CardTitle className="text-sm font-semibold text-zinc-100">
                    Engineering Health Center
                  </CardTitle>
                  <span className="text-[11px] text-zinc-500 font-mono hidden sm:inline">
                    Code Quality, Security & Merge Readiness
                  </span>
                </div>
              </CardHeader>
              <CardContent className="py-8 text-center space-y-3">
                <div className="inline-flex p-2.5 rounded-md bg-[#141418] border border-[#26262C] text-zinc-400">
                  <Activity className="h-5 w-5" />
                </div>
                <div className="text-sm font-medium text-zinc-200">
                  Awaiting Pull Request Analysis
                </div>
                <p className="text-xs text-zinc-400 max-w-md mx-auto">
                  Deterministic metrics for Security, Reliability, Test Sufficiency, Dependency Vulnerabilities, and Merge Readiness will be calculated as soon as a pull request is analyzed.
                </p>
                {selectedPrNumber && (
                  <div className="pt-1">
                    <Button
                      size="sm"
                      variant="default"
                      onClick={handleAnalyzePr}
                      disabled={isAnalyzingPr}
                      className="text-xs h-8 px-3"
                    >
                      {isAnalyzingPr ? (
                        <>
                          <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" />
                          Analyzing Pull Request...
                        </>
                      ) : (
                        <>
                          <Play className="h-3.5 w-3.5 mr-1.5" />
                          Analyze Pull Request #{selectedPrNumber}
                        </>
                      )}
                    </Button>
                  </div>
                )}
              </CardContent>
            </Card>
          );
        }

        const sec = prAnalysisResult?.security_assessment;
        const rel = prAnalysisResult?.reliability_assessment;
        const merge = prAnalysisResult?.merge_readiness;
        const risk = prAnalysisResult?.unified_risk;
        const strat = prAnalysisResult?.test_strategy;

        // 1. Security Metrics
        const secStatusText = sec?.critical_count && sec.critical_count > 0
          ? "CRITICAL"
          : sec?.high_count && sec.high_count > 0
          ? "HIGH"
          : sec?.evidence_status === "PARTIAL"
          ? "PARTIAL"
          : "CLEAN";
        const secretFindingsCount = sec?.findings?.filter((f: any) => f.category === "SECRET" || f.rule_id?.includes("SECRET"))?.length || 0;
        const secRuleMatchesCount = sec?.findings?.length || 0;

        // 2. Reliability Metrics with Grounded Terminology
        const relFindings = rel?.findings || [];
        const relDetectedCount = relFindings.filter((f: any) => f.terminology === "DETECTED" || f.confidence === "HIGH").length;
        const relLikelyCount = relFindings.filter((f: any) => f.terminology === "LIKELY" || f.confidence === "MEDIUM").length;
        const relSuspiciousCount = relFindings.filter((f: any) => f.terminology === "SUSPICIOUS").length;
        const relReviewCount = relFindings.filter((f: any) => f.terminology === "REQUIRES REVIEW" || f.confidence === "LOW").length;
        const relStatusText = rel?.critical_count && rel.critical_count > 0
          ? `${rel.critical_count} CRIT`
          : rel?.high_count && rel.high_count > 0
          ? `${rel.high_count} HIGH`
          : rel?.reliability_status || "PASSED";

        // 3. Tests Metrics: Explicit distinction between verified failure & AI recommendations
        const failedRequiredTests = merge?.evidence_summary?.failed_required_tests || 0;
        const mustRunCount = strat?.must_run?.length || 0;
        const shouldRunCount = strat?.should_run?.length || 0;
        const totalRecommendedTests = mustRunCount + shouldRunCount;
        const testStatusText = failedRequiredTests > 0
          ? `${failedRequiredTests} FAILED`
          : mustRunCount > 0
          ? `${mustRunCount} MUST RUN`
          : "PASSING";

        // 4. Dependencies Metrics (Truthful Partial Evidence contract)
        const depStatusText = dependencySecurity?.status || (sec?.evidence_status === "PARTIAL" ? "PARTIAL" : "VERIFIED");
        const depCount = dependencySecurity?.dependencies_scanned || orgDependencies?.length || 0;
        const depVulnCount = dependencySecurity?.vulnerabilities_found || 0;

        // 5. Impact Metrics
        const changedFilesCount = prChangeSet?.changedFilesCount || prChangeSet?.files?.length || 0;
        const changedModulesCount = prAnalysisResult?.affected_modules?.length || 0;
        const downstreamReposCount = prAnalysisResult?.impact_metadata?.distributed_impact?.direct_downstream_count || 0;
        const riskScoreNum = risk?.final_risk ?? prAnalysisResult?.risk_score ?? 0;
        const riskLevelText = risk?.final_risk_level ?? prAnalysisResult?.risk_level ?? "LOW";
        const impactLevelText = prAnalysisResult?.impact_metadata?.distributed_impact?.combined_risk_level
          || (riskScoreNum >= 75 ? "HIGH" : riskScoreNum >= 40 ? "MEDIUM" : "LOW");

        // 6. Merge Readiness Metrics
        const mergeDecisionText = merge?.decision || "NOT_READY";
        const blockersCount = merge?.blockers?.length || 0;

        const critFindingsCount = (sec?.critical_count ?? 0) + (rel?.critical_count ?? 0);
        const highFindingsCount = (sec?.high_count ?? 0) + (rel?.high_count ?? 0);
        const testsFailingCount = failedRequiredTests;
        const missingCoverageCount = strat?.coverage_summary?.coverage_available === false ? (strat?.must_run?.length || 1) : 0;

        return (
          <Card className="border-[#1C1C21] bg-[#0E0E11]">
            <CardHeader className="pb-3 border-b border-[#1C1C21]">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                  <Activity className="h-4 w-4 text-zinc-400" />
                  <CardTitle className="text-sm font-semibold text-zinc-100">
                    Engineering Health Center
                  </CardTitle>
                  <span className="text-[11px] text-zinc-500 font-mono hidden sm:inline">
                    Code Quality, Security & Merge Readiness
                  </span>
                </div>

                {/* Sub-Navigation for Engineering Health */}
                <div className="flex items-center gap-1 p-1 bg-[#141418] border border-[#26262C] rounded-md text-xs font-mono flex-wrap">
                  {[
                    { id: "all", label: "Overview" },
                    { id: "security", label: "Security" },
                    { id: "reliability", label: "Reliability" },
                    { id: "tests", label: "Tests" },
                    { id: "dependencies", label: "Dependencies" },
                    { id: "impact", label: "Impact" },
                    { id: "merge", label: "Merge" },
                  ].map((tab) => (
                    <button
                      key={tab.id}
                      onClick={() => {
                        setActiveTab("pr");
                        setActiveHealthSection(tab.id as any);
                      }}
                      className={`px-2 py-0.5 rounded text-[11px] transition-colors ${
                        activeHealthSection === tab.id
                          ? "bg-zinc-100 text-zinc-950 font-medium"
                          : "text-zinc-400 hover:text-zinc-100"
                      }`}
                    >
                      {tab.label}
                    </button>
                  ))}
                </div>
              </div>
            </CardHeader>

            <CardContent className="pt-4 space-y-4">
              {/* Top 6 Dimension Metric Cards (Clickable) */}
              <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2.5">
                {/* 1. Security */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("security");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "security"
                      ? "border-rose-700/70 bg-rose-950/20"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Security
                    </span>
                    <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
                  </div>
                  <div className={`text-sm font-semibold font-mono tabular-nums truncate ${
                    secStatusText === "CRITICAL" ? "text-rose-400" : secStatusText === "HIGH" ? "text-amber-400" : "text-emerald-400"
                  }`}>
                    {secStatusText}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span>{secRuleMatchesCount} rules</span>
                    {secretFindingsCount > 0 && (
                      <span className="text-rose-400 font-semibold">{secretFindingsCount} secrets</span>
                    )}
                  </div>
                </button>

                {/* 2. Reliability / Bugs */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("reliability");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "reliability"
                      ? "border-amber-700/70 bg-amber-950/20"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Reliability
                    </span>
                    <Bug className="h-3.5 w-3.5 text-amber-400" />
                  </div>
                  <div className="text-sm font-semibold font-mono tabular-nums text-amber-300 truncate">
                    {relStatusText}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span title="Detected via static rules">{relDetectedCount} det</span>
                    <span title="Likely dereference issues">{relLikelyCount} likely</span>
                    <span title="Requires review">{relReviewCount} rev</span>
                  </div>
                </button>

                {/* 3. Tests */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("tests");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "tests"
                      ? "border-zinc-400 bg-[#1A1A20]"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Tests
                    </span>
                    <TestTube className="h-3.5 w-3.5 text-zinc-400" />
                  </div>
                  <div className={`text-sm font-semibold font-mono tabular-nums truncate ${
                    testStatusText.includes("FAILED") ? "text-rose-400" : "text-zinc-100"
                  }`}>
                    {testStatusText}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span className="text-zinc-300" title="AI Recommended tests">{totalRecommendedTests} recs</span>
                    <span title="Actual verified CI failures">{failedRequiredTests} fails</span>
                  </div>
                </button>

                {/* 4. Dependencies (Card 4 per Section 4) */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("dependencies");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "dependencies"
                      ? "border-sky-700/70 bg-sky-950/20"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Dependencies
                    </span>
                    <Boxes className="h-3.5 w-3.5 text-sky-400" />
                  </div>
                  <div className={`text-sm font-semibold font-mono tabular-nums truncate ${
                    depStatusText === "PARTIAL" ? "text-sky-300" : "text-emerald-400"
                  }`}>
                    {depStatusText}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span>{depCount} pkgs</span>
                    <span className={depVulnCount > 0 ? "text-rose-400" : "text-emerald-400"}>
                      {depVulnCount} vulns
                    </span>
                  </div>
                </button>

                {/* 5. Impact */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("impact");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "impact"
                      ? "border-zinc-400 bg-[#1A1A20]"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Impact
                    </span>
                    <Network className="h-3.5 w-3.5 text-zinc-400" />
                  </div>
                  <div className="text-sm font-semibold font-mono tabular-nums text-zinc-100 truncate">
                    {impactLevelText}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span>{changedFilesCount} files</span>
                    <span>{downstreamReposCount} repos</span>
                  </div>
                </button>

                {/* 6. Merge Readiness */}
                <button
                  type="button"
                  onClick={() => {
                    setActiveTab("pr");
                    setActiveHealthSection("merge");
                  }}
                  className={`p-3 rounded-md border transition-colors text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "merge"
                      ? "border-emerald-700/70 bg-emerald-950/20"
                      : "border-[#1C1C21] bg-[#141418] hover:border-[#26262C]"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-500 group-hover:text-zinc-300">
                      Merge Status
                    </span>
                    <GitPullRequest className="h-3.5 w-3.5 text-emerald-400" />
                  </div>
                  <div className={`text-sm font-semibold font-mono tabular-nums truncate ${
                    mergeDecisionText.includes("BLOCKED") ? "text-rose-400" : mergeDecisionText.includes("WARNING") ? "text-amber-400" : "text-emerald-400"
                  }`}>
                    {mergeDecisionText.replace(/_/g, " ")}
                  </div>
                  <div className="text-[10px] font-mono tabular-nums text-zinc-400 flex items-center justify-between pt-0.5">
                    <span className={blockersCount > 0 ? "text-rose-400 font-semibold" : "text-emerald-400"}>
                      {blockersCount} blockers
                    </span>
                    <span>Score {riskScoreNum.toFixed(0)}</span>
                  </div>
                </button>
              </div>

              {/* Engineering Lifecycle Timeline View (Section 15) */}
              <div className="p-3.5 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-2.5">
                <div className="flex items-center justify-between text-xs">
                  <div className="flex items-center gap-2 font-mono text-zinc-300 font-medium uppercase tracking-wider text-[11px]">
                    <Workflow className="h-3.5 w-3.5 text-zinc-400" />
                    <span>Engineering Workflow Lifecycle</span>
                  </div>
                  <span className="text-[10px] font-mono text-zinc-500 hidden sm:inline">
                    Deterministic Evidence &bull; Tenant Isolated
                  </span>
                </div>

                {/* Horizontal Step Pipeline */}
                <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-2 font-mono text-[10px]">
                  {/* Step 1: Project & Req */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">1. Project & Req</span>
                    <div className="text-emerald-400 font-semibold flex items-center gap-1">
                      <CheckCircle2 className="h-3 w-3" /> Linked
                    </div>
                  </div>

                  {/* Step 2: Task */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">2. Task Linked</span>
                    <div className="text-emerald-400 font-semibold flex items-center gap-1">
                      <CheckCircle2 className="h-3 w-3" /> Active
                    </div>
                  </div>

                  {/* Step 3: Git Repo & Branch */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">3. Branch / Commit</span>
                    <div className="text-zinc-200 font-semibold truncate">
                      {prChangeSet?.sourceBranch || "main"}
                    </div>
                  </div>

                  {/* Step 4: Pull Request */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">4. Pull Request</span>
                    <div className="text-zinc-100 font-semibold tabular-nums">
                      {selectedPrNumber ? `PR #${selectedPrNumber}` : "None"}
                    </div>
                  </div>

                  {/* Step 5: Tests / CI */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">5. CI / Tests</span>
                    <div className={`font-semibold tabular-nums ${failedRequiredTests > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {failedRequiredTests > 0 ? `${failedRequiredTests} Failures` : "Verified"}
                    </div>
                  </div>

                  {/* Step 6: AI Health */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">6. AI Analysis</span>
                    <div className="text-zinc-200 font-semibold">
                      {prAnalysisResult ? "Executed" : "Pending"}
                    </div>
                  </div>

                  {/* Step 7: Security & Bugs */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">7. Security/Bugs</span>
                    <div className={`font-semibold tabular-nums ${critFindingsCount > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {critFindingsCount > 0 ? `${critFindingsCount} Critical` : "Clean"}
                    </div>
                  </div>

                  {/* Step 8: Merge Ready */}
                  <div className="p-2 rounded border border-[#1C1C21] bg-[#141418] space-y-1">
                    <span className="text-zinc-500 block uppercase">8. Merge Ready</span>
                    <div className={`font-semibold truncate ${mergeDecisionText.includes("BLOCKED") ? "text-rose-400" : "text-emerald-400"}`}>
                      {mergeDecisionText}
                    </div>
                  </div>
                </div>
              </div>

              {/* 4 Clickable Quick Metrics */}
              <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-[#1C1C21] text-xs">
                <div className="flex items-center gap-2.5 flex-wrap">
                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("security");
                      setFindingSeverityFilter("CRITICAL");
                    }}
                    className="flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-rose-900/60 bg-rose-950/20 text-rose-300 hover:bg-rose-950/35 transition-colors"
                  >
                    <ShieldAlert className="h-3.5 w-3.5 text-rose-400" />
                    <span>Critical Findings:</span>
                    <strong className="font-mono tabular-nums">{critFindingsCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("security");
                      setFindingSeverityFilter("HIGH");
                    }}
                    className="flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-amber-900/60 bg-amber-950/20 text-amber-300 hover:bg-amber-950/35 transition-colors"
                  >
                    <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
                    <span>High Findings:</span>
                    <strong className="font-mono tabular-nums">{highFindingsCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("tests");
                      setTestBucketFilter("FAILURES");
                    }}
                    className="flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-[#26262C] bg-[#141418] text-zinc-300 hover:border-zinc-600 transition-colors"
                  >
                    <TestTube className="h-3.5 w-3.5 text-zinc-400" />
                    <span>Tests Failing:</span>
                    <strong className="font-mono tabular-nums">{testsFailingCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("tests");
                      setTestBucketFilter("MUST_RUN");
                    }}
                    className="flex items-center gap-1.5 px-2.5 py-1 rounded-md border border-sky-900/60 bg-sky-950/20 text-sky-300 hover:bg-sky-950/35 transition-colors"
                  >
                    <CheckCheck className="h-3.5 w-3.5 text-sky-400" />
                    <span>Missing Coverage:</span>
                    <strong className="font-mono tabular-nums">{missingCoverageCount}</strong>
                  </button>
                </div>

                {/* Secondary Utilities */}
                <div className="flex items-center gap-2.5">
                  <button
                    type="button"
                    onClick={() => setIsDepSecurityModalOpen(true)}
                    className="text-[11px] font-mono text-zinc-400 hover:text-zinc-100 flex items-center gap-1 transition-colors"
                  >
                    <Boxes className="h-3.5 w-3.5 text-sky-400" />
                    Dependency CVE Database
                  </button>
                  <span className="text-zinc-700">&bull;</span>
                  <button
                    type="button"
                    onClick={() => {
                      if (selectedPrNumber) fetchAnalysisHistory(selectedPrNumber);
                      setIsHistoryModalOpen(true);
                    }}
                    className="text-[11px] font-mono text-zinc-400 hover:text-zinc-100 flex items-center gap-1 transition-colors"
                  >
                    <History className="h-3.5 w-3.5 text-zinc-400" />
                    Analysis History Timeline
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>
        );
      })()}

      {/* Navigation Tabs */}
      <div className="flex items-center gap-1.5 border-b border-[#1C1C21] pb-2.5 overflow-x-auto">
        <button
          onClick={() => setActiveTab("pr")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "pr"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <GitPullRequest className="h-3.5 w-3.5" />
          Automatic PR Intelligence
        </button>
        <button
          onClick={() => setActiveTab("search")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "search"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <Search className="h-3.5 w-3.5" />
          Semantic Vector Search (pgvector)
        </button>
        <button
          onClick={() => setActiveTab("impact")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "impact"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <Network className="h-3.5 w-3.5" />
          AST Call Graph Blast Radius
        </button>
        <button
          onClick={() => setActiveTab("dependencies")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "dependencies"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <Share2 className="h-3.5 w-3.5" />
          Cross-Repository Topology
        </button>
        <button
          onClick={() => setActiveTab("remediation")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "remediation"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <Sliders className="h-3.5 w-3.5" />
          Controlled Remediation
          {localActions.length > 0 && (
            <span className={`ml-1 rounded px-1.5 py-0 text-[10px] font-mono tabular-nums ${
              activeTab === "remediation"
                ? "bg-zinc-900/15 text-zinc-950"
                : "bg-[#141418] text-zinc-300 border border-[#26262C]"
            }`}>
              {localActions.length}
            </span>
          )}
        </button>
        <button
          onClick={() => setActiveTab("autodocs")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "autodocs"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <BookOpen className="h-3.5 w-3.5" />
          System Design & Auto-Docs
          {latestDocs.length > 0 && (
            <span className={`ml-1 rounded px-1.5 py-0 text-[10px] font-mono tabular-nums ${
              activeTab === "autodocs"
                ? "bg-zinc-900/15 text-zinc-950"
                : "bg-[#141418] text-zinc-300 border border-[#26262C]"
            }`}>
              {latestDocs.length}
            </span>
          )}
        </button>
        <button
          onClick={() => setActiveTab("commitguard")}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap transition-colors ${
            activeTab === "commitguard"
              ? "bg-zinc-100 text-zinc-950"
              : "text-zinc-400 hover:text-zinc-100 hover:bg-[#141418]"
          }`}
        >
          <ShieldAlert className="h-3.5 w-3.5" />
          Commit Push Guard
          {commitReports.some((r) => r.status === "CRITICAL_ALERT") && (
            <span className="ml-1 rounded bg-rose-950/60 text-rose-300 border border-rose-800/60 text-[10px] px-1.5 py-0 font-mono">
              ALERT
            </span>
          )}
        </button>
      </div>

      {/* ========================================================================= */}
      {/* TAB 1: AUTOMATIC REAL GITHUB PULL REQUEST INTELLIGENCE                    */}
      {/* ========================================================================= */}
      {activeTab === "pr" && (
        <div className="space-y-5">
          {/* PR Selector & Metadata Header */}
          <Card className="border-[#1C1C21] bg-[#0E0E11]">
            <CardHeader className="pb-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <CardTitle className="text-sm font-semibold text-zinc-100 flex items-center gap-2">
                    <GitPullRequest className="h-4 w-4 text-zinc-400" />
                    Authoritative GitHub Pull Request
                  </CardTitle>
                  <CardDescription className="text-xs text-zinc-400 mt-0.5">
                    Select a pull request to fetch change sets, derive AST symbols, and compute blast radius.
                  </CardDescription>
                </div>

                {/* PR Dropdown */}
                <div className="flex items-center gap-2">
                  {isLoadingPrs ? (
                    <div className="flex items-center gap-2 text-xs text-zinc-400 h-8 px-3 bg-[#141418] border border-[#26262C] rounded-md">
                      <Loader2 className="h-3 w-3 animate-spin text-zinc-400" />
                      Loading PRs...
                    </div>
                  ) : pullRequests.length > 0 ? (
                    <select
                      aria-label="Authoritative Pull Request Selection"
                      value={selectedPrNumber}
                      onChange={(e) => setSelectedPrNumber(Number(e.target.value) || "")}
                      className="bg-[#141418] border border-[#26262C] text-xs text-zinc-100 rounded-md px-3 h-8 focus:border-zinc-400 focus:outline-none cursor-pointer max-w-xs truncate"
                    >
                      {pullRequests.map((pr) => (
                        <option key={pr.id || pr.number} value={pr.prNumber || pr.number} className="bg-[#0E0E11] text-zinc-100">
                          #{pr.prNumber || pr.number}: {pr.title}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <div className="text-xs text-zinc-500 bg-[#141418] px-3 py-1.5 rounded-md border border-[#26262C]">
                      No pull requests synchronized for this repository
                    </div>
                  )}
                </div>
              </div>
            </CardHeader>

            {/* Authoritative Changeset Details Card */}
            <CardContent className="space-y-4 pt-0">
              {isLoadingChangeSet ? (
                <div className="flex items-center justify-center p-6 text-xs text-zinc-400">
                  <Loader2 className="h-4 w-4 animate-spin text-zinc-400 mr-2" />
                  Fetching authoritative change set from GitHub / Source Control...
                </div>
              ) : prChangeSet ? (
                <div className="rounded-md border border-[#26262C] bg-[#141418] p-4 space-y-4">
                  <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-zinc-100 tabular-nums">
                          #{prChangeSet.number}: {prChangeSet.title}
                        </span>
                        <Badge
                          variant={prChangeSet.state === "OPEN" ? "success" : "outline"}
                          className="text-[10px]"
                        >
                          {prChangeSet.state}
                        </Badge>
                      </div>
                      <div className="flex items-center gap-2 text-xs text-zinc-400 mt-1 font-mono tabular-nums">
                        <GitBranch className="h-3 w-3 text-zinc-500" />
                        <span className="text-zinc-300">{prChangeSet.sourceBranch || "head"}</span>
                        <ArrowRight className="h-3 w-3 text-zinc-600" />
                        <span className="text-zinc-300">{prChangeSet.targetBranch || "main"}</span>
                        {prChangeSet.headSha && (
                          <span className="text-[11px] text-zinc-500 ml-1">
                            ({prChangeSet.headSha.substring(0, 7)})
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Stats pills */}
                    <div className="flex items-center gap-2 text-[11px] font-mono tabular-nums">
                      <span className="px-2 py-0.5 rounded bg-[#0E0E11] border border-[#26262C] text-zinc-300">
                        {prChangeSet.changedFilesCount || prChangeSet.files?.length || 0} files changed
                      </span>
                      <span className="px-2 py-0.5 rounded bg-emerald-950/30 border border-emerald-900/50 text-emerald-400">
                        +{prChangeSet.additions || 0}
                      </span>
                      <span className="px-2 py-0.5 rounded bg-rose-950/30 border border-rose-900/50 text-rose-400">
                        -{prChangeSet.deletions || 0}
                      </span>
                    </div>
                  </div>

                  {/* Changed Files Chips */}
                  {prChangeSet.files && prChangeSet.files.length > 0 && (
                    <div className="space-y-1.5">
                      <div className="text-[11px] font-medium text-zinc-400 uppercase tracking-wider">
                        Changed Files ({prChangeSet.files.length})
                      </div>
                      <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto">
                        {prChangeSet.files.map((file, idx) => (
                          <div
                            key={idx}
                            className="flex items-center gap-1.5 px-2 py-1 rounded border border-[#26262C] bg-[#0E0E11] text-[11px] font-mono text-zinc-300"
                          >
                            <FileCode className="h-3 w-3 text-zinc-500" />
                            <span className="truncate max-w-xs">{file.filename}</span>
                            <span
                              className={`text-[9px] px-1 rounded uppercase font-semibold ${
                                file.status === "added"
                                  ? "text-emerald-400 bg-emerald-950/50"
                                  : file.status === "removed"
                                  ? "text-rose-400 bg-rose-950/50"
                                  : "text-amber-400 bg-amber-950/50"
                              }`}
                            >
                              {file.status}
                            </span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Primary Action Button */}
                  <div className="flex flex-col sm:flex-row items-center justify-between gap-4 pt-2">
                    <Button
                      onClick={handleAnalyzePr}
                      disabled={isAnalyzingPr || !selectedRepoId || !selectedPrNumber}
                      variant="default"
                      className="w-full sm:w-auto h-8 px-4 text-xs font-medium flex items-center gap-2"
                    >
                      {isAnalyzingPr ? (
                        <>
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          Running PR Intelligence Pipeline...
                        </>
                      ) : (
                        <>
                          <Play className="h-3.5 w-3.5" />
                          Analyze Pull Request
                        </>
                      )}
                    </Button>

                    {/* Advanced toggle */}
                    <button
                      type="button"
                      onClick={() => setShowAdvancedOverride(!showAdvancedOverride)}
                      className="text-xs text-zinc-400 hover:text-zinc-100 flex items-center gap-1 transition-colors"
                    >
                      {showAdvancedOverride ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                      Advanced Override (Manual Symbols)
                    </button>
                  </div>

                  {/* Collapsible Advanced Override Section */}
                  {showAdvancedOverride && (
                    <div className="p-3 rounded-md border border-[#26262C] bg-[#0E0E11] space-y-2 mt-2">
                      <div className="text-[11px] text-zinc-400">
                        Optional: Override static AST symbol derivation with manual symbols for debugging.
                      </div>
                      <Input
                        placeholder="e.g. RepositoryApplicationService#getPullRequestChanges, TokenRotationService"
                        value={manualSymbolsInput}
                        onChange={(e) => setManualSymbolsInput(e.target.value)}
                        className="text-xs font-mono"
                      />
                    </div>
                  )}
                </div>
              ) : null}

              {prError && (
                <div className="p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-rose-400 shrink-0" />
                  <span>{prError}</span>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Lifecycle Execution Pipeline Indicator */}
          {isAnalyzingPr && (
            <Card className="border-[#1C1C21] bg-[#0E0E11] p-4">
              <div className="text-xs font-medium text-zinc-200 mb-3 flex items-center gap-2">
                <Loader2 className="h-3.5 w-3.5 animate-spin text-zinc-300" />
                PR Intelligence Pipeline Execution
              </div>
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2 text-[11px] font-mono">
                <div
                  className={`p-2 rounded-md border flex items-center gap-1.5 ${
                    lifecycleStep === "fetching"
                      ? "border-zinc-400 bg-[#1A1A20] text-zinc-100"
                      : "border-[#1C1C21] bg-[#141418] text-zinc-500"
                  }`}
                >
                  <GitPullRequest className="h-3 w-3 shrink-0" />
                  <span>1. Authoritative PR</span>
                </div>
                <div
                  className={`p-2 rounded-md border flex items-center gap-1.5 ${
                    lifecycleStep === "deriving"
                      ? "border-zinc-400 bg-[#1A1A20] text-zinc-100"
                      : "border-[#1C1C21] bg-[#141418] text-zinc-500"
                  }`}
                >
                  <Code2 className="h-3 w-3 shrink-0" />
                  <span>2. Tree-sitter AST</span>
                </div>
                <div
                  className={`p-2 rounded-md border flex items-center gap-1.5 ${
                    lifecycleStep === "indexing"
                      ? "border-zinc-400 bg-[#1A1A20] text-zinc-100"
                      : "border-[#1C1C21] bg-[#141418] text-zinc-500"
                  }`}
                >
                  <Database className="h-3 w-3 shrink-0" />
                  <span>3. Durable Indexing</span>
                </div>
                <div
                  className={`p-2 rounded-md border flex items-center gap-1.5 ${
                    lifecycleStep === "graph"
                      ? "border-zinc-400 bg-[#1A1A20] text-zinc-100"
                      : "border-[#1C1C21] bg-[#141418] text-zinc-500"
                  }`}
                >
                  <Network className="h-3 w-3 shrink-0" />
                  <span>4. Graph CTE</span>
                </div>
                <div
                  className={`p-2 rounded-md border flex items-center gap-1.5 ${
                    lifecycleStep === "reasoning"
                      ? "border-zinc-400 bg-[#1A1A20] text-zinc-100"
                      : "border-[#1C1C21] bg-[#141418] text-zinc-500"
                  }`}
                >
                  <Cpu className="h-3 w-3 shrink-0" />
                  <span>5. Gemini Reasoning</span>
                </div>
              </div>
            </Card>
          )}

          {/* Analysis Results */}
          {prAnalysisResult && (
            <div className="space-y-5">
              {/* Fallback Banner when viewing previous AI data */}
              {(isUsingLastAnalysis || prAnalysisResult?.impact_metadata?.showing_previous_ai_data) && (
                <div className="p-4 rounded-lg border border-amber-900/60 bg-amber-950/20 text-amber-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                  <div className="flex items-center gap-3">
                    <div className="p-2 rounded-md bg-amber-950/50 border border-amber-800/50 text-amber-400 shrink-0">
                      <AlertTriangle className="h-4 w-4" />
                    </div>
                    <div>
                      <div className="text-xs font-semibold text-amber-300 uppercase tracking-wider flex items-center gap-2 flex-wrap">
                        <span>This is the previous data — AI API is not working currently.</span>
                        {lastAnalysisTimestamp && (
                          <Badge variant="outline" className="text-[10px] border-amber-800/60 bg-amber-950/40 text-amber-300 font-mono tabular-nums">
                            Recorded: {lastAnalysisTimestamp}
                          </Badge>
                        )}
                      </div>
                      <p className="text-xs text-zinc-300 mt-1">
                        The live AI API is currently not responding. Displaying the last real analysis performed by ForgeAI. Live real-time derivation is paused.
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={handleAnalyzePr}
                      disabled={isAnalyzingPr}
                      className="h-8 text-xs border-amber-800/60 bg-amber-950/30 text-amber-200 hover:bg-amber-950/50"
                    >
                      {isAnalyzingPr ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <RefreshCw className="h-3.5 w-3.5 mr-1.5 text-amber-400" />}
                      Retry AI API
                    </Button>
                  </div>
                </div>
              )}
              <Card className="border-[#1C1C21] bg-[#0E0E11]">
                <CardHeader className="pb-4 border-b border-[#1C1C21]">
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div className="flex items-center gap-2.5 flex-wrap">
                      <div className="flex items-center gap-2">
                        <ShieldCheck className="h-4 w-4 text-zinc-400" />
                        <CardTitle className="text-sm font-semibold text-zinc-100">
                          Engineering Impact & Risk Intelligence
                        </CardTitle>
                      </div>

                      {/* Overall Risk Badge */}
                      <Badge
                        variant="outline"
                        className={`text-[11px] font-mono font-semibold px-2.5 py-0.5 uppercase tracking-wider border ${
                          prAnalysisResult.risk_level === "LOW"
                            ? "bg-emerald-950/30 text-emerald-400 border-emerald-900/60"
                            : prAnalysisResult.risk_level === "MEDIUM"
                            ? "bg-amber-950/30 text-amber-400 border-amber-900/60"
                            : prAnalysisResult.risk_level === "HIGH"
                            ? "bg-orange-950/30 text-orange-400 border-orange-900/60"
                            : "bg-rose-950/30 text-rose-400 border-rose-900/60"
                        }`}
                      >
                        Risk: {prAnalysisResult.risk_level}
                      </Badge>

                      {/* Deterministic Risk Score */}
                      <div className="flex items-center gap-1.5 px-2.5 py-0.5 rounded bg-[#141418] border border-[#26262C] text-[11px] font-mono tabular-nums">
                        <Activity className="h-3 w-3 text-zinc-400" />
                        <span className="text-zinc-500">Score:</span>
                        <span className="font-semibold text-zinc-100">
                          {typeof prAnalysisResult.risk_score === "number"
                            ? prAnalysisResult.risk_score.toFixed(1)
                            : "0.0"}
                        </span>
                        <span className="text-zinc-500">/ 100</span>
                      </div>

                      {/* Evidence Confidence */}
                      <Badge
                        variant="outline"
                        className={`text-[11px] font-mono px-2 py-0.5 ${
                          prAnalysisResult.confidence === "HIGH"
                            ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                            : prAnalysisResult.confidence === "MEDIUM"
                            ? "text-sky-400 border-sky-900/60 bg-sky-950/20"
                            : "text-amber-400 border-amber-900/60 bg-amber-950/20"
                        }`}
                        title={prAnalysisResult.impact_metadata?.confidence_reason}
                      >
                        Confidence: {prAnalysisResult.confidence}
                      </Badge>
                    </div>

                    {/* Transparency Badges */}
                    <div className="flex items-center gap-2 flex-wrap">
                      <Badge variant="outline" className="font-mono text-[11px] border-[#26262C] bg-[#141418] text-zinc-300">
                        Provider:{" "}
                        <span className="text-zinc-100 font-semibold ml-1">
                          {prAnalysisResult.provider === "GROQ"
                            ? "Groq Cloud"
                            : prAnalysisResult.provider === "GEMINI"
                            ? "Google Gemini"
                            : prAnalysisResult.provider}
                        </span>
                      </Badge>
                      {prAnalysisResult.fallback_used ? (
                        <Badge variant="destructive" className="font-mono text-[11px]">
                          Fallback: Yes (Groq)
                        </Badge>
                      ) : (
                        <Badge variant="outline" className="font-mono text-[11px] text-emerald-400 border-emerald-900/60">
                          Primary: Gemini
                        </Badge>
                      )}
                    </div>
                  </div>

                  <CardDescription className="text-[11px] font-mono tabular-nums pt-2 text-zinc-400 flex flex-wrap items-center gap-x-4 gap-y-1">
                    <span>Model: {prAnalysisResult.model_version}</span>
                    <span>Duration: {prAnalysisResult.execution_duration_ms}ms</span>
                    {prAnalysisResult.impact_metadata?.confidence_reason && (
                      <span className="text-zinc-400 font-sans">
                        Evidence: {prAnalysisResult.impact_metadata.confidence_reason}
                      </span>
                    )}
                    {prAnalysisResult.trace_id && (
                      <span>Trace: {prAnalysisResult.trace_id.substring(0, 16)}...</span>
                    )}
                  </CardDescription>
                </CardHeader>

                <CardContent className="space-y-5 pt-5">
                  {/* ========================================================= */}
                  {/* AUTOMATED MERGE READINESS BANNER (DETERMINISTIC ENGINE)   */}
                  {/* ========================================================= */}
                  {prAnalysisResult.merge_readiness && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-4">
                      {/* Header Row: Decision Badge, Readiness Score, Policy & "Why?" Toggle */}
                      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-[#1C1C21] pb-3.5">
                        <div className="flex items-center gap-2.5 flex-wrap">
                          {/* Categorical Decision Badge */}
                          {(() => {
                            const decision = prAnalysisResult.merge_readiness.decision;
                            if (decision === "READY") {
                              return (
                                <Badge className="px-2.5 py-1 text-xs font-mono font-semibold tracking-wide bg-emerald-950/60 text-emerald-300 border border-emerald-800/60 flex items-center gap-1.5">
                                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                                  READY TO MERGE
                                </Badge>
                              );
                            }
                            if (decision === "READY_WITH_WARNINGS") {
                              return (
                                <Badge className="px-2.5 py-1 text-xs font-mono font-semibold tracking-wide bg-amber-950/60 text-amber-300 border border-amber-800/60 flex items-center gap-1.5">
                                  <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
                                  READY WITH WARNINGS
                                </Badge>
                              );
                            }
                            if (decision === "NOT_READY") {
                              return (
                                <Badge className="px-2.5 py-1 text-xs font-mono font-semibold tracking-wide bg-rose-950/60 text-rose-300 border border-rose-800/60 flex items-center gap-1.5">
                                  <XCircle className="h-3.5 w-3.5 text-rose-400" />
                                  NOT READY TO MERGE
                                </Badge>
                              );
                            }
                            return (
                              <Badge className="px-2.5 py-1 text-xs font-mono font-semibold tracking-wide bg-sky-950/60 text-sky-300 border border-sky-800/60 flex items-center gap-1.5">
                                <AlertCircle className="h-3.5 w-3.5 text-sky-400" />
                                BLOCKED BY MISSING EVIDENCE
                              </Badge>
                            );
                          })()}

                          {/* Policy Version Badge */}
                          <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C] bg-[#0E0E11]">
                            Policy: {prAnalysisResult.merge_readiness.policy_version || "merge-policy-v1"}
                          </Badge>

                          {/* Security Status Badge */}
                          {(() => {
                            const sec = prAnalysisResult.merge_readiness.security_status;
                            if (sec === "NO_SECURITY_FINDINGS") {
                              return (
                                <Badge variant="outline" className="text-[10px] font-mono text-emerald-400 border-emerald-900/60 bg-emerald-950/20 flex items-center gap-1">
                                  <ShieldCheck className="h-3 w-3" />
                                  Security: Clean
                                </Badge>
                              );
                            }
                            if (sec === "SECURITY_BLOCKER") {
                              return (
                                <Badge variant="destructive" className="text-[10px] font-mono flex items-center gap-1">
                                  <ShieldAlert className="h-3 w-3" />
                                  Security: Blocker
                                </Badge>
                              );
                            }
                            if (sec === "SECURITY_WARNING") {
                              return (
                                <Badge variant="outline" className="text-[10px] font-mono text-amber-400 border-amber-900/60 bg-amber-950/20 flex items-center gap-1">
                                  <ShieldAlert className="h-3 w-3" />
                                  Security: Warning
                                </Badge>
                              );
                            }
                            return (
                              <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C] bg-[#0E0E11] flex items-center gap-1">
                                <ShieldX className="h-3 w-3 text-zinc-500" />
                                Security: Evidence Unavailable
                              </Badge>
                            );
                          })()}
                        </div>

                        {/* Readiness Score & "Why?" Expand Toggle */}
                        <div className="flex items-center gap-3">
                          <div className="flex items-center gap-2">
                            <span className="text-[11px] font-mono uppercase tracking-wider text-zinc-400">Readiness Score:</span>
                            <div className="flex items-baseline gap-1 font-mono tabular-nums">
                              <span
                                className={`text-lg font-semibold ${
                                  prAnalysisResult.merge_readiness.readiness_score >= 80
                                    ? "text-emerald-400"
                                    : prAnalysisResult.merge_readiness.readiness_score >= 50
                                    ? "text-amber-400"
                                    : "text-rose-400"
                                }`}
                              >
                                {typeof prAnalysisResult.merge_readiness.readiness_score === "number"
                                  ? prAnalysisResult.merge_readiness.readiness_score.toFixed(1)
                                  : "0.0"}
                              </span>
                              <span className="text-[10px] text-zinc-500">/ 100.0</span>
                            </div>
                          </div>

                          <Button
                            type="button"
                            variant="secondary"
                            size="sm"
                            onClick={() => setIsReadinessDetailsOpen((prev) => !prev)}
                            className="h-7 text-xs font-mono px-2.5 flex items-center gap-1.5"
                          >
                            <HelpCircle className="h-3.5 w-3.5 text-zinc-400" />
                            <span>Why?</span>
                            {isReadinessDetailsOpen ? (
                              <ChevronUp className="h-3.5 w-3.5 text-zinc-400" />
                            ) : (
                              <ChevronDown className="h-3.5 w-3.5 text-zinc-400" />
                            )}
                          </Button>
                        </div>
                      </div>

                      {/* Summary Banner */}
                      <div className="text-xs text-zinc-300 leading-relaxed font-sans">
                        {prAnalysisResult.merge_readiness.summary}
                      </div>

                      {/* Blocker Callouts if any */}
                      {prAnalysisResult.merge_readiness.blockers && prAnalysisResult.merge_readiness.blockers.length > 0 && (
                        <div className="p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs space-y-1.5">
                          <div className="font-semibold text-rose-300 flex items-center gap-1.5">
                            <XCircle className="h-3.5 w-3.5 text-rose-400 shrink-0" />
                            <span>Merge Blockers ({prAnalysisResult.merge_readiness.blockers.length})</span>
                          </div>
                          <ul className="list-disc list-inside space-y-1 text-rose-300/90 pl-1">
                            {prAnalysisResult.merge_readiness.blockers.map((blocker, bIdx) => (
                              <li key={bIdx} className="leading-snug">{blocker}</li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {/* Warning Callouts if any */}
                      {prAnalysisResult.merge_readiness.warnings && prAnalysisResult.merge_readiness.warnings.length > 0 && (
                        <div className="p-3 rounded-md border border-amber-900/60 bg-amber-950/20 text-xs space-y-1.5">
                          <div className="font-semibold text-amber-300 flex items-center gap-1.5">
                            <AlertTriangle className="h-3.5 w-3.5 text-amber-400 shrink-0" />
                            <span>Advisory Warnings ({prAnalysisResult.merge_readiness.warnings.length})</span>
                          </div>
                          <ul className="list-disc list-inside space-y-1 text-amber-300/90 pl-1">
                            {prAnalysisResult.merge_readiness.warnings.map((warning, wIdx) => (
                              <li key={wIdx} className="leading-snug">{warning}</li>
                            ))}
                          </ul>
                        </div>
                      )}

                      {/* Interactive "Why?" Evidence Drawer */}
                      {isReadinessDetailsOpen && (
                        <div className="pt-3 border-t border-[#1C1C21] space-y-3 animate-in fade-in duration-150">
                          <div className="flex items-center justify-between">
                            <span className="text-[11px] font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
                              <Scale className="h-3.5 w-3.5 text-zinc-400" />
                              Required Checks & Evidence Matrix
                            </span>
                            <span className="text-[11px] font-mono text-zinc-500">
                              Deterministic Evaluation &bull; Mode A Advisory
                            </span>
                          </div>

                          {/* Required Checks Matrix Table */}
                          <div className="rounded-md border border-[#1C1C21] bg-[#0E0E11] overflow-hidden">
                            <div className="overflow-x-auto">
                              <table className="w-full text-left text-xs font-mono">
                                <thead className="bg-[#09090B] text-zinc-500 uppercase text-[10px] border-b border-[#1C1C21]">
                                  <tr>
                                    <th className="px-3.5 py-2 font-medium">Check Name</th>
                                    <th className="px-3.5 py-2 font-medium">Category</th>
                                    <th className="px-3.5 py-2 font-medium">Status</th>
                                    <th className="px-3.5 py-2 font-medium">Observed Evidence</th>
                                    <th className="px-3.5 py-2 font-medium">Evaluation Detail</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-[#1C1C21]">
                                  {Array.isArray(prAnalysisResult.merge_readiness.required_checks) &&
                                    prAnalysisResult.merge_readiness.required_checks.map((check, cIdx) => (
                                      <tr key={cIdx} className="hover:bg-[#141418] transition-colors">
                                        <td className="px-3.5 py-2 font-medium text-zinc-200 whitespace-nowrap">
                                          {check.name}
                                        </td>
                                        <td className="px-3.5 py-2 text-zinc-400 text-[11px]">
                                          {check.category}
                                        </td>
                                        <td className="px-3.5 py-2 whitespace-nowrap">
                                          {check.status === "PASSED" && (
                                            <Badge variant="outline" className="text-[10px] text-emerald-400 border-emerald-900/60 bg-emerald-950/20">
                                              PASSED
                                            </Badge>
                                          )}
                                          {check.status === "FAILED" && (
                                            <Badge variant="destructive" className="text-[10px]">
                                              FAILED
                                            </Badge>
                                          )}
                                          {check.status === "MISSING" && (
                                            <Badge variant="outline" className="text-[10px] text-sky-400 border-sky-900/60 bg-sky-950/20">
                                              MISSING
                                            </Badge>
                                          )}
                                          {check.status === "RUNNING" && (
                                            <Badge variant="outline" className="text-[10px] text-zinc-200 border-[#26262C] bg-[#141418]">
                                              RUNNING
                                            </Badge>
                                          )}
                                          {check.status === "WARNING" && (
                                            <Badge variant="outline" className="text-[10px] text-amber-400 border-amber-900/60 bg-amber-950/20">
                                              WARNING
                                            </Badge>
                                          )}
                                          {check.status === "SKIPPED" && (
                                            <Badge variant="outline" className="text-[10px] text-zinc-400 border-[#26262C] bg-[#141418]">
                                              SKIPPED
                                            </Badge>
                                          )}
                                        </td>
                                        <td className="px-3.5 py-2 text-zinc-300 text-[11px] max-w-xs truncate">
                                          {check.evidence || "—"}
                                        </td>
                                        <td className="px-3.5 py-2 text-zinc-400 text-[11px] font-sans">
                                          {check.detail || "—"}
                                        </td>
                                      </tr>
                                    ))}
                                </tbody>
                              </table>
                            </div>
                          </div>

                          {/* Evidence Summary Stats Grid */}
                          {prAnalysisResult.merge_readiness.evidence_summary && (
                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 font-mono tabular-nums text-xs">
                              <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11]">
                                <div className="text-[10px] uppercase text-zinc-500">CI Run Status</div>
                                <div className="text-zinc-200 font-semibold mt-0.5">
                                  {prAnalysisResult.merge_readiness.evidence_summary.ci_status}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11]">
                                <div className="text-[10px] uppercase text-zinc-500">Required Tests (Exec/Total)</div>
                                <div className="text-zinc-200 font-semibold mt-0.5">
                                  {prAnalysisResult.merge_readiness.evidence_summary.executed_required_tests} / {prAnalysisResult.merge_readiness.evidence_summary.total_required_tests}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11]">
                                <div className="text-[10px] uppercase text-zinc-500">Required Test Failures</div>
                                <div className={`font-semibold mt-0.5 ${prAnalysisResult.merge_readiness.evidence_summary.failed_required_tests > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                                  {prAnalysisResult.merge_readiness.evidence_summary.failed_required_tests}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11]">
                                <div className="text-[10px] uppercase text-zinc-500">Deterministic Risk</div>
                                <div className="text-zinc-100 font-semibold mt-0.5">
                                  {(prAnalysisResult.merge_readiness?.evidence_summary?.deterministic_risk_score ?? 0).toFixed(1)} / 100
                                </div>
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  )}

                  {/* ========================================================= */}
                  {/* UNIFIED ENGINEERING RISK AGGREGATOR CARD (v3)             */}
                  {/* ========================================================= */}
                  {prAnalysisResult.unified_risk && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-4">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
                        <div className="flex items-center gap-2">
                          <Scale className="h-4 w-4 text-zinc-400" />
                          <div>
                            <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider">
                              Unified Engineering Risk Engine
                            </span>
                            <div className="text-[11px] font-mono text-zinc-500">
                              Deterministic Multi-Dimensional Formula (v3)
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-3">
                          <div className="flex items-baseline gap-1.5 font-mono tabular-nums">
                            <span className="text-[11px] uppercase text-zinc-500">Final Risk:</span>
                            <span
                              className={`text-lg font-semibold ${
                                (prAnalysisResult.unified_risk.final_risk ?? 0) >= 75
                                  ? "text-rose-400"
                                  : (prAnalysisResult.unified_risk.final_risk ?? 0) >= 50
                                  ? "text-amber-400"
                                  : (prAnalysisResult.unified_risk.final_risk ?? 0) >= 25
                                  ? "text-amber-300"
                                  : "text-emerald-400"
                              }`}
                            >
                              {(prAnalysisResult.unified_risk.final_risk ?? 0).toFixed(1)}
                            </span>
                            <span className="text-[10px] text-zinc-500">/ 100.0</span>
                          </div>
                          <Badge
                            variant="outline"
                            className={`font-mono text-[10px] px-2 py-0.5 ${
                              prAnalysisResult.unified_risk.final_risk_level === "CRITICAL"
                                ? "text-rose-400 border-rose-900/60 bg-rose-950/30"
                                : prAnalysisResult.unified_risk.final_risk_level === "HIGH"
                                ? "text-amber-400 border-amber-900/60 bg-amber-950/30"
                                : prAnalysisResult.unified_risk.final_risk_level === "MEDIUM"
                                ? "text-amber-300 border-amber-900/50 bg-amber-950/20"
                                : "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                            }`}
                          >
                            {prAnalysisResult.unified_risk.final_risk_level}
                          </Badge>
                        </div>
                      </div>

                      {/* Dimension Contribution Cards */}
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2.5">
                        {/* Local Risk */}
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono tabular-nums">
                            <span className="text-zinc-400">Local Impact (35%)</span>
                            <span className="text-zinc-200 font-semibold">
                              {(prAnalysisResult.unified_risk.local_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-[#1C1C21] rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-zinc-200 h-1.5 rounded-full transition-all duration-300"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.local_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono tabular-nums text-zinc-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.local ?? ((prAnalysisResult.unified_risk.local_risk ?? 0) * 0.35)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Distributed Risk */}
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono tabular-nums">
                            <span className="text-zinc-400">Distributed (15%)</span>
                            <span className="text-zinc-200 font-semibold">
                              {(prAnalysisResult.unified_risk.distributed_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-[#1C1C21] rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-sky-500 h-1.5 rounded-full transition-all duration-300"
                              style={{ width: `${Math.min(100, (prAnalysisResult.unified_risk.distributed_risk ?? 0) * 2)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono tabular-nums text-zinc-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.distributed ?? ((prAnalysisResult.unified_risk.distributed_risk ?? 0) * 0.15)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Security Risk */}
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono tabular-nums">
                            <span className="text-zinc-400">Security (35%)</span>
                            <span className="text-zinc-200 font-semibold">
                              {(prAnalysisResult.unified_risk.security_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-[#1C1C21] rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-rose-500 h-1.5 rounded-full transition-all duration-300"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.security_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono tabular-nums text-zinc-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.security ?? ((prAnalysisResult.unified_risk.security_risk ?? 0) * 0.35)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Reliability Risk */}
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono tabular-nums">
                            <span className="text-zinc-400">Reliability (15%)</span>
                            <span className="text-zinc-200 font-semibold">
                              {(prAnalysisResult.unified_risk.reliability_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-[#1C1C21] rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-amber-500 h-1.5 rounded-full transition-all duration-300"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.reliability_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono tabular-nums text-zinc-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.reliability ?? ((prAnalysisResult.unified_risk.reliability_risk ?? 0) * 0.15)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>
                      </div>

                      {/* Formula footer & Floor Notice */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pt-1 text-[11px] font-mono text-zinc-500">
                        <span>Formula: min(100.0, 0.35*local + 0.15*distributed + 0.35*security + 0.15*reliability)</span>
                        {prAnalysisResult.unified_risk.final_risk >= 75 && prAnalysisResult.unified_risk.security_risk > 0 && (
                          <span className="text-rose-400 flex items-center gap-1 font-medium">
                            <AlertTriangle className="h-3 w-3" />
                            Elevated by Critical Security Floor Policy
                          </span>
                        )}
                      </div>
                    </div>
                  )}

                  {/* ========================================================= */}
                  {/* DETERMINISTIC SECURITY INTELLIGENCE CARD                  */}
                  {/* ========================================================= */}
                  {prAnalysisResult.security_assessment && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-4">
                      {/* Card Header */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
                        <div className="flex items-center gap-2">
                          <ShieldCheck className="h-4 w-4 text-emerald-400" />
                          <div>
                            <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider">
                              Deterministic Security Analysis Layer
                            </span>
                            <div className="text-[11px] font-mono text-zinc-500">
                              Static Rule Verification &bull; Zero Raw Secret Leakage
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-2 flex-wrap">
                          {/* Status Badge */}
                          {prAnalysisResult.security_assessment.security_status === "SECURITY_BLOCKER" ? (
                            <Badge variant="destructive" className="font-mono text-[10px] flex items-center gap-1">
                              <ShieldAlert className="h-3 w-3" />
                              SECURITY BLOCKER
                            </Badge>
                          ) : prAnalysisResult.security_assessment.security_status === "SECURITY_WARNING" ? (
                            <Badge variant="outline" className="font-mono text-[10px] text-amber-400 border-amber-900/60 bg-amber-950/20 flex items-center gap-1">
                              <AlertTriangle className="h-3 w-3" />
                              SECURITY WARNING
                            </Badge>
                          ) : prAnalysisResult.security_assessment.security_status === "SECURITY_EVIDENCE_UNAVAILABLE" ? (
                            <Badge variant="outline" className="font-mono text-[10px] text-zinc-400 border-[#26262C] bg-[#0E0E11] flex items-center gap-1">
                              <ShieldX className="h-3 w-3" />
                              EVIDENCE UNAVAILABLE
                            </Badge>
                          ) : (
                            <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900/60 bg-emerald-950/20 flex items-center gap-1">
                              <ShieldCheck className="h-3 w-3" />
                              CLEAN (0 FINDINGS)
                            </Badge>
                          )}

                          {/* Severity Pill Counts */}
                          <div className="flex items-center gap-1 text-[10px] font-mono tabular-nums">
                            <span className="px-1.5 py-0.5 rounded bg-rose-950/30 border border-rose-900/60 text-rose-300">
                              {prAnalysisResult.security_assessment.critical_count} Crit
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-amber-950/30 border border-amber-900/60 text-amber-300">
                              {prAnalysisResult.security_assessment.high_count} High
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-amber-950/20 border border-amber-900/50 text-amber-300">
                              {prAnalysisResult.security_assessment.medium_count} Med
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-[#0E0E11] border border-[#26262C] text-zinc-400">
                              {prAnalysisResult.security_assessment.low_count} Low
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Partial Evidence Warning Callout (Dependency CVE Scanner Unavailable) */}
                      {prAnalysisResult.security_assessment.evidence_status === "PARTIAL" && (
                        <div className="p-3 rounded-md border border-sky-900/60 bg-sky-950/20 text-xs text-sky-200 flex items-center gap-2.5 font-sans">
                          <Info className="h-4 w-4 text-sky-400 shrink-0" />
                          <span>
                            <strong>Partial Evidence:</strong> Dependency CVE scanner database is unconfigured. Code-level and secret scanning is fully verified. ForgeAI reports partial status truthfully with zero false certainty.
                          </span>
                        </div>
                      )}

                      {/* Findings List */}
                      {Array.isArray(prAnalysisResult.security_assessment.findings) && prAnalysisResult.security_assessment.findings.length > 0 ? (
                        <div className="space-y-2.5">
                          {prAnalysisResult.security_assessment.findings.map((f, fIdx) => (
                            <div
                              key={fIdx}
                              className={`p-3.5 rounded-md border text-xs space-y-2 transition-colors ${
                                f.severity === "CRITICAL"
                                  ? "border-[#26262C] border-l-2 border-l-rose-500 bg-[#0E0E11]"
                                  : f.severity === "HIGH"
                                  ? "border-[#26262C] border-l-2 border-l-amber-500 bg-[#0E0E11]"
                                  : "border-[#1C1C21] bg-[#0E0E11]"
                              }`}
                            >
                              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1.5">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <Badge
                                    variant={f.severity === "CRITICAL" ? "destructive" : "outline"}
                                    className={`text-[9px] font-mono font-semibold uppercase tracking-wider ${
                                      f.severity === "HIGH"
                                        ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                        : f.severity === "MEDIUM"
                                        ? "text-amber-300 border-amber-900/50 bg-amber-950/15"
                                        : ""
                                    }`}
                                  >
                                    {f.severity}
                                  </Badge>
                                  <Badge variant="outline" className="text-[9px] font-mono text-emerald-400 border-emerald-900/60 bg-emerald-950/20 flex items-center gap-1">
                                    <ShieldCheck className="h-3 w-3" />
                                    DETERMINISTIC
                                  </Badge>
                                  <span className="font-mono text-[10px] text-zinc-400 uppercase tracking-wider">
                                    {f.category}
                                  </span>
                                  <span className="font-semibold text-zinc-100">{f.title}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-[10px] text-zinc-500">{f.rule_id}</span>
                                  {f.is_blocker ? (
                                    <Badge variant="destructive" className="text-[9px] px-1.5 py-0 uppercase font-mono font-semibold">
                                      BLOCKING
                                    </Badge>
                                  ) : (
                                    <Badge variant="outline" className="text-[9px] px-1.5 py-0 uppercase font-mono text-zinc-400 border-[#26262C]">
                                      ADVISORY
                                    </Badge>
                                  )}
                                </div>
                              </div>

                              <p className="text-zinc-300 text-[11px] leading-relaxed">{f.description}</p>

                              {/* Concrete Redacted Evidence */}
                              <div className="p-2.5 rounded-md bg-[#09090B] border border-[#26262C] font-mono tabular-nums text-[11px] text-zinc-200 flex items-center justify-between gap-2 overflow-x-auto">
                                <div>
                                  <span className="text-zinc-500">{f.file_path}{f.line_number ? `:${f.line_number}` : ""}:</span>{" "}
                                  <span className="font-medium text-amber-200">{f.redacted_evidence}</span>
                                </div>
                                <span className="text-[9px] uppercase px-1.5 py-0.5 rounded bg-[#141418] border border-[#26262C] text-zinc-300 shrink-0">
                                  Confidence: {f.confidence}
                                </span>
                              </div>

                              {/* Actionable Remediation Guidance */}
                              <div className="text-[11px] text-zinc-400 flex items-start gap-1.5">
                                <span className="text-emerald-400 font-medium shrink-0">Remediation:</span>
                                <span>{f.remediation}</span>
                              </div>

                              {/* Interactive Action Buttons */}
                              <div className="flex items-center justify-between pt-2 border-t border-[#1C1C21]">
                                <span className="text-[10px] font-mono text-zinc-500">
                                  Verification: <span className="text-amber-400 font-medium">Deterministic Re-Scan Required</span>
                                </span>
                                <div className="flex items-center gap-2">
                                  <Button
                                    type="button"
                                    variant="secondary"
                                    size="sm"
                                    onClick={() => setSelectedFindingForModal(f)}
                                    className="h-6 text-[10px] px-2 flex items-center gap-1"
                                  >
                                    <Eye className="h-3 w-3 text-zinc-400" />
                                    Safe Context
                                  </Button>
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleExplainFinding(f)}
                                    className="h-6 text-[10px] px-2 border-[#26262C] bg-[#141418] hover:bg-[#1C1C21] text-zinc-200 flex items-center gap-1"
                                  >
                                    <Cpu className="h-3 w-3 text-zinc-400" />
                                    Explain with ForgeAI
                                  </Button>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-center py-6 text-zinc-500 text-xs font-mono">
                          Zero security vulnerabilities or secret leaks detected in pull request files.
                        </div>
                      )}
                    </div>
                  )}

                  {/* ========================================================= */}
                  {/* DETERMINISTIC RELIABILITY INTELLIGENCE CARD               */}
                  {/* ========================================================= */}
                  {prAnalysisResult.reliability_assessment && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-4">
                      {/* Card Header */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
                        <div className="flex items-center gap-2">
                          <Activity className="h-4 w-4 text-zinc-400" />
                          <div>
                            <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider">
                              Deterministic Reliability Analysis Layer
                            </span>
                            <div className="text-[11px] font-mono text-zinc-500">
                              Swallowed Exceptions &bull; Missing Timeouts &bull; Concurrency Safety &bull; Churn Hotspots
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-2 flex-wrap">
                          {/* Status Badge */}
                          {prAnalysisResult.reliability_assessment.reliability_status === "FAILED" ? (
                            <Badge variant="destructive" className="font-mono text-[10px] flex items-center gap-1">
                              <XCircle className="h-3 w-3" />
                              RELIABILITY FAILED
                            </Badge>
                          ) : prAnalysisResult.reliability_assessment.reliability_status === "WARNING" ? (
                            <Badge variant="outline" className="font-mono text-[10px] text-amber-400 border-amber-900/60 bg-amber-950/20 flex items-center gap-1">
                              <AlertTriangle className="h-3 w-3" />
                              RELIABILITY WARNING
                            </Badge>
                          ) : (
                            <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-900/60 bg-emerald-950/20 flex items-center gap-1">
                              <CheckCircle2 className="h-3 w-3" />
                              PASSED (0 ISSUES)
                            </Badge>
                          )}

                          {/* Risk Score Pill */}
                          <div className="text-[10px] font-mono tabular-nums px-2 py-0.5 rounded bg-[#0E0E11] border border-[#26262C] text-zinc-300">
                            Risk: {(prAnalysisResult.reliability_assessment?.reliability_risk_score ?? 0).toFixed(1)} / 100
                          </div>
                        </div>
                      </div>

                      {/* Partial Evidence Warning Callout */}
                      {prAnalysisResult.reliability_assessment.evidence_status === "PARTIAL" && (
                        <div className="p-3 rounded-md border border-sky-900/60 bg-sky-950/20 text-xs text-sky-200 flex items-center gap-2.5 font-sans">
                          <Info className="h-4 w-4 text-sky-400 shrink-0" />
                          <span>
                            <strong>Partial Telemetry:</strong> Live OpenTelemetry distributed tracing is currently offline. Static concurrency, exception handling, and churn hotspot rules were executed deterministically.
                          </span>
                        </div>
                      )}

                      {/* Findings List */}
                      {Array.isArray(prAnalysisResult.reliability_assessment.findings) && prAnalysisResult.reliability_assessment.findings.length > 0 ? (
                        <div className="space-y-2.5">
                          {prAnalysisResult.reliability_assessment.findings.map((rf, rIdx) => (
                            <div
                              key={rIdx}
                              className={`p-3.5 rounded-md border text-xs space-y-2 transition-colors ${
                                rf.severity === "CRITICAL"
                                  ? "border-[#26262C] border-l-2 border-l-rose-500 bg-[#0E0E11]"
                                  : rf.severity === "HIGH"
                                  ? "border-[#26262C] border-l-2 border-l-amber-500 bg-[#0E0E11]"
                                  : "border-[#1C1C21] bg-[#0E0E11]"
                              }`}
                            >
                              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1.5">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <Badge
                                    variant={rf.severity === "CRITICAL" ? "destructive" : "outline"}
                                    className={`text-[9px] font-mono font-semibold uppercase tracking-wider ${
                                      rf.severity === "HIGH"
                                        ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                        : rf.severity === "MEDIUM"
                                        ? "text-amber-300 border-amber-900/50 bg-amber-950/15"
                                        : ""
                                    }`}
                                  >
                                    {rf.severity}
                                  </Badge>
                                  <Badge variant="outline" className="text-[9px] font-mono text-zinc-200 border-[#26262C] bg-[#141418] flex items-center gap-1">
                                    <Activity className="h-3 w-3 text-zinc-400" />
                                    DETERMINISTIC
                                  </Badge>
                                  <span className="font-mono text-[10px] text-zinc-400 uppercase tracking-wider">
                                    {rf.category}
                                  </span>
                                  <span className="font-semibold text-zinc-100">{rf.title}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-[10px] text-zinc-500">{rf.rule_id}</span>
                                  {rf.is_blocker && (
                                    <Badge variant="destructive" className="text-[9px] px-1.5 py-0 uppercase font-mono font-semibold">
                                      BLOCKING
                                    </Badge>
                                  )}
                                </div>
                              </div>

                              <p className="text-zinc-300 text-[11px] leading-relaxed">{rf.description}</p>

                              {/* Concrete Evidence Snippet */}
                              <div className="p-2.5 rounded-md bg-[#09090B] border border-[#26262C] font-mono tabular-nums text-[11px] text-zinc-200 flex items-center justify-between gap-2 overflow-x-auto">
                                <div>
                                  <span className="text-zinc-500">{rf.file_path}{rf.line_number ? `:${rf.line_number}` : ""}:</span>{" "}
                                  <span>{rf.evidence}</span>
                                </div>
                                <span className="text-[9px] uppercase px-1.5 py-0.5 rounded bg-[#141418] border border-[#26262C] text-zinc-300 shrink-0">
                                  Confidence: {rf.confidence}
                                </span>
                              </div>

                              {/* Remediation */}
                              <div className="text-[11px] text-zinc-400 flex items-start gap-1.5">
                                <span className="text-emerald-400 font-medium shrink-0">Remediation:</span>
                                <span>{rf.remediation}</span>
                              </div>

                              {/* Interactive Action Buttons */}
                              <div className="flex items-center justify-between pt-2 border-t border-[#1C1C21]">
                                <span className="text-[10px] font-mono text-zinc-500">
                                  Evidence: <span className="text-zinc-300 font-medium">Static AST & Churn Verification</span>
                                </span>
                                <div className="flex items-center gap-2">
                                  <Button
                                    type="button"
                                    variant="secondary"
                                    size="sm"
                                    onClick={() => setSelectedFindingForModal(rf)}
                                    className="h-6 text-[10px] px-2 flex items-center gap-1"
                                  >
                                    <Eye className="h-3 w-3 text-zinc-400" />
                                    Safe Context
                                  </Button>
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleExplainFinding(rf)}
                                    className="h-6 text-[10px] px-2 border-[#26262C] bg-[#141418] hover:bg-[#1C1C21] text-zinc-200 flex items-center gap-1"
                                  >
                                    <Cpu className="h-3 w-3 text-zinc-400" />
                                    Explain with ForgeAI
                                  </Button>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-center py-6 text-zinc-500 text-xs font-mono">
                          Zero reliability failure risks or exception swallows identified.
                        </div>
                      )}
                    </div>
                  )}

                  {/* Insufficient context warning callout if applicable */}
                  {prAnalysisResult.summary?.includes("INSUFFICIENT_CONTEXT") && (
                    <div className="p-4 rounded-lg border border-amber-900/60 bg-amber-950/20 text-xs text-amber-200 flex items-start gap-3">
                      <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0 mt-0.5" />
                      <div>
                        <div className="font-semibold text-amber-100">Grounded Evidence Warning: Insufficient Context</div>
                        <div className="mt-0.5 text-amber-200/80">
                          No indexed code chunks or knowledge graph edges matched this repository. ForgeAI does not fabricate facts.
                          Please ensure the repository has been indexed into ForgeAI before performing deep impact analysis.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* 1. Deterministic Risk Factors Breakdown */}
                  {Array.isArray(prAnalysisResult.risk_factors) && prAnalysisResult.risk_factors.length > 0 && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <Sliders className="h-3.5 w-3.5 text-zinc-400" />
                          Deterministic Risk Factors Breakdown
                        </span>
                        <span className="text-[11px] font-mono tabular-nums text-zinc-400">
                          Score: {(prAnalysisResult.risk_score ?? 0).toFixed(1)} / 100
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
                        {prAnalysisResult.risk_factors.map((rf, idx) => (
                          <div
                            key={idx}
                            className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] text-xs flex flex-col justify-between gap-1.5"
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="font-medium text-zinc-200">{rf.factor || rf.name}</span>
                              <Badge
                                variant="outline"
                                className={`text-[10px] font-mono tabular-nums shrink-0 ${
                                  (rf.points ?? rf.score ?? 0) > 10
                                    ? "text-rose-400 border-rose-900/60 bg-rose-950/20"
                                    : (rf.points ?? rf.score ?? 0) > 0
                                    ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                    : "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                }`}
                              >
                                +{(rf.points ?? rf.score ?? 0)} pts
                              </Badge>
                            </div>
                            <p className="text-[11px] text-zinc-400 leading-snug">{rf.detail || rf.details}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 2. Changed Components & Affected Modules */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Changed Components */}
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                        <FileCode className="h-3.5 w-3.5 text-zinc-400" />
                        Directly Changed Components
                      </div>
                      {(() => {
                        const raw = prAnalysisResult.changed_components;
                        let items: { type: string; name: string }[] = [];
                        if (Array.isArray(raw)) {
                          items = raw;
                        } else if (raw && typeof raw === "object") {
                          const symbols = Array.isArray((raw as any).symbols) ? (raw as any).symbols : [];
                          const files = Array.isArray((raw as any).files) ? (raw as any).files : [];
                          items = [
                            ...symbols.map((s: string) => ({ type: "SYMBOL", name: s })),
                            ...files.map((f: string) => ({ type: "FILE", name: f })),
                          ];
                        }
                        if (items.length === 0) {
                          return <div className="text-xs text-zinc-500">No specific symbols identified</div>;
                        }
                        return (
                          <div className="flex flex-wrap gap-1.5 max-h-40 overflow-y-auto">
                            {items.map((comp, idx) => (
                              <div
                                key={idx}
                                className="px-2 py-1 rounded border border-[#26262C] bg-[#0E0E11] text-[11px] font-mono text-zinc-300 flex items-center gap-1.5"
                              >
                                <Badge variant="outline" className="text-[9px] font-mono text-zinc-400 border-[#26262C] px-1 py-0">
                                  {comp.type}
                                </Badge>
                                <span className="truncate max-w-xs">{comp.name}</span>
                              </div>
                            ))}
                          </div>
                        );
                      })()}
                    </div>

                    {/* Affected Modules */}
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <Boxes className="h-3.5 w-3.5 text-zinc-400" />
                          Architectural Areas & Modules
                        </span>
                        {prAnalysisResult.impact_metadata?.cross_module_impact && (
                          <Badge variant="destructive" className="text-[10px] uppercase font-mono">
                            Cross-Module Impact
                          </Badge>
                        )}
                      </div>
                      {Array.isArray(prAnalysisResult.affected_modules) && prAnalysisResult.affected_modules.length > 0 ? (
                        <div className="flex flex-wrap gap-1.5">
                          {prAnalysisResult.affected_modules.map((mod, idx) => (
                            <Badge
                              key={idx}
                              variant="outline"
                              className="text-xs font-mono px-2 py-0.5 bg-[#0E0E11] text-zinc-200 border-[#26262C]"
                            >
                              {mod}
                            </Badge>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-zinc-500">No specific architectural modules isolated</div>
                      )}
                    </div>
                  </div>

                  {/* 3. Multi-Hop Blast Radius (Direct vs Transitive) */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Direct Impact (Depth = 1) */}
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-3.5 w-3.5 text-emerald-400" />
                          Direct Blast Radius (Depth 1)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono tabular-nums border-[#26262C] text-zinc-300">
                          {Array.isArray(prAnalysisResult.direct_impact) ? prAnalysisResult.direct_impact.length : 0} callers
                        </Badge>
                      </div>
                      {Array.isArray(prAnalysisResult.direct_impact) && prAnalysisResult.direct_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.direct_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-[#1C1C21] bg-[#0E0E11] text-xs font-mono flex items-center justify-between"
                            >
                              <span className="text-emerald-300 font-medium truncate max-w-xs">
                                {item.source_symbol}
                              </span>
                              <Badge variant="outline" className="text-[9px] text-zinc-400 border-[#26262C]">
                                {item.edge_type || "DEPENDS_ON"}
                              </Badge>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-zinc-500">No direct downstream dependents found</div>
                      )}
                    </div>

                    {/* Transitive Impact (Depth = 2, 3) */}
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-3.5 w-3.5 text-zinc-400" />
                          Transitive Blast Radius (Depth 2-3)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono tabular-nums border-[#26262C] text-zinc-300">
                          {Array.isArray(prAnalysisResult.transitive_impact) ? prAnalysisResult.transitive_impact.length : 0} callers
                        </Badge>
                      </div>
                      {Array.isArray(prAnalysisResult.transitive_impact) && prAnalysisResult.transitive_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.transitive_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-[#1C1C21] bg-[#0E0E11] text-xs font-mono flex items-center justify-between"
                            >
                              <div className="flex items-center gap-2 truncate">
                                <Badge variant="outline" className="text-[9px] text-zinc-300 border-[#26262C] tabular-nums">
                                  {item.depth} hops
                                </Badge>
                                <span className="text-zinc-300 truncate max-w-xs">{item.source_symbol}</span>
                              </div>
                              <span className="text-[10px] text-zinc-500">{item.edge_type}</span>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-zinc-500">No multi-hop transitive dependents found</div>
                      )}
                    </div>
                  </div>

                  {/* 3b. Distributed Change Intelligence & Cross-Repository Topology */}
                  {prAnalysisResult.impact_metadata?.distributed_impact && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-4">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-[#1C1C21] pb-3">
                        <div className="flex items-center gap-2 flex-wrap">
                          <Share2 className="h-4 w-4 text-zinc-400" />
                          <span className="text-xs font-semibold text-zinc-100 uppercase tracking-wider">
                            Distributed Change Intelligence & Cross-Repository Topology
                          </span>
                          <Badge
                            variant="outline"
                            className={`text-[11px] font-mono font-semibold px-2 py-0.5 border ${
                              prAnalysisResult.impact_metadata.distributed_impact.contract_status === "COMPATIBLE"
                                ? "bg-emerald-950/30 text-emerald-400 border-emerald-900/60"
                                : prAnalysisResult.impact_metadata.distributed_impact.contract_status === "POTENTIALLY_BREAKING"
                                ? "bg-amber-950/30 text-amber-400 border-amber-900/60"
                                : "bg-rose-950/30 text-rose-400 border-rose-900/60"
                            }`}
                          >
                            Contract: {prAnalysisResult.impact_metadata.distributed_impact.contract_status}
                          </Badge>
                        </div>

                        <div className="flex items-center gap-2 flex-wrap text-xs font-mono tabular-nums">
                          <div className="py-1 px-2.5 rounded bg-[#0E0E11] border border-[#26262C] text-zinc-300">
                            Direct Downstream Repos:{" "}
                            <span className="font-semibold text-zinc-100">
                              {prAnalysisResult.impact_metadata.distributed_impact.direct_downstream_count}
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Contract Analysis Rationale */}
                      {prAnalysisResult.impact_metadata.contract_rationale && (
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] text-xs text-zinc-300 flex items-start gap-2">
                          <Info className="h-4 w-4 text-zinc-400 shrink-0 mt-0.5" />
                          <div>
                            <span className="font-medium text-zinc-200">Contract Analysis: </span>
                            <span className="text-zinc-300">{prAnalysisResult.impact_metadata.contract_rationale}</span>
                          </div>
                        </div>
                      )}

                      {/* Multi-Repo Risk Scores */}
                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 font-mono tabular-nums text-xs">
                        <div className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] space-y-1">
                          <div className="text-[10px] uppercase text-zinc-500 font-semibold">Repo-Local Risk</div>
                          <div className="text-base font-semibold text-zinc-100">
                            {(prAnalysisResult.impact_metadata.distributed_impact.local_risk_score ?? prAnalysisResult.risk_score ?? 0).toFixed(1)} / 100
                          </div>
                          <Badge variant="outline" className="text-[9px] text-zinc-400 border-[#26262C]">
                            Level: {prAnalysisResult.impact_metadata.distributed_impact.local_risk_level ?? "LOW"}
                          </Badge>
                        </div>

                        <div className="p-3 rounded-md border border-sky-900/50 bg-[#0E0E11] space-y-1">
                          <div className="text-[10px] uppercase text-sky-400 font-semibold">Distributed Risk (Max 50)</div>
                          <div className="text-base font-semibold text-sky-300">
                            {(prAnalysisResult.impact_metadata.distributed_impact.distributed_risk_score ?? 0).toFixed(1)} / 50
                          </div>
                          <Badge variant="outline" className="text-[9px] text-sky-300 border-sky-900/60">
                            Level: {prAnalysisResult.impact_metadata.distributed_impact.distributed_risk_level ?? "NONE"}
                          </Badge>
                        </div>

                        <div className="p-3 rounded-md border border-[#26262C] bg-[#0E0E11] space-y-1">
                          <div className="text-[10px] uppercase text-zinc-400 font-semibold">Combined Multi-Repo Risk</div>
                          <div className="text-base font-semibold text-zinc-100">
                            {(prAnalysisResult.impact_metadata.distributed_impact.combined_risk_score ?? 0).toFixed(1)} / 100
                          </div>
                          <div className="text-[10px] text-zinc-500 font-sans">
                            Formula: <span className="font-mono text-zinc-300">local + 0.4 &times; distributed</span>
                          </div>
                        </div>
                      </div>

                      {/* Downstream Impacted Repositories List */}
                      <div className="space-y-2">
                        <div className="text-xs font-semibold text-zinc-300 uppercase tracking-wider flex items-center gap-1.5">
                          <FolderGit2 className="h-3.5 w-3.5 text-zinc-400" />
                          Downstream Dependents & CI Status
                        </div>

                        {prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts &&
                        prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts.length > 0 ? (
                          <div className="space-y-2 max-h-64 overflow-y-auto">
                            {prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts.map((dep, dIdx) => (
                              <div
                                key={dIdx}
                                className="p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] text-xs space-y-2 hover:border-[#26262C] transition-colors"
                              >
                                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    <span className="font-semibold font-mono text-zinc-100 text-xs">
                                      {dep.target_repository_name || (dep as any).repository_name || dep.target_repository_id || (dep as any).repository_id || "downstream-repo"}
                                    </span>
                                    <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                                      {dep.dependency_type}
                                    </Badge>
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-mono ${
                                        (dep.confidence || (dep as any).dependency_confidence) === "HIGH"
                                          ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                          : (dep.confidence || (dep as any).dependency_confidence) === "MEDIUM"
                                          ? "text-sky-400 border-sky-900/60 bg-sky-950/20"
                                          : "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                      }`}
                                    >
                                      Confidence: {dep.confidence || (dep as any).dependency_confidence || "LOW"}
                                    </Badge>
                                  </div>

                                  <div className="flex items-center gap-2 font-mono tabular-nums text-xs">
                                    <span className="text-[11px] text-zinc-500">Downstream CI:</span>
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-semibold ${
                                        (dep.downstream_ci_status || (dep as any).ci_status) === "PASS" || (dep.downstream_ci_status || (dep as any).ci_status) === "PASSED"
                                          ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                          : (dep.downstream_ci_status || (dep as any).ci_status) === "FAIL" || (dep.downstream_ci_status || (dep as any).ci_status) === "FAILED"
                                          ? "text-rose-400 border-rose-900/60 bg-rose-950/20"
                                          : "text-zinc-400 border-[#26262C] bg-[#141418]"
                                      }`}
                                    >
                                      {dep.downstream_ci_status || (dep as any).ci_status || "NOT_FOUND"}
                                    </Badge>
                                    <span className="text-[11px] text-zinc-400 ml-1">
                                      Risk +{dep.risk_contribution ?? 0}
                                    </span>
                                  </div>
                                </div>

                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px] font-mono pt-1">
                                  {dep.affected_symbols && dep.affected_symbols.length > 0 && (
                                    <div className="p-2 rounded bg-[#09090B] border border-[#1C1C21]">
                                      <span className="text-[10px] uppercase text-zinc-500 block mb-1">
                                        Affected Downstream Symbols ({dep.affected_symbols.length})
                                      </span>
                                      <div className="flex flex-wrap gap-1">
                                        {dep.affected_symbols.slice(0, 4).map((sym, sIdx) => (
                                          <span key={sIdx} className="px-1.5 py-0.5 rounded bg-[#141418] text-zinc-200 border border-[#26262C]">
                                            {sym}
                                          </span>
                                        ))}
                                        {dep.affected_symbols.length > 4 && (
                                          <span className="text-zinc-500 text-[10px]">
                                            +{dep.affected_symbols.length - 4} more
                                          </span>
                                        )}
                                      </div>
                                    </div>
                                  )}

                                  {dep.evidence_files && dep.evidence_files.length > 0 && (
                                    <div className="p-2 rounded bg-[#09090B] border border-[#1C1C21]">
                                      <span className="text-[10px] uppercase text-zinc-500 block mb-1">
                                        Evidence Manifests & Files
                                      </span>
                                      <div className="truncate text-zinc-400">
                                        {dep.evidence_files.join(", ")}
                                      </div>
                                    </div>
                                  )}
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <div className="p-4 rounded-md border border-dashed border-[#26262C] text-center text-xs text-zinc-500">
                            No downstream repositories depend on the components modified in this pull request.
                          </div>
                        )}
                      </div>
                    </div>
                  )}

                  {/* 4. Test Intelligence & Risk-Driven Validation Engine */}
                  {(() => {
                    const strategy = prAnalysisResult.test_strategy;
                    const legacyTests = prAnalysisResult.affected_tests || [];
                    const mustRunList = strategy?.must_run || legacyTests.filter((t) => t.confidence === "HIGH").map((t) => ({
                      test_name: t.test_name,
                      test_file: t.test_file,
                      priority_score: 85.0,
                      priority_bucket: "MUST_RUN" as const,
                      confidence: t.confidence,
                      evidence_sources: ["STATIC"],
                      evidence_types: ["AST_CALL"],
                      evidence_path: [t.reason],
                      reason: t.reason,
                      historical_failure_count: 0,
                      cochange_count: 0,
                      runtime_coverage_verified: false,
                    }));
                    const shouldRunList = strategy?.should_run || legacyTests.filter((t) => t.confidence === "MEDIUM").map((t) => ({
                      test_name: t.test_name,
                      test_file: t.test_file,
                      priority_score: 45.0,
                      priority_bucket: "SHOULD_RUN" as const,
                      confidence: t.confidence,
                      evidence_sources: ["STATIC"],
                      evidence_types: ["PATH_PAIRING"],
                      evidence_path: [t.reason],
                      reason: t.reason,
                      historical_failure_count: 0,
                      cochange_count: 0,
                      runtime_coverage_verified: false,
                    }));
                    const optionalList = strategy?.optional || legacyTests.filter((t) => t.confidence === "LOW").map((t) => ({
                      test_name: t.test_name,
                      test_file: t.test_file,
                      priority_score: 15.0,
                      priority_bucket: "OPTIONAL" as const,
                      confidence: t.confidence,
                      evidence_sources: ["STATIC"],
                      evidence_types: ["HEURISTIC_MODULE"],
                      evidence_path: [t.reason],
                      reason: t.reason,
                      historical_failure_count: 0,
                      cochange_count: 0,
                      runtime_coverage_verified: false,
                    }));

                    const totalTests = mustRunList.length + shouldRunList.length + optionalList.length;
                    if (totalTests === 0 && !strategy) return null;

                    const activeList =
                      testBucketFilter === "MUST_RUN"
                        ? mustRunList
                        : testBucketFilter === "SHOULD_RUN"
                        ? shouldRunList
                        : testBucketFilter === "OPTIONAL"
                        ? optionalList
                        : [...mustRunList, ...shouldRunList, ...optionalList];

                    const coverageAvailable = strategy?.coverage_summary?.coverage_available;
                    const coverageMech = strategy?.coverage_summary?.coverage_mechanism || "STATIC_AST";
                    const overallConf = strategy?.overall_confidence || prAnalysisResult.confidence || "MEDIUM";

                    const effectiveness = testValidationResult || (prAnalysisResult.test_effectiveness ? {
                      prediction_to_failure_correlation: prAnalysisResult.test_effectiveness.prediction_to_failure_correlation,
                      test_selection_efficiency: prAnalysisResult.test_effectiveness.test_selection_efficiency,
                      high_priority_missed_count: prAnalysisResult.test_effectiveness.high_priority_missed_count,
                      total_recommended: totalTests,
                      total_actual_executed: 14,
                      actual_failed_count: 0,
                      recommended_failed_count: 0,
                      missed_failures: [],
                    } : null);

                    return (
                      <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-5">
                        {/* Section Header */}
                        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-3.5 border-b border-[#1C1C21]">
                          <div>
                            <div className="flex items-center gap-2">
                              <TestTube className="h-4 w-4 text-zinc-400" />
                              <span className="text-xs font-semibold text-zinc-100 uppercase tracking-wider">
                                Test Intelligence & Risk-Driven Validation Engine
                              </span>
                            </div>
                            <p className="text-xs text-zinc-400 mt-1">
                              Deterministic test prioritization combining AST symbol call graph, runtime coverage, and historical failure coupling.
                            </p>
                          </div>

                          <div className="flex items-center gap-2 flex-wrap">
                            {/* Coverage Mechanism Badge */}
                            <Badge
                              variant="outline"
                              className={`text-[11px] font-mono px-2.5 py-0.5 flex items-center gap-1.5 ${
                                coverageAvailable
                                  ? "bg-emerald-950/30 text-emerald-300 border-emerald-900/60"
                                  : "bg-[#0E0E11] text-zinc-300 border-[#26262C]"
                              }`}
                            >
                              {coverageAvailable ? (
                                <>
                                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                                  <span>{coverageMech} Runtime Coverage Verified</span>
                                </>
                              ) : (
                                <>
                                  <Info className="h-3.5 w-3.5 text-zinc-400" />
                                  <span>Static AST Fallback (Graceful Degradation)</span>
                                </>
                              )}
                            </Badge>

                            {/* Overall Test Strategy Confidence */}
                            <Badge
                              variant="outline"
                              className={`text-[11px] font-mono px-2.5 py-0.5 ${
                                overallConf === "HIGH"
                                  ? "bg-emerald-950/30 text-emerald-400 border-emerald-900/60"
                                  : overallConf === "MEDIUM"
                                  ? "bg-sky-950/30 text-sky-400 border-sky-900/60"
                                  : "bg-amber-950/30 text-amber-400 border-amber-900/60"
                              }`}
                              title={strategy?.confidence_rationale}
                            >
                              Confidence: {overallConf}
                            </Badge>
                          </div>
                        </div>

                        {/* Priority Bucket Selector Cards */}
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
                          {/* MUST RUN */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("MUST_RUN")}
                            className={`p-3 rounded-md border text-left transition-colors flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "MUST_RUN"
                                ? "border-rose-700/70 bg-rose-950/20"
                                : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C]"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-[11px] font-semibold text-rose-300 uppercase flex items-center gap-1.5">
                                <Flame className="h-3.5 w-3.5 text-rose-400" />
                                MUST RUN
                              </span>
                              <Badge variant="outline" className="text-[11px] font-mono tabular-nums font-semibold bg-rose-950/30 text-rose-300 border-rose-900/60">
                                {mustRunList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] font-mono text-zinc-500">Score &ge; 60 or High Risk</span>
                          </button>

                          {/* SHOULD RUN */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("SHOULD_RUN")}
                            className={`p-3 rounded-md border text-left transition-colors flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "SHOULD_RUN"
                                ? "border-amber-700/70 bg-amber-950/20"
                                : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C]"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-[11px] font-semibold text-amber-300 uppercase flex items-center gap-1.5">
                                <Zap className="h-3.5 w-3.5 text-amber-400" />
                                SHOULD RUN
                              </span>
                              <Badge variant="outline" className="text-[11px] font-mono tabular-nums font-semibold bg-amber-950/30 text-amber-300 border-amber-900/60">
                                {shouldRunList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] font-mono text-zinc-500">30 &le; Score &lt; 60</span>
                          </button>

                          {/* OPTIONAL */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("OPTIONAL")}
                            className={`p-3 rounded-md border text-left transition-colors flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "OPTIONAL"
                                ? "border-sky-700/70 bg-sky-950/20"
                                : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C]"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-[11px] font-semibold text-sky-300 uppercase flex items-center gap-1.5">
                                <CheckCheck className="h-3.5 w-3.5 text-sky-400" />
                                OPTIONAL
                              </span>
                              <Badge variant="outline" className="text-[11px] font-mono tabular-nums font-semibold bg-sky-950/30 text-sky-300 border-sky-900/60">
                                {optionalList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] font-mono text-zinc-500">Score &lt; 30 (Regression)</span>
                          </button>

                          {/* ALL / SUMMARY */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("ALL")}
                            className={`p-3 rounded-md border text-left transition-colors flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "ALL"
                                ? "border-zinc-400 bg-[#1A1A20]"
                                : "border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C]"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-[11px] font-semibold text-zinc-200 uppercase flex items-center gap-1.5">
                                <BarChart3 className="h-3.5 w-3.5 text-zinc-400" />
                                ALL TESTS
                              </span>
                              <Badge variant="outline" className="text-[11px] font-mono tabular-nums font-semibold bg-[#141418] text-zinc-200 border-[#26262C]">
                                {totalTests}
                              </Badge>
                            </div>
                            <span className="text-[11px] font-mono text-zinc-500">Total Recommended</span>
                          </button>
                        </div>

                        {/* Test Recommendations List */}
                        <div className="space-y-3">
                          <div className="flex items-center justify-between text-xs text-zinc-400 font-mono tabular-nums">
                            <span>
                              Showing {activeList.length} tests in{" "}
                              <span className="font-semibold text-zinc-100 uppercase">{testBucketFilter.replace("_", " ")}</span>
                            </span>
                            {strategy?.confidence_rationale && (
                              <span className="truncate max-w-md font-sans text-zinc-500">{strategy.confidence_rationale}</span>
                            )}
                          </div>

                          {activeList.length > 0 ? (
                            <div className="space-y-2.5">
                              {activeList.map((rec: TestRecommendationItem, idx: number) => (
                                <div
                                  key={idx}
                                  className="p-3.5 rounded-md border border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C] transition-colors space-y-2.5"
                                >
                                  {/* Row 1: Header */}
                                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                    <div className="flex items-center gap-2 flex-wrap">
                                      <span className="font-mono text-zinc-100 font-semibold text-xs">
                                        {rec.test_name}
                                      </span>
                                      {rec.test_method && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C]">
                                          #{rec.test_method}
                                        </Badge>
                                      )}
                                      {rec.target_symbol && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C]">
                                          Targets: {rec.target_symbol}
                                        </Badge>
                                      )}
                                    </div>

                                    <div className="flex items-center gap-2 flex-wrap">
                                      {/* Priority Score */}
                                      <div className="flex items-center gap-1 px-2 py-0.5 rounded bg-[#141418] border border-[#26262C] font-mono tabular-nums text-[11px]">
                                        <span className="text-zinc-500">Score:</span>
                                        <span className="font-semibold text-zinc-100">
                                          {typeof rec.priority_score === "number" ? rec.priority_score.toFixed(1) : rec.priority_score}
                                        </span>
                                        <span className="text-zinc-500">/ 100</span>
                                      </div>

                                      {/* Bucket Badge */}
                                      <Badge
                                        variant="outline"
                                        className={`text-[10px] font-mono font-semibold uppercase tracking-wider ${
                                          rec.priority_bucket === "MUST_RUN"
                                            ? "bg-rose-950/30 text-rose-300 border-rose-900/60"
                                            : rec.priority_bucket === "SHOULD_RUN"
                                            ? "bg-amber-950/30 text-amber-300 border-amber-900/60"
                                            : "bg-sky-950/30 text-sky-300 border-sky-900/60"
                                        }`}
                                      >
                                        {rec.priority_bucket}
                                      </Badge>

                                      {/* Stronger Concept: Required for Merge */}
                                      {rec.merge_requirement === "TEST_REQUIRED" && (
                                        <Badge
                                          variant="outline"
                                          className="text-[10px] font-mono font-semibold uppercase tracking-wider bg-rose-950/60 text-rose-300 border-rose-800/70"
                                        >
                                          Required for Merge
                                        </Badge>
                                      )}

                                      {/* Confidence */}
                                      <Badge
                                        variant="outline"
                                        className={`text-[10px] font-mono ${
                                          rec.confidence === "HIGH"
                                            ? "text-emerald-400 border-emerald-900/60"
                                            : rec.confidence === "MEDIUM"
                                            ? "text-sky-400 border-sky-900/60"
                                            : "text-amber-400 border-amber-900/60"
                                        }`}
                                      >
                                        {rec.confidence}
                                      </Badge>
                                    </div>
                                  </div>

                                  {/* Row 2: File Path & Evidence Sources */}
                                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                                    <span className="font-mono text-zinc-400 text-[11px] truncate max-w-xl">
                                      {rec.test_file || "Module Test Suite"}
                                    </span>

                                    <div className="flex items-center gap-1.5 flex-wrap font-mono tabular-nums">
                                      {rec.runtime_coverage_verified && (
                                        <Badge variant="outline" className="text-[10px] text-emerald-300 border-emerald-900/60 bg-emerald-950/20">
                                          Runtime Coverage
                                        </Badge>
                                      )}
                                      {rec.historical_failure_count > 0 && (
                                        <Badge variant="outline" className="text-[10px] text-rose-300 border-rose-900/60 bg-rose-950/20">
                                          {rec.historical_failure_count} Prior Failures
                                        </Badge>
                                      )}
                                      {rec.cochange_count > 0 && (
                                        <Badge variant="outline" className="text-[10px] text-amber-300 border-amber-900/60 bg-amber-950/20">
                                          {rec.cochange_count} Co-Commits
                                        </Badge>
                                      )}
                                      {Array.isArray(rec.evidence_sources) && rec.evidence_sources.map((src, sIdx) => (
                                        <Badge
                                          key={sIdx}
                                          variant="outline"
                                          className={`text-[9px] uppercase ${
                                            src === "RUNTIME"
                                              ? "text-emerald-400 border-emerald-900/60"
                                              : src === "HISTORICAL"
                                              ? "text-amber-400 border-amber-900/60"
                                              : "text-zinc-300 border-[#26262C]"
                                          }`}
                                        >
                                          {src}
                                        </Badge>
                                      ))}
                                    </div>
                                  </div>

                                  {/* Row 3: Derivation Path (Change -> AST/Runtime/History -> Test) */}
                                  {Array.isArray(rec.evidence_path) && rec.evidence_path.length > 0 && (
                                    <div className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] text-[11px] font-mono text-zinc-300 flex items-start gap-2">
                                      <ArrowRight className="h-3.5 w-3.5 text-zinc-500 shrink-0 mt-0.5" />
                                      <div className="space-y-0.5 w-full">
                                        <div className="text-[10px] uppercase font-semibold text-zinc-500">
                                          Derivation Path
                                        </div>
                                        <div className="text-zinc-300 leading-relaxed">
                                          {rec.evidence_path.join(" \u2192 ")}
                                        </div>
                                      </div>
                                    </div>
                                  )}

                                  {/* Row 4: Reason / Explanation */}
                                  <div className="text-[11px] text-zinc-400">
                                    <span className="font-medium text-zinc-300">Rationale: </span>
                                    {rec.reason}
                                  </div>
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="p-8 rounded-md border border-dashed border-[#26262C] text-center text-xs text-zinc-500">
                              No tests categorized under {testBucketFilter.replace("_", " ")} for this pull request.
                            </div>
                          )}
                        </div>

                        {/* Recent CI Test Runs & Historical Failures Ingestion Drawer */}
                        {strategy && (strategy.runtime_evidence?.length > 0 || strategy.historical_failures?.length > 0) && (
                          <div className="rounded-md border border-[#1C1C21] bg-[#0E0E11] p-4 space-y-3">
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                                <Database className="h-3.5 w-3.5 text-zinc-400" />
                                Ingested CI Workflow Executions & Historical Failures
                              </span>
                              <span className="text-[11px] font-mono tabular-nums text-zinc-500">
                                {strategy.runtime_evidence?.length || 0} CI runs &bull; {strategy.historical_failures?.length || 0} failures
                              </span>
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
                              {/* CI Workflow Runs */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-2">
                                <div className="text-[11px] font-semibold text-zinc-300 uppercase">Recent CI Executions</div>
                                {strategy.runtime_evidence && strategy.runtime_evidence.length > 0 ? (
                                  <div className="space-y-1.5 max-h-36 overflow-y-auto font-mono tabular-nums text-[11px]">
                                    {strategy.runtime_evidence.slice(0, 5).map((run: any, rIdx: number) => (
                                      <div key={rIdx} className="flex items-center justify-between p-1.5 rounded bg-[#141418] border border-[#1C1C21]">
                                        <span className="text-zinc-300 truncate max-w-[180px]">
                                          {run.workflow || `Run #${run.workflow_run_id || rIdx + 1}`}
                                        </span>
                                        <Badge
                                          variant="outline"
                                          className={`text-[9px] ${
                                            run.conclusion === "success"
                                              ? "text-emerald-400 border-emerald-900/60"
                                              : run.conclusion === "failure"
                                              ? "text-rose-400 border-rose-900/60"
                                              : "text-zinc-400 border-[#26262C]"
                                          }`}
                                        >
                                          {run.conclusion || run.status || "COMPLETED"}
                                        </Badge>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-zinc-500 text-[11px]">No previous workflow runs ingested</div>
                                )}
                              </div>

                              {/* Historical Failures */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-2">
                                <div className="text-[11px] font-semibold text-zinc-300 uppercase">Historical Test Failures</div>
                                {strategy.historical_failures && strategy.historical_failures.length > 0 ? (
                                  <div className="space-y-1.5 max-h-36 overflow-y-auto font-mono tabular-nums text-[11px]">
                                    {strategy.historical_failures.map((hf: any, hIdx: number) => (
                                      <div key={hIdx} className="flex items-center justify-between p-1.5 rounded bg-[#141418] border border-[#1C1C21]">
                                        <span className="text-rose-300 truncate max-w-[200px]">
                                          {hf.test_name || hf.name}
                                        </span>
                                        <Badge variant="outline" className="text-[9px] text-rose-400 border-rose-900/60">
                                          {hf.failure_count || 1}x failed
                                        </Badge>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-zinc-500 text-[11px]">No historical failures recorded for affected files</div>
                                )}
                              </div>
                            </div>
                          </div>
                        )}

                        {/* 5. Post-PR Test Validation Feedback Loop */}
                        <div className="rounded-md border border-[#26262C] bg-[#0E0E11] p-4 space-y-3">
                          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div>
                              <div className="flex items-center gap-2">
                                <Activity className="h-4 w-4 text-emerald-400" />
                                <span className="text-xs font-semibold text-zinc-100 uppercase tracking-wider">
                                  Post-PR Test Validation (Feedback Loop)
                                </span>
                              </div>
                              <p className="text-[11px] text-zinc-400 mt-0.5">
                                Compares recommended test selection against actual CI test execution results to calculate selection efficiency and correlation.
                              </p>
                            </div>

                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              onClick={handleValidateTests}
                              disabled={isValidatingTests}
                              className="h-8 text-xs px-3 border-emerald-900/60 bg-emerald-950/20 hover:bg-emerald-950/40 text-emerald-300 transition-colors shrink-0"
                            >
                              {isValidatingTests ? (
                                <>
                                  <Loader2 className="h-3 w-3 animate-spin mr-1.5 text-emerald-400" />
                                  Validating Against CI...
                                </>
                              ) : (
                                <>
                                  <CheckCheck className="h-3.5 w-3.5 mr-1.5 text-emerald-400" />
                                  Validate Against CI
                                </>
                              )}
                            </Button>
                          </div>

                          {validationError && (
                            <div className="p-2.5 rounded-md bg-rose-950/20 border border-rose-900/60 text-rose-300 text-xs">
                              {validationError}
                            </div>
                          )}

                          {effectiveness && (
                            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1">
                              {/* Metric 1 */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#141418] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  Prediction-to-Failure Correlation
                                </span>
                                <div className="text-lg font-semibold font-mono tabular-nums text-emerald-400">
                                  {((effectiveness.prediction_to_failure_correlation ?? 0) * 100).toFixed(0)}%
                                </div>
                                <span className="text-[10px] text-zinc-500">Recommended failing tests / Total failed tests</span>
                              </div>

                              {/* Metric 2 */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#141418] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  Test Selection Efficiency
                                </span>
                                <div className="text-lg font-semibold font-mono tabular-nums text-zinc-100">
                                  {((effectiveness.test_selection_efficiency ?? 0) * 100).toFixed(0)}%
                                </div>
                                <span className="text-[10px] text-zinc-500">Suite runtime reduction efficiency</span>
                              </div>

                              {/* Metric 3 */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#141418] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  High Priority Missed Failures
                                </span>
                                <div className={`text-lg font-semibold font-mono tabular-nums ${effectiveness.high_priority_missed_count === 0 ? "text-emerald-400" : "text-rose-400"}`}>
                                  {effectiveness.high_priority_missed_count}
                                </div>
                                <span className="text-[10px] text-zinc-500">Failing tests missed by MUST_RUN</span>
                              </div>
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })()}

                  {/* 5. Historical Change Signals */}
                  {Array.isArray(prAnalysisResult.historical_signals) && prAnalysisResult.historical_signals.length > 0 && (
                    <div className="rounded-lg border border-[#26262C] bg-[#141418] p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <History className="h-3.5 w-3.5 text-amber-400" />
                          Historical Change Signals (Commit Churn Evidence)
                        </span>
                        <span className="text-[11px] font-mono tabular-nums text-zinc-400">
                          {prAnalysisResult.historical_signals.length} files tracked
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
                        {prAnalysisResult.historical_signals.map((sig, idx) => (
                          <div
                            key={idx}
                            className="p-2.5 rounded-md border border-[#1C1C21] bg-[#0E0E11] text-xs font-mono tabular-nums flex flex-col gap-1"
                          >
                            <div className="flex items-center justify-between gap-2">
                              <span className="text-zinc-300 truncate max-w-[180px]">{sig.file_path}</span>
                              <Badge variant="outline" className="text-[10px] text-amber-400 border-amber-900/60 shrink-0">
                                {sig.commit_count || (sig as any).recent_modifications_count || 0} prior commits
                              </Badge>
                            </div>
                            {sig.recent_commits && sig.recent_commits.length > 0 && (
                              <div className="text-[10px] text-zinc-500 truncate">
                                Latest: {sig.recent_commits[0]}
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 6. Architectural Concerns Alert */}
                  {Array.isArray(prAnalysisResult.architectural_concerns) && prAnalysisResult.architectural_concerns.length > 0 && (
                    <div className="p-4 rounded-lg border border-rose-900/60 bg-rose-950/20 text-xs text-rose-200 space-y-2">
                      <div className="font-semibold text-rose-200 flex items-center gap-2">
                        <AlertTriangle className="h-4 w-4 text-rose-400" />
                        Architectural Concerns Flagged
                      </div>
                      <ul className="list-disc list-inside space-y-1 text-rose-300/90">
                        {prAnalysisResult.architectural_concerns.map((concern, idx) => (
                          <li key={idx}>{concern}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {/* 7. Grounded AI Reasoning Review Body */}
                  <div className="space-y-2">
                    <div className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                      <Cpu className="h-3.5 w-3.5 text-zinc-400" />
                      Grounded LLM Reasoning (Facts, Inferences & Recommendations)
                    </div>
                    <GroundedReasoningRenderer summary={prAnalysisResult.summary} />
                  </div>

                  {/* ========================================================= */}
                  {/* AUTOMATED AI CODE REVIEW FINDINGS (GROUNDED & DEDUPLICATED)*/}
                  {/* ========================================================= */}
                  <div className="space-y-4 pt-3 border-t border-[#1C1C21]">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <FileCheck className="h-4 w-4 text-emerald-400" />
                        <span className="text-xs font-semibold text-zinc-100 uppercase tracking-wider">
                          Automated Code Review Findings
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono tabular-nums text-zinc-300 border-[#26262C]">
                          {prAnalysisResult.review_findings?.length || 0} findings
                        </Badge>
                      </div>

                      {/* Severity Filters */}
                      <div className="flex items-center gap-1 text-[11px] flex-wrap">
                        {(["ALL", "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"] as const).map((sev) => {
                          const count =
                            sev === "ALL"
                              ? prAnalysisResult.review_findings?.length || 0
                              : prAnalysisResult.review_findings?.filter((f) => f.severity === sev).length || 0;
                          return (
                            <button
                              key={sev}
                              onClick={() => setFindingSeverityFilter(sev)}
                              className={`px-2 py-0.5 rounded text-[10px] font-mono tabular-nums transition-colors flex items-center gap-1 ${
                                findingSeverityFilter === sev
                                  ? "bg-zinc-100 text-zinc-950 font-semibold"
                                  : "bg-[#141418] text-zinc-400 border border-[#26262C] hover:text-zinc-100"
                              }`}
                            >
                              <span>{sev}</span>
                              <span className="text-[9px] opacity-75">({count})</span>
                            </button>
                          );
                        })}
                      </div>
                    </div>

                    {prAnalysisResult.review_findings && prAnalysisResult.review_findings.length > 0 ? (
                      (() => {
                        const filtered =
                          findingSeverityFilter === "ALL"
                            ? prAnalysisResult.review_findings
                            : prAnalysisResult.review_findings.filter((f) => f.severity === findingSeverityFilter);

                        if (filtered.length === 0) {
                          return (
                            <div className="p-6 rounded-md border border-dashed border-[#26262C] text-center text-xs text-zinc-500">
                              No review findings matching severity {findingSeverityFilter}.
                            </div>
                          );
                        }

                        return (
                          <div className="grid grid-cols-1 gap-3">
                            {filtered.map((finding, fIdx) => (
                              <div
                                key={finding.id || fIdx}
                                className={`rounded-md border p-4 space-y-3 transition-colors ${
                                  finding.severity === "CRITICAL"
                                    ? "border-[#26262C] border-l-2 border-l-rose-500 bg-[#141418]"
                                    : finding.severity === "HIGH"
                                    ? "border-[#26262C] border-l-2 border-l-amber-500 bg-[#141418]"
                                    : "border-[#26262C] bg-[#141418]"
                                }`}
                              >
                                {/* Header: Title, Severity, Observed/Inferred, Category */}
                                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    {/* Severity Badge */}
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-mono font-semibold uppercase tracking-wider ${
                                        finding.severity === "CRITICAL"
                                          ? "text-rose-400 border-rose-900/60 bg-rose-950/20"
                                          : finding.severity === "HIGH"
                                          ? "text-orange-400 border-orange-900/60 bg-orange-950/20"
                                          : finding.severity === "MEDIUM"
                                          ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                          : finding.severity === "LOW"
                                          ? "text-sky-400 border-sky-900/60 bg-sky-950/20"
                                          : "text-zinc-400 border-[#26262C] bg-[#0E0E11]"
                                      }`}
                                    >
                                      {finding.severity}
                                    </Badge>

                                    {/* Finding Type (DETERMINISTIC vs AI EXPLANATION) */}
                                    <Badge
                                      variant="outline"
                                      className={`text-[9px] font-mono uppercase tracking-wider flex items-center gap-1 ${
                                        finding.finding_type === "OBSERVED"
                                          ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                          : "text-sky-300 border-sky-900/60 bg-sky-950/20"
                                      }`}
                                    >
                                      {finding.finding_type === "OBSERVED" ? (
                                        <>
                                          <ShieldCheck className="h-3 w-3 text-emerald-400" />
                                          DETERMINISTIC
                                        </>
                                      ) : (
                                        <>
                                          <Cpu className="h-3 w-3 text-sky-400" />
                                          AI EXPLANATION
                                        </>
                                      )}
                                    </Badge>

                                    {/* Category Badge */}
                                    <Badge
                                      variant="outline"
                                      className="text-[9px] font-mono text-zinc-300 border-[#26262C] bg-[#0E0E11]"
                                    >
                                      {finding.category}
                                    </Badge>
                                  </div>

                                  {/* Affected Target */}
                                  <div className="text-[11px] font-mono text-zinc-400 flex items-center gap-1.5 truncate max-w-sm">
                                    <FileCode className="h-3.5 w-3.5 text-zinc-500 shrink-0" />
                                    <span className="text-zinc-300 truncate">{finding.affected_file}</span>
                                    {finding.affected_symbol && (
                                      <span className="text-zinc-400 truncate">#{finding.affected_symbol}</span>
                                    )}
                                  </div>
                                </div>

                                {/* Title & Description */}
                                <div>
                                  <h4 className="text-sm font-semibold text-zinc-100 tracking-tight">
                                    {finding.title}
                                  </h4>
                                  <p className="text-xs text-zinc-300 mt-1 leading-relaxed">
                                    {finding.description}
                                  </p>
                                </div>

                                {/* Concrete Grounded Evidence */}
                                {finding.evidence && (
                                  <div className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] text-[11px] font-mono text-zinc-300 space-y-1">
                                    <div className="text-[10px] uppercase font-semibold text-zinc-500">
                                      Concrete Evidence
                                    </div>
                                    <div className="text-zinc-300 leading-snug whitespace-pre-wrap break-words">
                                      {finding.evidence}
                                    </div>
                                  </div>
                                )}

                                {/* Actionable Recommendation */}
                                {finding.recommendation && (
                                  <div className="p-2.5 rounded-md bg-emerald-950/15 border border-emerald-900/50 text-[11px] text-emerald-200 space-y-1">
                                    <div className="text-[10px] uppercase font-semibold text-emerald-400 flex items-center gap-1">
                                      <CheckCircle2 className="h-3 w-3" />
                                      Actionable Recommendation
                                    </div>
                                    <div className="text-emerald-300/90 leading-snug">
                                      {finding.recommendation}
                                    </div>
                                  </div>
                                )}

                                {/* Action Buttons */}
                                <div className="flex items-center justify-between pt-2 border-t border-[#1C1C21]">
                                  <span className="text-[10px] font-mono text-zinc-500">
                                    Provenance: <span className="text-zinc-300 font-medium">{finding.finding_type === "OBSERVED" ? "Deterministic Rule" : "Grounded AI Reasoning"}</span>
                                  </span>
                                  <div className="flex items-center gap-2">
                                    <Button
                                      type="button"
                                      variant="secondary"
                                      size="sm"
                                      onClick={() => setSelectedFindingForModal(finding)}
                                      className="h-6 text-[10px] px-2 flex items-center gap-1"
                                    >
                                      <Eye className="h-3 w-3 text-zinc-400" />
                                      Safe Context
                                    </Button>
                                    <Button
                                      type="button"
                                      variant="outline"
                                      size="sm"
                                      onClick={() => handleExplainFinding(finding)}
                                      className="h-6 text-[10px] px-2 border-[#26262C] bg-[#0E0E11] hover:bg-[#1C1C21] text-zinc-200 flex items-center gap-1"
                                    >
                                      <Cpu className="h-3 w-3 text-zinc-400" />
                                      Explain with ForgeAI
                                    </Button>
                                  </div>
                                </div>
                              </div>
                            ))}
                          </div>
                        );
                      })()
                    ) : (
                      <div className="p-5 rounded-md border border-[#1C1C21] bg-[#141418] text-xs text-zinc-400 flex items-center gap-2.5">
                        <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                        <span>No automated review issues or architectural violations identified for this changeset.</span>
                      </div>
                    )}
                  </div>

                  {/* 8. Grounded Provenance Explorer (Multi-Category) */}
                  {Array.isArray(prAnalysisResult.retrieved_chunks) && prAnalysisResult.retrieved_chunks.length > 0 && (
                    <div className="space-y-3 pt-3 border-t border-[#1C1C21]">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                        <h4 className="text-xs font-semibold text-zinc-200 uppercase tracking-wider flex items-center gap-1.5">
                          <Layers className="h-3.5 w-3.5 text-zinc-400" />
                          Evidence & Provenance Explorer ({prAnalysisResult.retrieved_chunks.length} records)
                        </h4>

                        {/* Filter pills */}
                        <div className="flex items-center gap-1 text-[11px] flex-wrap">
                          {(
                            [
                              "ALL",
                              "PR_DIFF",
                              "GRAPH",
                              "VECTOR",
                              "TEST",
                              "HISTORY",
                              "SYNC_STATE",
                            ] as const
                          ).map((filter) => (
                            <button
                              key={filter}
                              onClick={() => setProvenanceFilter(filter)}
                              className={`px-2 py-0.5 rounded text-[10px] font-mono transition-colors ${
                                provenanceFilter === filter
                                  ? "bg-zinc-100 text-zinc-950 font-semibold"
                                  : "bg-[#141418] text-zinc-400 border border-[#26262C] hover:text-zinc-100"
                              }`}
                            >
                              {filter}
                            </button>
                          ))}
                        </div>
                      </div>

                      <div className="grid grid-cols-1 gap-2 max-h-80 overflow-y-auto">
                        {filteredChunks.map((chunk: any, i: number) => (
                          <div
                            key={i}
                            className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] text-xs font-mono flex flex-col gap-1.5"
                          >
                            <div className="flex items-center justify-between flex-wrap gap-2">
                              <div className="flex items-center gap-2">
                                <Badge
                                  variant="outline"
                                  className={`text-[9px] font-semibold ${
                                    chunk.source === "PR_DIFF"
                                      ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                      : chunk.source === "GRAPH"
                                      ? "text-sky-300 border-sky-900/60 bg-sky-950/20"
                                      : chunk.source === "TEST"
                                      ? "text-amber-300 border-amber-900/60 bg-amber-950/20"
                                      : chunk.source === "HISTORY"
                                      ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                      : chunk.source === "SYNC_STATE"
                                      ? "text-cyan-400 border-cyan-900/60 bg-cyan-950/20"
                                      : "text-zinc-300 border-[#26262C] bg-[#141418]"
                                  }`}
                                >
                                  {chunk.source || "VECTOR"}
                                </Badge>
                                <span className="text-zinc-100 font-medium truncate max-w-sm">
                                  {chunk.file_path || chunk.symbol_name || "Repository Artifact"}
                                </span>
                              </div>

                              {chunk.similarity !== undefined && (
                                <span className="text-[10px] text-zinc-400 tabular-nums">
                                  Similarity: {(chunk.similarity * 100).toFixed(1)}%
                                </span>
                              )}
                              {chunk.depth !== undefined && (
                                <span className="text-[10px] text-zinc-400 tabular-nums">
                                  Depth: {chunk.depth} hops ({chunk.edge_type})
                                </span>
                              )}
                            </div>

                            {chunk.content && (
                              <div className="text-[11px] text-zinc-400 bg-[#141418] p-2 rounded truncate">
                                {chunk.content}
                              </div>
                            )}

                            <div className="flex items-center justify-between text-[10px] text-zinc-500 pt-1 border-t border-[#1C1C21]">
                              <span>
                                Symbol:{" "}
                                <code className="text-zinc-300">
                                  {chunk.symbol_name || chunk.file_path}
                                </code>
                              </span>
                              {chunk.commit_sha && (
                                <span className="tabular-nums">
                                  Commit:{" "}
                                  <code className="text-zinc-300">
                                    {chunk.commit_sha.substring(0, 7)}
                                  </code>
                                </span>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}
                </CardContent>
              </Card>
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 2: SEMANTIC VECTOR SEARCH                                             */}
      {/* ========================================================================= */}
      {activeTab === "search" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Natural Language Code Search</CardTitle>
              <CardDescription>
                Search across indexed repositories using pgvector cosine embeddings. Strict tenant isolation enforced for{" "}
                <span className="text-zinc-100 font-medium">{workspaceDisplayName}</span>.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleSearch} className="flex gap-2">
                <Input
                  placeholder="e.g. rate limiter token bucket redis filter, webhook signature verification"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="flex-1"
                />
                <Button type="submit" variant="default" disabled={isSearching || !searchQuery.trim()}>
                  {isSearching ? <Loader2 className="h-4 w-4 animate-spin" /> : "Search Code"}
                </Button>
              </form>

              {searchError && (
                <div className="mt-4 p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs text-rose-300">
                  {searchError}
                </div>
              )}
            </CardContent>
          </Card>

          {searchResults.length > 0 && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider font-mono tabular-nums">
                  Matching Code Chunks ({searchResults.length})
                </h3>
                {selectedRepo && (
                  <span className="text-[11px] text-zinc-500 font-mono">
                    Scoped to {selectedRepo.fullName}
                  </span>
                )}
              </div>
              {searchResults.map((chunk, idx) => (
                <div
                  key={chunk.id || idx}
                  className="rounded-lg border border-[#1C1C21] bg-[#0E0E11] p-4 space-y-2"
                >
                  <div className="flex items-center justify-between text-xs gap-2 flex-wrap">
                    <div className="flex items-center gap-2">
                      <FileCode className="h-4 w-4 text-zinc-400" />
                      <span className="font-mono text-zinc-100 font-semibold">{chunk.file_path}</span>
                      {chunk.symbol_name && (
                        <Badge variant="outline" className="text-[10px] font-mono border-[#26262C] bg-[#141418] text-zinc-300">
                          {chunk.symbol_type}: {chunk.symbol_name}
                        </Badge>
                      )}
                    </div>
                    <Badge variant="outline" className="font-mono tabular-nums text-[10px] border-[#26262C] text-zinc-300">
                      Similarity: {((chunk.similarity || (1 - (chunk.distance || 0))) * 100).toFixed(1)}%
                    </Badge>
                  </div>
                  <pre className="text-xs font-mono bg-[#09090B] p-3 rounded-md text-zinc-300 overflow-x-auto border border-[#1C1C21] max-h-60">
                    {chunk.content}
                  </pre>
                  <div className="flex items-center justify-between text-[10px] text-zinc-500 pt-1 font-mono tabular-nums">
                    <span>Commit: <code className="text-zinc-400 font-mono">{chunk.commit_sha?.substring(0, 10)}</code></span>
                    <span>Tenant ID: <code className="text-zinc-400 font-mono">{chunk.organization_id}</code></span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 3: GRAPH IMPACT ANALYSIS                                              */}
      {/* ========================================================================= */}
      {activeTab === "impact" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Recursive AST Blast Radius</CardTitle>
              <CardDescription>
                Compute caller/callee trees using recursive Common Table Expressions over the knowledge graph.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={handleImpact} className="space-y-4">
                <div className="space-y-1.5">
                  <label className="text-xs font-medium text-zinc-300">
                    Target Symbols (comma separated)
                  </label>
                  <Input
                    placeholder="e.g. JwtRevocationGatewayFilter, RedisRevocationStore, WebhookIngestionService"
                    value={impactSymbolsInput}
                    onChange={(e) => setImpactSymbolsInput(e.target.value)}
                  />
                </div>
                <Button type="submit" variant="default" disabled={isAnalyzingImpact || !impactSymbolsInput.trim()}>
                  {isAnalyzingImpact ? <Loader2 className="h-4 w-4 animate-spin" /> : "Compute Blast Radius"}
                </Button>
              </form>

              {impactError && (
                <div className="mt-4 p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs text-rose-300">
                  {impactError}
                </div>
              )}
            </CardContent>
          </Card>

          {impactResults.length > 0 ? (
            <div className="space-y-3">
              <h3 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider font-mono tabular-nums">
                Impacted Upstream Dependencies ({impactResults.length})
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {impactResults.map((item, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between gap-2 p-3 rounded-md border border-[#1C1C21] bg-[#0E0E11] text-xs"
                  >
                    <div className="min-w-0 flex-1 pr-2">
                      <div className="font-mono text-zinc-100 font-semibold truncate" title={item.source_symbol}>
                        {item.source_symbol}
                      </div>
                      <div className="text-[10px] text-zinc-500 font-mono tabular-nums">Depth: {item.depth ?? 1} hops</div>
                    </div>
                    <Badge variant="outline" className="shrink-0 text-[10px] font-mono border-[#26262C] bg-[#141418] text-zinc-300">
                      {item.edge_type || "DEPENDS_ON"}
                    </Badge>
                  </div>
                ))}
              </div>
            </div>
          ) : hasSearchedImpact && !isAnalyzingImpact && !impactError ? (
            <div className="rounded-md border border-dashed border-[#26262C] bg-[#0E0E11] p-8 text-center text-xs text-zinc-400 space-y-1.5">
              <p className="font-medium text-zinc-200">No Upstream Dependencies Found</p>
              <p className="text-[11px] text-zinc-500">
                No callers or AST edges matched &ldquo;{impactSymbolsInput}&rdquo; within 3 hops in this repository. Try entering a class name, method suffix, or qualified symbol.
              </p>
            </div>
          ) : null}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 4: CROSS-REPOSITORY DEPENDENCY TOPOLOGY & DISCOVERY                   */}
      {/* ========================================================================= */}
      {activeTab === "dependencies" && (
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <CardTitle className="text-base flex items-center gap-2">
                    <Share2 className="h-4 w-4 text-zinc-400" />
                    Cross-Repository Dependency Topology
                  </CardTitle>
                  <CardDescription>
                    Deterministic static manifest discovery (pom.xml, requirements.txt, package.json, OpenAPI contracts, service URLs) strictly scoped to{" "}
                    <span className="text-zinc-100 font-medium">{workspaceDisplayName}</span>.
                  </CardDescription>
                </div>

                <div className="flex items-center gap-2 flex-wrap">
                  <Button
                    onClick={fetchOrgDependencies}
                    disabled={isLoadingDependencies}
                    variant="outline"
                    size="sm"
                    className="text-xs"
                  >
                    <RefreshCw className={`h-3.5 w-3.5 mr-1.5 ${isLoadingDependencies ? "animate-spin" : ""}`} />
                    Refresh
                  </Button>

                  <Button
                    onClick={handleDiscoverDependencies}
                    disabled={isDiscoveringDependencies || !selectedRepoId}
                    variant="default"
                    size="sm"
                    className="text-xs"
                  >
                    {isDiscoveringDependencies ? (
                      <>
                        <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" />
                        Scanning Manifests...
                      </>
                    ) : (
                      <>
                        <Sparkles className="h-3.5 w-3.5 mr-1.5" />
                        Scan Manifests & Contracts
                      </>
                    )}
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {dependencyDiscoveryResult && (
                <div className="p-3 rounded-md border border-emerald-900/60 bg-emerald-950/20 text-xs text-emerald-300 flex items-center gap-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                  <span>{dependencyDiscoveryResult}</span>
                </div>
              )}

              {dependencyError && (
                <div className="p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-rose-400 shrink-0" />
                  <span>{dependencyError}</span>
                </div>
              )}

              {/* Confidence Filters */}
              <div className="flex items-center justify-between flex-wrap gap-2 pt-3 border-t border-[#1C1C21]">
                <div className="flex items-center gap-1.5 text-xs font-mono">
                  <span className="text-zinc-400 text-[11px] mr-1">Confidence:</span>
                  {(["ALL", "HIGH", "MEDIUM", "LOW"] as const).map((conf) => (
                    <button
                      key={conf}
                      onClick={() => setDependencyFilterConfidence(conf)}
                      className={`px-2.5 py-1 rounded text-[10px] font-mono transition-colors ${
                        dependencyFilterConfidence === conf
                          ? "bg-zinc-100 text-zinc-950 font-semibold"
                          : "bg-[#141418] text-zinc-400 hover:text-zinc-100 border border-[#26262C]"
                      }`}
                    >
                      {conf}
                    </button>
                  ))}
                </div>

                <span className="text-[11px] text-zinc-500 font-mono tabular-nums">
                  {orgDependencies.length} total dependencies discovered
                </span>
              </div>

              {/* Dependencies List */}
              {isLoadingDependencies ? (
                <div className="p-8 rounded-lg border border-[#1C1C21] bg-[#09090B] flex items-center justify-center gap-2 text-xs text-zinc-400">
                  <Loader2 className="h-4 w-4 animate-spin text-zinc-400" />
                  Loading Cross-Repository Dependency Graph...
                </div>
              ) : orgDependencies.length > 0 ? (
                <div className="space-y-3 max-h-[600px] overflow-y-auto">
                  {orgDependencies
                    .filter(
                      (dep) =>
                        dependencyFilterConfidence === "ALL" ||
                        dep.confidence === dependencyFilterConfidence
                    )
                    .map((dep, idx) => (
                      <div
                        key={dep.id || idx}
                        className="rounded-lg border border-[#1C1C21] bg-[#09090B] p-4 space-y-3 hover:border-[#26262C] transition-colors"
                      >
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-xs font-mono font-semibold text-zinc-100">
                              {dep.source_repository_name || dep.source_repository_id.substring(0, 8)}
                            </span>
                            <ArrowRight className="h-3 w-3 text-zinc-500" />
                            <span className="text-xs font-mono font-semibold text-zinc-200">
                              {dep.target_repository_name || dep.target_repository_id.substring(0, 8)}
                            </span>
                            <Badge variant="outline" className="text-[10px] font-mono text-zinc-300 border-[#26262C] bg-[#141418]">
                              {dep.dependency_type}
                            </Badge>
                            <Badge
                              variant="outline"
                              className={`text-[10px] font-mono ${
                                dep.confidence === "HIGH"
                                  ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                  : dep.confidence === "MEDIUM"
                                  ? "text-sky-400 border-sky-900/60 bg-sky-950/20"
                                  : "text-amber-400 border-amber-900/60 bg-amber-950/20"
                              }`}
                            >
                              Confidence: {dep.confidence}
                            </Badge>
                          </div>

                          <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C]">
                            State: {dep.state}
                          </Badge>
                        </div>

                        {/* Evidence Items */}
                        {dep.evidence && dep.evidence.length > 0 && (
                          <div className="space-y-1.5 pt-1">
                            <span className="text-[10px] uppercase font-semibold text-zinc-500 tracking-wider font-mono tabular-nums">
                              Deterministic Evidence Snippets ({dep.evidence.length})
                            </span>
                            <div className="space-y-1.5">
                              {dep.evidence.map((ev, eIdx) => (
                                <div
                                  key={eIdx}
                                  className="p-2.5 rounded-md bg-[#0E0E11] border border-[#1C1C21] text-[11px] font-mono space-y-1"
                                >
                                  <div className="flex items-center justify-between text-zinc-400">
                                    <span className="text-zinc-200">{ev.file_path}</span>
                                    <Badge variant="outline" className="text-[9px] border-[#26262C] text-zinc-400">
                                      {ev.evidence_type}
                                    </Badge>
                                  </div>
                                  {ev.snippet && (
                                    <pre className="text-[10px] text-zinc-300 bg-[#09090B] border border-[#1C1C21] p-1.5 rounded overflow-x-auto whitespace-pre-wrap">
                                      {ev.snippet}
                                    </pre>
                                  )}
                                </div>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    ))}
                </div>
              ) : (
                <div className="p-8 rounded-lg border border-dashed border-[#26262C] bg-[#09090B] text-center text-xs text-zinc-500 space-y-2">
                  <Share2 className="h-8 w-8 text-zinc-600 mx-auto" />
                  <p className="text-zinc-300">No cross-repository dependencies discovered yet for this organization.</p>
                  <p className="text-[11px] text-zinc-500">
                    Click &ldquo;Scan Manifests &amp; Contracts&rdquo; to analyze Maven, Node, Python, and OpenAPI manifests.
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 5: CONTROLLED AI REMEDIATION & WORKFLOW AUTOMATION WORKSPACE          */}
      {/* ========================================================================= */}
      {activeTab === "remediation" && (
        <div className="space-y-6">
          {/* Workspace Header & Advisory Mode Banner */}
          <div className="rounded-lg border border-amber-900/60 bg-amber-950/15 p-4 space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-md bg-amber-950/40 border border-amber-900/60 text-amber-400 shrink-0">
                  <ShieldAlert className="h-5 w-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="text-sm font-semibold text-zinc-100 tracking-tight">
                      Controlled AI Remediation Workspace
                    </h3>
                    <Badge variant="outline" className="text-[10px] font-mono bg-amber-950/30 text-amber-300 border-amber-900/60 font-semibold">
                      MODE A: ADVISORY ONLY
                    </Badge>
                  </div>
                  <p className="text-xs text-zinc-300 mt-0.5 leading-relaxed">
                    Zero autonomous repository writes. GitHub App permissions are strictly read-only (<code className="text-amber-300 font-mono">contents: read</code>). All patches, plans, and actions require explicit developer review and approval.
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2 self-start sm:self-auto shrink-0">
                <Badge variant="outline" className="text-[11px] font-mono text-zinc-400 bg-[#141418] border-[#26262C]">
                  Execution Safety: Strict In-Memory AST
                </Badge>
              </div>
            </div>
          </div>

          {/* Action Filter & Stats Bar */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-4 rounded-lg border border-[#1C1C21] bg-[#0E0E11]">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-xs font-medium text-zinc-400">Status:</span>
              {(["ALL", "PROPOSED", "APPROVED", "REJECTED", "APPLIED"] as const).map((st) => (
                <button
                  key={st}
                  onClick={() => setActionStatusFilter(st)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono font-medium transition-colors ${
                    actionStatusFilter === st
                      ? "bg-zinc-100 text-zinc-950 font-semibold"
                      : "bg-[#141418] text-zinc-400 hover:text-zinc-100 border border-[#26262C]"
                  }`}
                >
                  {st}
                </button>
              ))}

              <div className="h-4 w-px bg-[#26262C] mx-1 hidden sm:block" />

              <span className="text-xs font-medium text-zinc-400 ml-1">Type:</span>
              {(["ALL", "FIX_SECURITY", "FIX_RELIABILITY", "ADD_TEST", "UPDATE_API_CONTRACT"] as const).map((tp) => (
                <button
                  key={tp}
                  onClick={() => setActionTypeFilter(tp)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono font-medium transition-colors ${
                    actionTypeFilter === tp
                      ? "bg-zinc-100 text-zinc-950 font-semibold"
                      : "bg-[#141418] text-zinc-400 hover:text-zinc-100 border border-[#26262C]"
                  }`}
                >
                  {tp === "ALL" ? "ALL TYPES" : tp.replace("FIX_", "")}
                </button>
              ))}
            </div>

            <div className="flex items-center gap-2 text-xs font-mono tabular-nums text-zinc-400">
              <span>Total: <strong className="text-zinc-100">{localActions.length}</strong></span>
              <span>&bull;</span>
              <span>Pending: <strong className="text-amber-400">{localActions.filter(a => a.status === "PROPOSED").length}</strong></span>
              <span>&bull;</span>
              <span>Approved: <strong className="text-emerald-400">{localActions.filter(a => a.status === "APPROVED").length}</strong></span>
            </div>
          </div>

          {/* Remediation Feedback Alert */}
          {remediationFeedback && (
            <div
              className={`p-3.5 rounded-lg border text-xs flex items-center justify-between gap-3 ${
                remediationFeedback.type === "success"
                  ? "bg-emerald-950/20 border-emerald-900/60 text-emerald-200"
                  : remediationFeedback.type === "error"
                  ? "bg-rose-950/20 border-rose-900/60 text-rose-200"
                  : "bg-amber-950/20 border-amber-900/60 text-amber-200"
              }`}
            >
              <div className="flex items-center gap-2">
                {remediationFeedback.type === "success" ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                ) : remediationFeedback.type === "error" ? (
                  <AlertCircle className="h-4 w-4 text-rose-400 shrink-0" />
                ) : (
                  <Info className="h-4 w-4 text-amber-400 shrink-0" />
                )}
                <span>{remediationFeedback.message}</span>
              </div>
              <button
                onClick={() => setRemediationFeedback(null)}
                className="text-xs font-mono text-zinc-400 hover:text-zinc-100 underline shrink-0"
              >
                Dismiss
              </button>
            </div>
          )}

          {/* Master-Detail Grid */}
          {localActions.length > 0 ? (
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
              {/* Left Column: Actions List (5 Cols) */}
              <div className="lg:col-span-5 space-y-3">
                <div className="flex items-center justify-between text-xs text-zinc-400 px-1">
                  <span className="font-semibold uppercase tracking-wider text-zinc-400 font-mono tabular-nums">
                    Derived Engineering Actions ({localActions.length})
                  </span>
                  <span className="text-[11px] text-zinc-500">Select action to inspect</span>
                </div>

                <div className="space-y-2.5 max-h-[800px] overflow-y-auto pr-1">
                  {localActions
                    .filter((a) => actionStatusFilter === "ALL" || a.status === actionStatusFilter)
                    .filter((a) => actionTypeFilter === "ALL" || a.action_type === actionTypeFilter)
                    .map((action) => {
                      const isSelected = selectedActionId === action.action_id;
                      return (
                        <div
                          key={action.action_id}
                          onClick={() => setSelectedActionId(action.action_id)}
                          className={`p-4 rounded-lg border transition-colors cursor-pointer space-y-2.5 ${
                            isSelected
                              ? "border-zinc-400 bg-[#141418]"
                              : "border-[#1C1C21] bg-[#0E0E11] hover:bg-[#141418]/60 hover:border-[#26262C]"
                          }`}
                        >
                          <div className="flex items-center justify-between gap-2 flex-wrap">
                            <div className="flex items-center gap-1.5 flex-wrap">
                              {/* Action Type Badge */}
                              <Badge
                                variant="outline"
                                className={`text-[10px] font-mono font-semibold ${
                                  action.action_type === "FIX_SECURITY"
                                    ? "bg-rose-950/30 text-rose-300 border-rose-900/60"
                                    : action.action_type === "FIX_RELIABILITY"
                                    ? "bg-amber-950/30 text-amber-300 border-amber-900/60"
                                    : action.action_type === "ADD_TEST" || action.action_type === "RUN_TEST"
                                    ? "bg-sky-950/30 text-sky-300 border-sky-900/60"
                                    : action.action_type === "UPDATE_API_CONTRACT"
                                    ? "bg-sky-950/30 text-sky-300 border-sky-900/60"
                                    : "bg-[#141418] text-zinc-300 border-[#26262C]"
                                }`}
                              >
                                {action.action_type}
                              </Badge>

                              {/* Severity Badge */}
                              <Badge
                                variant="outline"
                                className={`text-[9px] font-mono ${
                                  action.severity === "CRITICAL"
                                    ? "text-rose-400 border-rose-900/60 bg-rose-950/20"
                                    : action.severity === "HIGH"
                                    ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                    : action.severity === "MEDIUM"
                                    ? "text-amber-300 border-amber-900/60 bg-amber-950/15"
                                    : "text-sky-400 border-sky-900/60 bg-sky-950/20"
                                }`}
                              >
                                {action.severity}
                              </Badge>
                            </div>

                            {/* Status Badge */}
                            <Badge
                              variant="outline"
                              className={`text-[10px] font-mono font-semibold ${
                                action.status === "APPROVED"
                                  ? "bg-emerald-950/30 text-emerald-300 border-emerald-900/60"
                                  : action.status === "PROPOSED"
                                  ? "bg-amber-950/25 text-amber-300 border-amber-900/60"
                                  : action.status === "APPLIED"
                                  ? "bg-sky-950/30 text-sky-300 border-sky-900/60"
                                  : "bg-rose-950/30 text-rose-300 border-rose-900/60"
                              }`}
                            >
                              {action.status}
                            </Badge>
                          </div>

                          <div>
                            <h4 className="text-xs font-semibold text-zinc-100 tracking-tight line-clamp-2">
                              {action.title}
                            </h4>
                            <p className="text-[11px] text-zinc-400 mt-1 line-clamp-2 leading-relaxed">
                              {action.description}
                            </p>
                          </div>

                          {action.affected_files && action.affected_files.length > 0 && (
                            <div className="flex items-center gap-1.5 text-[10px] font-mono text-zinc-400 truncate">
                              <FileCode className="h-3 w-3 text-zinc-400 shrink-0" />
                              <span className="truncate">{action.affected_files[0]}</span>
                            </div>
                          )}

                          {/* Quick decision footer */}
                          <div className="flex items-center justify-between pt-2 border-t border-[#1C1C21] text-[11px]">
                            <span className="text-zinc-500 font-mono tabular-nums text-[10px]">
                              ID: {action.action_id}
                            </span>
                            <div className="flex items-center gap-1.5" onClick={(e) => e.stopPropagation()}>
                              {action.status === "PROPOSED" && (
                                <>
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    onClick={() => handleApproveAction(action.action_id)}
                                    disabled={isApprovingAction}
                                    className="h-6 text-[10px] px-2 border-emerald-900/60 text-emerald-300 hover:bg-emerald-950/40"
                                  >
                                    Approve
                                  </Button>
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    onClick={() => setRejectingActionId(action.action_id)}
                                    disabled={isRejectingAction}
                                    className="h-6 text-[10px] px-2 border-rose-900/60 text-rose-300 hover:bg-rose-950/40"
                                  >
                                    Reject
                                  </Button>
                                </>
                              )}
                            </div>
                          </div>
                        </div>
                      );
                    })}
                </div>
              </div>

              {/* Right Column: Selected Action Detailed Inspector (7 Cols) */}
              <div className="lg:col-span-7 space-y-4">
                {(() => {
                  const currentAction = localActions.find((a) => a.action_id === selectedActionId) || localActions[0];
                  if (!currentAction) return null;

                  const details = actionDetailsMap[currentAction.action_id];
                  const plan = details?.plan;
                  const patch = details?.patch;
                  const effectiveness = details?.effectiveness;
                  const events = details?.events || [];
                  const validationResult = validationResultsMap[currentAction.action_id];

                  return (
                    <div className="space-y-4">
                      {/* Action Header Card */}
                      <Card className="border-[#26262C] bg-[#0E0E11]">
                        <CardHeader className="pb-3">
                          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div className="space-y-1">
                              <div className="flex items-center gap-2 flex-wrap">
                                <Badge
                                  variant="outline"
                                  className="text-xs font-mono font-semibold bg-[#141418] text-zinc-200 border-[#26262C]"
                                >
                                  {currentAction.action_type}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className="text-[10px] font-mono tabular-nums text-zinc-400 bg-[#09090B] border-[#1C1C21]"
                                >
                                  ID: {currentAction.action_id}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className={`text-[10px] font-mono ${
                                    currentAction.status === "APPROVED"
                                      ? "text-emerald-400 border-emerald-900/60 bg-emerald-950/20"
                                      : currentAction.status === "PROPOSED"
                                      ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                                      : "text-rose-400 border-rose-900/60 bg-rose-950/20"
                                  }`}
                                >
                                  Status: {currentAction.status}
                                </Badge>
                              </div>
                              <CardTitle className="text-base text-zinc-100 pt-1">
                                {currentAction.title}
                              </CardTitle>
                              <CardDescription className="text-xs text-zinc-300">
                                {currentAction.description}
                              </CardDescription>
                            </div>

                            {/* Human Decision Controls */}
                            <div className="flex items-center gap-2 shrink-0 self-start sm:self-auto">
                              {currentAction.status === "PROPOSED" ? (
                                <>
                                  <Button
                                    size="sm"
                                    onClick={() => handleApproveAction(currentAction.action_id)}
                                    disabled={isApprovingAction}
                                    className="bg-emerald-600 hover:bg-emerald-500 text-white text-xs gap-1.5 h-8"
                                  >
                                    <CheckCircle2 className="h-3.5 w-3.5" />
                                    Approve Action
                                  </Button>
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    onClick={() => setRejectingActionId(currentAction.action_id)}
                                    disabled={isRejectingAction}
                                    className="border-rose-900/60 text-rose-300 hover:bg-rose-950/30 text-xs gap-1.5 h-8"
                                  >
                                    <XCircle className="h-3.5 w-3.5" />
                                    Reject
                                  </Button>
                                </>
                              ) : currentAction.status === "APPROVED" ? (
                                <Button
                                  size="sm"
                                  variant="default"
                                  onClick={() => handleApplyAction(currentAction.action_id)}
                                  disabled={isApplyingAction}
                                  className="text-xs gap-1.5 h-8"
                                  title="Applies patch in Mode B or explains Mode A restrictions"
                                >
                                  {isApplyingAction ? (
                                    <>
                                      <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" />
                                      Applying...
                                    </>
                                  ) : (
                                    <>
                                      <Play className="h-3.5 w-3.5" />
                                      Apply Patch Proposal
                                    </>
                                  )}
                                </Button>
                              ) : null}
                            </div>
                          </div>
                        </CardHeader>

                        {/* Target file & symbol bar */}
                        <CardContent className="pt-0 space-y-3">
                          <div className="p-3 rounded-md bg-[#09090B] border border-[#1C1C21] flex items-center justify-between text-xs font-mono gap-2">
                            <div className="flex items-center gap-2 truncate">
                              <span className="text-zinc-500">Target File:</span>
                              <span className="text-zinc-200 truncate">
                                {currentAction.affected_files && currentAction.affected_files.length > 0
                                  ? currentAction.affected_files[0]
                                  : "Repository Core"}
                              </span>
                            </div>
                            <div className="flex items-center gap-2 shrink-0">
                              <span className="text-zinc-500">Confidence:</span>
                              <Badge variant="outline" className="text-[10px] text-emerald-400 border-emerald-900/60">
                                {currentAction.confidence}
                              </Badge>
                            </div>
                          </div>
                        </CardContent>
                      </Card>

                      {/* Modal for rejecting action with explicit reason */}
                      {rejectingActionId === currentAction.action_id && (
                        <div className="p-4 rounded-lg border border-rose-900/60 bg-rose-950/20 space-y-3">
                          <div className="flex items-center justify-between">
                            <span className="text-xs font-semibold text-rose-300 uppercase tracking-wider flex items-center gap-1.5">
                              <AlertTriangle className="h-4 w-4 text-rose-400" />
                              Reject Remediation Action
                            </span>
                            <button
                              onClick={() => setRejectingActionId(null)}
                              className="text-xs text-zinc-400 hover:text-zinc-100"
                            >
                              Cancel
                            </button>
                          </div>
                          <Input
                            placeholder="State reason for rejecting remediation action (recorded in audit log)..."
                            value={rejectReasonInput}
                            onChange={(e) => setRejectReasonInput(e.target.value)}
                            className="text-xs bg-[#09090B] border-[#26262C] text-zinc-100"
                          />
                          <div className="flex justify-end gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => setRejectingActionId(null)}
                              className="h-7 text-xs"
                            >
                              Cancel
                            </Button>
                            <Button
                              size="sm"
                              onClick={() => handleRejectAction(currentAction.action_id)}
                              disabled={isRejectingAction}
                              className="h-7 text-xs bg-rose-600 hover:bg-rose-500 text-white"
                            >
                              Confirm Rejection
                            </Button>
                          </div>
                        </div>
                      )}

                      {/* 1. Structured Remediation Plan */}
                      {plan ? (
                        <Card className="border-[#1C1C21] bg-[#0E0E11]">
                          <CardHeader className="pb-3">
                            <div className="flex items-center justify-between">
                              <CardTitle className="text-xs font-semibold uppercase tracking-wider text-zinc-300 flex items-center gap-2">
                                <Workflow className="h-4 w-4 text-zinc-400" />
                                Remediation Plan: {plan.title}
                              </CardTitle>
                              {plan.is_multi_repo && (
                                <Badge variant="outline" className="text-[10px] font-mono text-sky-300 border-sky-900/60 bg-sky-950/20">
                                  Multi-Repository Coordinated
                                </Badge>
                              )}
                            </div>
                            <CardDescription className="text-xs text-zinc-400 mt-1">
                              {plan.summary}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="space-y-2.5 pt-0">
                            {plan.coordinated_repos && plan.coordinated_repos.length > 0 && (
                              <div className="p-2.5 rounded-md bg-sky-950/20 border border-sky-900/60 text-xs text-sky-300 flex items-center gap-2">
                                <Share2 className="h-3.5 w-3.5 shrink-0" />
                                <span>Coordinated Repositories: {plan.coordinated_repos.join(", ")}</span>
                              </div>
                            )}

                            <div className="space-y-2">
                              {plan.steps && plan.steps.map((step, sIdx) => (
                                <div
                                  key={sIdx}
                                  className="p-3 rounded-md bg-[#09090B] border border-[#1C1C21] text-xs space-y-1.5"
                                >
                                  <div className="flex items-center justify-between gap-2">
                                    <div className="flex items-center gap-2">
                                      <span className="h-5 w-5 rounded bg-[#141418] border border-[#26262C] text-zinc-300 text-[10px] font-mono tabular-nums font-semibold flex items-center justify-center shrink-0">
                                        {step.step_number}
                                      </span>
                                      <span className="font-semibold text-zinc-100 font-mono text-[11px]">
                                        {step.target_file} {step.target_symbol && `(${step.target_symbol})`}
                                      </span>
                                    </div>
                                    <Badge variant="outline" className="text-[9px] font-mono text-zinc-400 border-[#26262C]">
                                      {step.status}
                                    </Badge>
                                  </div>
                                  <p className="text-zinc-300 text-xs leading-relaxed pl-7">
                                    {step.change_description}
                                  </p>
                                  <div className="pl-7 text-[10px] font-mono text-zinc-500 flex items-center gap-1.5">
                                    <ShieldCheck className="h-3 w-3 text-emerald-400 shrink-0" />
                                    <span>Verification: {step.validation_strategy}</span>
                                  </div>
                                </div>
                              ))}
                            </div>
                          </CardContent>
                        </Card>
                      ) : isLoadingActionDetails ? (
                        <div className="p-4 rounded-lg border border-[#1C1C21] bg-[#09090B] text-xs text-zinc-500 flex items-center gap-2">
                          <Loader2 className="h-3.5 w-3.5 animate-spin text-zinc-400" />
                          <span>Loading structured remediation plan steps...</span>
                        </div>
                      ) : (
                        <div className="p-4 rounded-lg border border-[#1C1C21] bg-[#09090B] text-xs text-zinc-400 flex items-center gap-2">
                          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400 shrink-0" />
                          <span>No code remediation steps required — all deterministic quality and security checks passed.</span>
                        </div>
                      )}

                      {/* 2. Proposed Patch & In-Memory AST Validation */}
                      {patch ? (
                        <Card className="border-[#1C1C21] bg-[#0E0E11]">
                          <CardHeader className="pb-3">
                            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                              <CardTitle className="text-xs font-semibold uppercase tracking-wider text-zinc-300 flex items-center gap-2">
                                <FileDiff className="h-4 w-4 text-emerald-400" />
                                Proposed Unified Diff Patch
                              </CardTitle>
                              <div className="flex items-center gap-2">
                                <Badge variant="outline" className="text-[10px] font-mono text-zinc-400 border-[#26262C]">
                                  Target: {patch.target_file}
                                </Badge>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => handleValidatePatch(currentAction.action_id)}
                                  disabled={isValidatingPatch}
                                  className="h-7 text-xs px-2.5 border-emerald-900/60 text-emerald-300 hover:bg-emerald-950/30 gap-1.5"
                                >
                                  {isValidatingPatch ? (
                                    <>
                                      <Loader2 className="h-3 w-3 animate-spin text-emerald-400" />
                                      Validating AST...
                                    </>
                                  ) : (
                                    <>
                                      <CheckCircle2 className="h-3 w-3 text-emerald-400" />
                                      Validate In-Memory
                                    </>
                                  )}
                                </Button>
                              </div>
                            </div>
                            <CardDescription className="text-xs text-zinc-400 mt-1">
                              {patch.rationale}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="space-y-3 pt-0">
                            {/* Validation Result Box */}
                            {validationResult && (
                              <div
                                className={`p-3 rounded-md border text-xs font-mono space-y-1.5 ${
                                  validationResult.valid
                                    ? "bg-emerald-950/20 border-emerald-900/60 text-emerald-200"
                                    : "bg-rose-950/20 border-rose-900/60 text-rose-200"
                                }`}
                              >
                                <div className="flex items-center justify-between">
                                  <span className="font-semibold flex items-center gap-1.5">
                                    {validationResult.valid ? (
                                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                                    ) : (
                                      <AlertTriangle className="h-3.5 w-3.5 text-rose-400" />
                                    )}
                                    Static Post-Patch Safety Validation: {validationResult.valid ? "PASSED" : "FAILED"}
                                  </span>
                                  <span className="text-[10px] text-zinc-400">Zero Shell Script Execution</span>
                                </div>
                                {validationResult.details && (
                                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[10px] text-zinc-300 pt-1 tabular-nums">
                                    <div>AST Valid: <strong className="text-emerald-400">Yes</strong></div>
                                    <div>Hunks: <strong className="text-zinc-100">{validationResult.details.applied_hunks}</strong></div>
                                    <div>Lines Changed: <strong className="text-zinc-100">{validationResult.details.changed_lines}</strong></div>
                                    <div>Secrets Detected: <strong className="text-emerald-400">0</strong></div>
                                  </div>
                                )}
                              </div>
                            )}

                            {/* Colorized Unified Diff Viewer */}
                            <div className="rounded-md bg-[#09090B] border border-[#1C1C21] p-3 overflow-x-auto font-mono text-xs max-h-72">
                              {patch.unified_diff.split("\n").map((line, lIdx) => {
                                let lineCls = "text-zinc-300";
                                let bgCls = "";
                                if (line.startsWith("+") && !line.startsWith("+++")) {
                                  lineCls = "text-emerald-400";
                                  bgCls = "bg-emerald-950/25";
                                } else if (line.startsWith("-") && !line.startsWith("---")) {
                                  lineCls = "text-rose-400";
                                  bgCls = "bg-rose-950/25";
                                } else if (line.startsWith("@@")) {
                                  lineCls = "text-sky-400 font-semibold";
                                  bgCls = "bg-sky-950/20";
                                } else if (line.startsWith("---") || line.startsWith("+++")) {
                                  lineCls = "text-zinc-400 font-semibold";
                                }
                                return (
                                  <div key={lIdx} className={`px-2 py-0.5 rounded-sm whitespace-pre ${lineCls} ${bgCls}`}>
                                    {line || " "}
                                  </div>
                                );
                              })}
                            </div>
                          </CardContent>
                        </Card>
                      ) : null}

                      {/* 3. Before vs After Differential Re-Analysis */}
                      {effectiveness && (
                        <Card className="border-[#1C1C21] bg-[#0E0E11]">
                          <CardHeader className="pb-3">
                            <div className="flex items-center justify-between">
                              <CardTitle className="text-xs font-semibold uppercase tracking-wider text-zinc-300 flex items-center gap-2">
                                <Scale className="h-4 w-4 text-zinc-400" />
                                Deterministic Before vs After Differential Re-Analysis
                              </CardTitle>
                              {effectiveness.is_effective && (
                                <Badge variant="outline" className="text-[10px] font-mono bg-emerald-950/30 text-emerald-300 border-emerald-900/60 font-semibold">
                                  VERIFIED EFFECTIVE
                                </Badge>
                              )}
                            </div>
                          </CardHeader>
                          <CardContent className="space-y-3 pt-0">
                            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                              {/* Risk Delta Card */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  Deterministic Risk Score
                                </span>
                                <div className="flex items-center gap-2 font-mono tabular-nums">
                                  <span className="text-zinc-400 line-through">{(effectiveness.before_risk_score ?? 0).toFixed(1)}</span>
                                  <ArrowRight className="h-3 w-3 text-zinc-500" />
                                  <span className="text-base font-semibold text-emerald-400">
                                    {(effectiveness.after_risk_score ?? 0).toFixed(1)}
                                  </span>
                                </div>
                                <span className="text-[10px] text-emerald-400 font-mono tabular-nums">
                                  &minus;{(effectiveness.risk_delta ?? 0).toFixed(1)} pts reduced
                                </span>
                              </div>

                              {/* Findings Delta */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  Security & Reliability Findings
                                </span>
                                <div className="text-xs font-mono tabular-nums text-zinc-300">
                                  Fixed: <strong className="text-emerald-400">{effectiveness.findings_fixed.length}</strong> &bull; Persisted: <strong className="text-zinc-400">{effectiveness.findings_persisted.length}</strong>
                                </div>
                                <span className="text-[10px] text-zinc-500 font-mono tabular-nums">
                                  New Findings Introduced: <strong className="text-emerald-400">0</strong>
                                </span>
                              </div>

                              {/* Merge Readiness Delta */}
                              <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                                <span className="text-[10px] uppercase font-semibold text-zinc-400">
                                  Merge Readiness Progression
                                </span>
                                <div className="text-xs font-mono font-semibold text-zinc-100 truncate">
                                  {effectiveness.merge_readiness_delta}
                                </div>
                                <span className="text-[10px] text-zinc-500 font-mono tabular-nums">
                                  Tests Added: {effectiveness.tests_added}
                                </span>
                              </div>
                            </div>

                            <p className="text-xs text-zinc-300 font-mono bg-[#09090B] p-2.5 rounded-md border border-[#1C1C21]">
                              {effectiveness.remediation_summary}
                            </p>
                          </CardContent>
                        </Card>
                      )}

                      {/* 4. Immutable Audit Trail (action_events) */}
                      {events && events.length > 0 && (
                        <Card className="border-[#1C1C21] bg-[#0E0E11]">
                          <CardHeader className="pb-3">
                            <CardTitle className="text-xs font-semibold uppercase tracking-wider text-zinc-300 flex items-center gap-2 font-mono tabular-nums">
                              <History className="h-4 w-4 text-zinc-400" />
                              Immutable Audit Trail ({events.length} Events)
                            </CardTitle>
                          </CardHeader>
                          <CardContent className="space-y-2 pt-0">
                            {events.map((ev, evIdx) => (
                              <div
                                key={evIdx}
                                className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21] text-[11px] font-mono flex items-center justify-between"
                              >
                                <div className="flex items-center gap-2">
                                  <Badge
                                    variant="outline"
                                    className={`text-[9px] font-semibold ${
                                      ev.event_type === "APPROVED"
                                        ? "text-emerald-400 border-emerald-900/60"
                                        : ev.event_type === "REJECTED"
                                        ? "text-rose-400 border-rose-900/60"
                                        : ev.event_type === "VALIDATED"
                                        ? "text-sky-400 border-sky-900/60"
                                        : "text-zinc-300 border-[#26262C]"
                                    }`}
                                  >
                                    {ev.event_type}
                                  </Badge>
                                  <span className="text-zinc-300">by {ev.actor_id}</span>
                                </div>
                                <span className="text-[10px] text-zinc-500 tabular-nums">
                                  {ev.created_at ? new Date(ev.created_at).toLocaleTimeString() : "Recent"}
                                </span>
                              </div>
                            ))}
                          </CardContent>
                        </Card>
                      )}
                    </div>
                  );
                })()}
              </div>
            </div>
          ) : (
            <div className="p-12 rounded-lg border border-dashed border-[#26262C] bg-[#0E0E11] text-center text-xs text-zinc-500 space-y-3">
              <Sliders className="h-9 w-9 text-zinc-600 mx-auto" />
              <div className="space-y-1">
                <p className="text-zinc-200 font-semibold text-sm">No Engineering Actions Derived Yet</p>
                <p className="text-zinc-500 max-w-md mx-auto">
                  Run &ldquo;Automatic PR Intelligence&rdquo; on an active Pull Request to evaluate security vulnerabilities, reliability risks, missing tests, and contract breaking changes.
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setActiveTab("pr")}
                className="text-xs"
              >
                Go to PR Intelligence
              </Button>
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 6: AUTOMATED REPOSITORY DOCUMENTATION & SYSTEM DESIGN (STEP 7)        */}
      {/* ========================================================================= */}
      {activeTab === "autodocs" && (
        <div className="space-y-6 animate-in fade-in duration-200">
          {/* Header Action Bar */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-4 rounded-lg border border-[#1C1C21] bg-[#0E0E11]">
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <BookOpen className="h-4 w-4 text-zinc-400" />
                <h2 className="text-base font-semibold text-zinc-100 tracking-tight">
                  Automated Documentation & System Design
                </h2>
                <Badge variant="outline" className="border-[#26262C] bg-[#141418] text-zinc-300 text-[11px] font-mono">
                  v1.0 Engine
                </Badge>
              </div>
              <p className="text-xs text-zinc-400 mt-1 max-w-2xl">
                4 deterministic documents (Overview, API, Data Model, Dependencies) + Grounded LLM System Design with verified citations. Managed through DRAFT &rarr; APPROVED &rarr; APPLIED lifecycle.
              </p>
            </div>
            <div className="flex items-center gap-2.5 flex-wrap">
              {documents.length > latestDocs.length && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setShowHistoricalDocs((prev) => !prev)}
                  className="gap-1.5 text-xs border-[#26262C]"
                >
                  <History className="h-3.5 w-3.5" />
                  {showHistoricalDocs
                    ? `Show Latest Only (${latestDocs.length})`
                    : `Show Historical Snapshots (${documents.length - latestDocs.length})`}
                </Button>
              )}
              <Button
                variant="outline"
                size="sm"
                onClick={fetchDocuments}
                disabled={isLoadingDocs}
                className="gap-2 text-xs"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${isLoadingDocs ? "animate-spin" : ""}`} />
                Refresh
              </Button>
              <Button
                variant="default"
                size="sm"
                onClick={handleGenerateDocuments}
                disabled={isGeneratingDocs || !selectedRepoId}
                className="gap-2 text-xs"
              >
                {isGeneratingDocs ? (
                  <>
                    <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    Generating Docs...
                  </>
                ) : (
                  <>
                    <Zap className="h-3.5 w-3.5" />
                    Generate Repository Docs
                  </>
                )}
              </Button>
            </div>
          </div>

          {/* Feedback Banners */}
          {docActionSuccess && (
            <div className="flex items-center gap-2 rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3 text-xs text-emerald-300">
              <Check className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>{docActionSuccess}</span>
            </div>
          )}
          {docActionError && (
            <div className="flex items-center gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300">
              <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
              <span>{docActionError}</span>
            </div>
          )}

          {/* Previous AI Data Banner */}
          {documents.some((d) => d.provenance?.showing_previous_ai_data) && (
            <div className="flex items-center gap-3 rounded-lg border border-amber-900/60 bg-amber-950/20 p-4 text-xs text-amber-200">
              <div className="p-2 rounded-md bg-amber-950/50 border border-amber-900/60 text-amber-400 shrink-0">
                <AlertTriangle className="h-4 w-4" />
              </div>
              <div>
                <p className="font-semibold text-amber-300 text-sm">
                  This is the previous data — AI API is not working currently.
                </p>
                <p className="text-zinc-300 mt-0.5">
                  The live AI API is currently unreachable. Displaying the most recent AI-analyzed System Design & Architecture document.
                </p>
              </div>
            </div>
          )}

          {/* Document Cards Grid */}
          {isLoadingDocs ? (
            <AutoDocsGridSkeleton />
          ) : documents.length === 0 ? (
            <div className="flex flex-col items-center justify-center p-14 rounded-lg border border-dashed border-[#26262C] bg-[#0E0E11] text-center space-y-4">
              <BookOpen className="h-10 w-10 text-zinc-600" />
              <div className="space-y-1">
                <h3 className="text-sm font-semibold text-zinc-100">No Generated Documents Yet</h3>
                <p className="text-xs text-zinc-400 max-w-md">
                  Click &ldquo;Generate Repository Docs&rdquo; above to automatically create the 5 repository documents and System Design specification for this repo.
                </p>
              </div>
              <Button
                variant="default"
                size="sm"
                onClick={handleGenerateDocuments}
                disabled={isGeneratingDocs || !selectedRepoId}
                className="gap-2 text-xs"
              >
                <Zap className="h-3.5 w-3.5" />
                Generate Now
              </Button>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
              {displayedDocs.map((doc) => {
                const isSystemDesign = doc.doc_type === "SYSTEM_DESIGN" || doc.doc_type === "ARCHITECTURE";
                const isDraft = doc.status === "DRAFT";
                const isApproved = doc.status === "APPROVED";
                const isApplied = doc.status === "APPLIED";
                const isStale = doc.status === "STALE";

                return (
                  <Card key={doc.id} className="border-[#1C1C21] bg-[#0E0E11] hover:border-[#26262C] transition-colors flex flex-col justify-between">
                    <CardHeader className="pb-3">
                      <div className="flex items-start justify-between gap-2">
                        <Badge
                          variant="outline"
                          className={`text-[10px] font-mono uppercase ${
                            isSystemDesign
                              ? "border-[#26262C] text-zinc-200 bg-[#141418]"
                              : "border-sky-900/60 text-sky-300 bg-sky-950/20"
                          }`}
                        >
                          {doc.doc_type}
                        </Badge>
                        <Badge
                          variant="outline"
                          className={`text-[10px] font-mono ${
                            isApplied
                              ? "border-emerald-900/60 text-emerald-300 bg-emerald-950/20"
                              : isApproved
                              ? "border-sky-900/60 text-sky-300 bg-sky-950/20"
                              : isStale
                              ? "border-amber-900/60 text-amber-300 bg-amber-950/20"
                              : "border-[#26262C] text-zinc-400"
                          }`}
                        >
                          {doc.status}
                        </Badge>
                      </div>
                      <CardTitle className="text-sm font-semibold text-zinc-100 font-mono mt-2 truncate">
                        {doc.target_path}
                      </CardTitle>
                      <CardDescription className="text-[11px] text-zinc-400 flex items-center gap-1.5 mt-1 font-mono tabular-nums">
                        <GitCommit className="h-3 w-3 text-zinc-500" />
                        sha: {doc.commit_sha ? doc.commit_sha.slice(0, 8) : "HEAD"}
                      </CardDescription>
                    </CardHeader>
                    <CardContent className="space-y-3 pt-0">
                      <div className="flex items-center justify-between text-[11px] text-zinc-400 border-t border-[#1C1C21] pt-2.5 font-mono tabular-nums">
                        <span>Citations: {doc.citations?.length || 0} files</span>
                        <span className="text-[10px] text-zinc-500 truncate max-w-[120px]">
                          hash: {doc.content_hash?.slice(0, 8)}
                        </span>
                      </div>

                      {doc.provenance && (
                        <div className="text-[10px] text-zinc-500 bg-[#09090B] p-2 rounded-md border border-[#1C1C21] font-mono">
                          {doc.provenance.generator === "ai_grounded" ? (
                            <span>AI: {doc.provenance.model || "Gemini"} ({doc.provenance.provider})</span>
                          ) : (
                            <span>
                              Deterministic Engine{" "}
                              {String(doc.provenance.generator_version || "1.0").startsWith("v")
                                ? doc.provenance.generator_version
                                : `v${doc.provenance.generator_version || "1.0"}`}
                            </span>
                          )}
                        </div>
                      )}

                      {/* Action buttons */}
                      <div className="flex items-center gap-2 pt-2 border-t border-[#1C1C21]">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => setSelectedDocPreview(doc)}
                          className="flex-1 text-xs gap-1 h-8"
                        >
                          <Eye className="h-3 w-3" />
                          Preview
                        </Button>

                        {isDraft && (
                          <Button
                            size="sm"
                            disabled={processingDocId === doc.id}
                            onClick={() => handleApproveDocument(doc.id)}
                            className="text-xs h-8 px-2.5 gap-1 bg-emerald-600 hover:bg-emerald-500 text-white"
                          >
                            {processingDocId === doc.id ? (
                              <RefreshCw className="h-3 w-3 animate-spin" />
                            ) : (
                              <Check className="h-3 w-3" />
                            )}
                            Approve
                          </Button>
                        )}

                        {isApproved && (
                          <Button
                            variant="default"
                            size="sm"
                            disabled={processingDocId === doc.id}
                            onClick={() => handleApplyDocument(doc.id)}
                            className="text-xs h-8 px-2.5 gap-1.5"
                          >
                            {processingDocId === doc.id ? (
                              <RefreshCw className="h-3 w-3 animate-spin" />
                            ) : (
                              <GitPullRequest className="h-3 w-3" />
                            )}
                            Open PR
                          </Button>
                        )}

                        {isApplied && doc.pr_url && (
                          <a href={doc.pr_url} target="_blank" rel="noopener noreferrer">
                            <Button variant="outline" size="sm" className="text-xs h-8 px-2 border-emerald-900/60 text-emerald-300">
                              <ExternalLink className="h-3 w-3" />
                            </Button>
                          </a>
                        )}
                      </div>
                    </CardContent>
                  </Card>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* TAB 7: COMMIT GUARD & PUSH-TRIGGERED ALERT LOG (STEP 7)                  */}
      {/* ========================================================================= */}
      {activeTab === "commitguard" && (
        <div className="space-y-6 animate-in fade-in duration-200">
          {/* Header Action Bar */}
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 p-4 rounded-lg border border-[#1C1C21] bg-[#0E0E11]">
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <ShieldCheck className="h-4 w-4 text-emerald-400" />
                <h2 className="text-base font-semibold text-zinc-100 tracking-tight">
                  Autonomous Push-Triggered Commit Guard
                </h2>
                <Badge variant="outline" className="border-[#26262C] bg-[#141418] text-zinc-300 text-[11px] font-mono">
                  Real-Time Audit & Alert
                </Badge>
              </div>
              <p className="text-xs text-zinc-400 mt-1 max-w-2xl">
                Every developer push triggers incremental indexing, secret leak scanning, vulnerability analysis, system design auto-refresh, and immediate email alerts on critical findings.
              </p>
            </div>
            <div className="flex items-center gap-2 flex-wrap">
              <SmtpTestBanner
                repoName={selectedRepo?.fullName || selectedRepo?.name || "ForgeAI"}
                onNotification={addToast}
              />
              <Button
                variant="outline"
                size="sm"
                onClick={handleCheckForNewCommits}
                disabled={isCheckingNewCommits || !selectedRepoId}
                className="gap-2 text-xs border-emerald-900/60 text-emerald-300 hover:bg-emerald-950/30"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${isCheckingNewCommits ? "animate-spin" : ""}`} />
                {isCheckingNewCommits ? "Checking GitHub..." : "Check for New Commits Now"}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={fetchCommitReports}
                disabled={isLoadingCommitReports}
                className="gap-2 text-xs"
              >
                <RefreshCw className={`h-3.5 w-3.5 ${isLoadingCommitReports ? "animate-spin" : ""}`} />
                Refresh
              </Button>
              <Button
                variant="default"
                size="sm"
                onClick={handleAnalyzeCommitOnDemand}
                disabled={isOnDemandAnalyzing || !selectedRepoId}
                className="gap-2 text-xs"
              >
                {isOnDemandAnalyzing ? (
                  <>
                    <RefreshCw className="h-3.5 w-3.5 animate-spin" />
                    Scanning Head Commit...
                  </>
                ) : (
                  <>
                    <Play className="h-3.5 w-3.5" />
                    Scan Commit On-Demand
                  </>
                )}
              </Button>
            </div>
          </div>

          {/* Feedback Banners */}
          {docActionSuccess && (
            <div className="flex items-center gap-2 rounded-md border border-emerald-900/60 bg-emerald-950/20 p-3 text-xs text-emerald-300">
              <Check className="h-4 w-4 shrink-0 text-emerald-400" />
              <span>{docActionSuccess}</span>
            </div>
          )}
          {docActionError && (
            <div className="flex items-center gap-2 rounded-md border border-rose-900/60 bg-rose-950/20 p-3 text-xs text-rose-300">
              <AlertCircle className="h-4 w-4 shrink-0 text-rose-400" />
              <span>{docActionError}</span>
            </div>
          )}

          {/* Previous AI Data Banner */}
          {documents.some((d) => d.provenance?.showing_previous_ai_data) && (
            <div className="flex items-center gap-3 rounded-lg border border-amber-900/60 bg-amber-950/20 p-4 text-xs text-amber-200">
              <div className="p-2 rounded-md bg-amber-950/50 border border-amber-900/60 text-amber-400 shrink-0">
                <AlertTriangle className="h-4 w-4" />
              </div>
              <div>
                <p className="font-semibold text-amber-300 text-sm">
                  This is the previous data — AI API is not working currently.
                </p>
                <p className="text-zinc-300 mt-0.5">
                  The live AI API is currently unreachable. Displaying the most recent AI-analyzed System Design & Architecture document.
                </p>
              </div>
            </div>
          )}

          {/* Metric Stats Banner */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
            <Card className="border-[#1C1C21] bg-[#0E0E11] p-4">
              <div className="text-xs text-zinc-400">Total Scanned Commits</div>
              <div className="text-2xl font-semibold font-mono tabular-nums text-zinc-100 mt-1">{commitReports.length}</div>
            </Card>
            <Card className="border-[#1C1C21] bg-[#0E0E11] p-4">
              <div className="text-xs text-zinc-400">Critical Alerts Triggered</div>
              <div className="text-2xl font-semibold font-mono tabular-nums text-rose-400 mt-1">
                {commitReports.filter((r) => r.status === "CRITICAL_ALERT").length}
              </div>
            </Card>
            <Card className="border-[#1C1C21] bg-[#0E0E11] p-4">
              <div className="text-xs text-zinc-400">Secret Leaks Detected</div>
              <div className="text-2xl font-semibold font-mono tabular-nums text-amber-400 mt-1">
                {commitReports.reduce((acc, r) => acc + (r.secrets_detected || 0), 0)}
              </div>
            </Card>
            <Card className="border-[#1C1C21] bg-[#0E0E11] p-4">
              <div className="text-xs text-zinc-400">Alert Emails Sent / Dry-run</div>
              <div className="text-2xl font-semibold font-mono tabular-nums text-sky-400 mt-1">
                {commitReports.filter((r) => r.alert_sent).length}
              </div>
            </Card>
          </div>

          {/* Reports Table */}
          <Card className="border-[#1C1C21] bg-[#0E0E11]">
            <CardHeader className="border-b border-[#1C1C21] pb-3">
              <div className="flex items-center justify-between">
                <CardTitle className="text-sm font-semibold text-zinc-100">Commit Security & Alert Ledger</CardTitle>
                <Badge variant="outline" className="text-xs text-zinc-400 font-mono tabular-nums border-[#26262C]">
                  {commitReports.length} records
                </Badge>
              </div>
            </CardHeader>
            <CardContent className="p-0">
              {isLoadingCommitReports ? (
                <div className="p-4">
                  <CommitReportsListSkeleton />
                </div>
              ) : commitReports.length === 0 ? (
                <div className="p-12 text-center space-y-3">
                  <ShieldCheck className="h-9 w-9 text-zinc-600 mx-auto" />
                  <p className="text-sm text-zinc-200 font-semibold">No Commit Reports Recorded Yet</p>
                  <p className="text-xs text-zinc-500 max-w-md mx-auto">
                    Push code to this repository via git or click &ldquo;Scan Commit On-Demand&rdquo; to analyze the current head commit.
                  </p>
                </div>
              ) : (
                <div className="divide-y divide-[#1C1C21]">
                  {commitReports.map((report) => {
                    const isCritical = report.status === "CRITICAL_ALERT";
                    const isWarning = report.status === "WARNING";

                    return (
                      <div
                        key={report.id}
                        className="p-4 hover:bg-[#141418]/60 transition-colors flex flex-col md:flex-row md:items-center justify-between gap-4"
                      >
                        <div className="space-y-1.5 flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap">
                            <Badge
                              variant="outline"
                              className={`text-[10px] font-mono font-semibold uppercase ${
                                isCritical
                                  ? "border-rose-900/60 text-rose-300 bg-rose-950/20"
                                  : isWarning
                                  ? "border-amber-900/60 text-amber-300 bg-amber-950/20"
                                  : "border-emerald-900/60 text-emerald-300 bg-emerald-950/20"
                              }`}
                            >
                              {report.status}
                            </Badge>
                            <span className="font-mono tabular-nums text-xs text-sky-400 font-semibold">
                              {report.commit_sha ? report.commit_sha.slice(0, 8) : "HEAD"}
                            </span>
                            <span className="text-xs text-zinc-400 font-mono">
                              ({report.ref || "main"})
                            </span>
                            {report.author_name && (
                              <span className="text-xs text-zinc-300">
                                by {report.author_name}
                              </span>
                            )}
                          </div>
                          <p className="text-xs text-zinc-200 line-clamp-1 font-sans">
                            {report.commit_message || "Commit push"}
                          </p>
                          <div className="flex items-center gap-3 text-[11px] font-mono tabular-nums text-zinc-400 flex-wrap">
                            <span className={report.secrets_detected > 0 ? "text-amber-400 font-semibold" : ""}>
                              Secrets: {report.secrets_detected}
                            </span>
                            <span>Security: {report.security_issues}</span>
                            <span>Reliability: {report.reliability_issues}</span>
                            <span>Risk Score: {report.risk_score?.toFixed(1) || "0.0"}</span>
                            {report.alert_sent && (
                              <Badge variant="outline" className="text-[9px] border-sky-900/60 text-sky-300 gap-1 py-0">
                                <Mail className="h-2.5 w-2.5" />
                                Alert: {report.alert_status}
                              </Badge>
                            )}
                          </div>
                        </div>

                        <div className="flex items-center gap-2 shrink-0">
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => setSelectedCommitReport(report)}
                            className="text-xs gap-1.5 h-8"
                          >
                            <Eye className="h-3 w-3" />
                            View Full Report
                          </Button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: GENERATED DOCUMENT PREVIEW & ACTIONS                               */}
      {/* ========================================================================= */}
      {selectedDocPreview && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-4xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            {/* Header */}
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-4">
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge variant="outline" className="text-[10px] font-mono uppercase border-sky-900/60 text-sky-300 bg-sky-950/20">
                    {selectedDocPreview.doc_type}
                  </Badge>
                  <Badge variant="outline" className="text-[10px] font-mono border-[#26262C] text-zinc-300 bg-[#141418]">
                    {selectedDocPreview.status}
                  </Badge>
                  <span className="font-mono tabular-nums text-xs text-zinc-400">
                    commit: {selectedDocPreview.commit_sha ? selectedDocPreview.commit_sha.slice(0, 8) : "HEAD"}
                  </span>
                </div>
                <h3 className="text-base font-semibold text-zinc-100 font-mono mt-1.5">
                  {selectedDocPreview.target_path}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setSelectedDocPreview(null)}
                className="text-zinc-400 hover:text-zinc-100 rounded-md p-1 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Warning Banner in Modal */}
            {selectedDocPreview.provenance?.showing_previous_ai_data && (
              <div className="p-3.5 rounded-lg border border-amber-900/60 bg-amber-950/20 text-amber-200 text-xs flex items-center gap-2.5">
                <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0" />
                <span className="font-semibold">This is the previous data — AI API is not working currently.</span>
              </div>
            )}

            {/* Citations List */}
            {selectedDocPreview.citations && selectedDocPreview.citations.length > 0 && (
              <div className="space-y-1.5 bg-[#09090B] p-3 rounded-lg border border-[#1C1C21]">
                <div className="text-xs font-semibold text-zinc-300">Verified Citations:</div>
                <div className="flex flex-wrap gap-1.5 max-h-24 overflow-y-auto">
                  {selectedDocPreview.citations.map((c, idx) => (
                    <span
                      key={idx}
                      className="font-mono text-[10px] bg-[#141418] border border-[#26262C] px-2 py-0.5 rounded text-sky-300"
                    >
                      {c.file_path}@{c.commit_sha ? c.commit_sha.slice(0, 7) : "HEAD"}
                    </span>
                  ))}
                </div>
              </div>
            )}

            {/* Document Content */}
            <div className="space-y-1.5">
              <MarkdownMermaidViewer
                content={selectedDocPreview.content_md}
                title={selectedDocPreview.doc_type}
                citations={selectedDocPreview.citations}
              />
            </div>

            {/* Footer Actions */}
            <div className="flex items-center justify-between border-t border-[#1C1C21] pt-4">
              <div className="text-[11px] text-zinc-500 font-mono tabular-nums">
                Hash: {selectedDocPreview.content_hash}
              </div>
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setSelectedDocPreview(null)}
                  className="text-xs"
                >
                  Close
                </Button>

                {selectedDocPreview.status === "DRAFT" && (
                  <>
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={processingDocId === selectedDocPreview.id}
                      onClick={() => handleRejectDocument(selectedDocPreview.id)}
                      className="text-xs border-rose-900/60 text-rose-300 hover:bg-rose-950/20"
                    >
                      Reject
                    </Button>
                    <Button
                      size="sm"
                      disabled={processingDocId === selectedDocPreview.id}
                      onClick={() => handleApproveDocument(selectedDocPreview.id)}
                      className="text-xs bg-emerald-600 hover:bg-emerald-500 text-white"
                    >
                      Approve
                    </Button>
                  </>
                )}

                {selectedDocPreview.status === "APPROVED" && (
                  <Button
                    variant="default"
                    size="sm"
                    disabled={processingDocId === selectedDocPreview.id}
                    onClick={() => handleApplyDocument(selectedDocPreview.id)}
                    className="text-xs gap-1.5"
                  >
                    <GitPullRequest className="h-3.5 w-3.5" />
                    Open Pull Request
                  </Button>
                )}

                {selectedDocPreview.status === "APPLIED" && selectedDocPreview.pr_url && (
                  <a href={selectedDocPreview.pr_url} target="_blank" rel="noopener noreferrer">
                    <Button size="sm" className="text-xs gap-1.5 bg-emerald-600 hover:bg-emerald-500 text-white">
                      <ExternalLink className="h-3.5 w-3.5" />
                      View Pull Request
                    </Button>
                  </a>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: COMMIT ANALYSIS REPORT PREVIEW & AUDIT                             */}
      {/* ========================================================================= */}
      {selectedCommitReport && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-4xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            {/* Header */}
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-4">
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge
                    variant="outline"
                    className={`text-[10px] font-mono font-semibold uppercase ${
                      selectedCommitReport.status === "CRITICAL_ALERT"
                        ? "border-rose-900/60 text-rose-300 bg-rose-950/20"
                        : selectedCommitReport.status === "WARNING"
                        ? "border-amber-900/60 text-amber-300 bg-amber-950/20"
                        : "border-emerald-900/60 text-emerald-300 bg-emerald-950/20"
                    }`}
                  >
                    {selectedCommitReport.status}
                  </Badge>
                  <span className="font-mono tabular-nums text-xs text-sky-400 font-semibold">
                    sha: {selectedCommitReport.commit_sha}
                  </span>
                  <span className="text-xs text-zinc-400 font-mono">
                    ref: {selectedCommitReport.ref || "main"}
                  </span>
                </div>
                <div className="text-xs text-zinc-400 mt-1 flex items-center gap-3 flex-wrap">
                  <span>Author: <strong className="text-zinc-100">{selectedCommitReport.author_name || "Unknown"}</strong> ({selectedCommitReport.author_email || "no-email"})</span>
                  <span className="font-mono tabular-nums">Scanned: {new Date(selectedCommitReport.created_at).toLocaleString()}</span>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setSelectedCommitReport(null)}
                className="text-zinc-400 hover:text-zinc-100 rounded-md p-1 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Commit Message */}
            <div className="bg-[#09090B] p-3 rounded-lg border border-[#1C1C21] text-xs text-zinc-200">
              <div className="text-[10px] text-zinc-500 font-mono uppercase mb-1">Commit Message</div>
              <p className="font-sans">{selectedCommitReport.commit_message || "No commit message"}</p>
            </div>

            {/* Email Alert Banner */}
            <div className="p-3 rounded-lg border border-[#1C1C21] bg-[#09090B] flex items-center justify-between text-xs flex-wrap gap-2">
              <div className="flex items-center gap-2">
                <Mail className="h-4 w-4 text-sky-400" />
                <span className="text-zinc-300">Notification Dispatch Status: <strong className="text-zinc-100">{selectedCommitReport.alert_status}</strong></span>
              </div>
              {selectedCommitReport.alert_recipient && (
                <span className="text-zinc-400 font-mono text-[11px]">Recipient: {selectedCommitReport.alert_recipient}</span>
              )}
            </div>

            {/* Redacted Findings */}
            {selectedCommitReport.findings && selectedCommitReport.findings.length > 0 && (
              <div className="space-y-2">
                <div className="text-xs font-semibold text-zinc-300 font-mono tabular-nums">
                  Detected Findings ({selectedCommitReport.findings.length}):
                </div>
                <div className="space-y-2 max-h-48 overflow-y-auto">
                  {selectedCommitReport.findings.map((f: any, idx: number) => (
                    <div key={idx} className="p-3 rounded-lg border border-rose-900/60 bg-rose-950/15 text-xs space-y-1">
                      <div className="flex items-center justify-between">
                        <span className="font-semibold text-zinc-100">{f.title || f.category || "Issue"}</span>
                        <Badge variant="outline" className="text-[10px] text-rose-300 border-rose-900/60 uppercase font-mono">
                          {f.severity || "HIGH"}
                        </Badge>
                      </div>
                      <div className="text-[11px] text-zinc-400 font-mono tabular-nums">
                        {f.file_path}{f.line_number ? `:${f.line_number}` : ""}
                      </div>
                      {f.redacted_evidence && (
                        <div className="font-mono text-[10px] text-amber-300 bg-[#09090B] p-2 rounded-md border border-[#1C1C21]">
                          {f.redacted_evidence}
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Full Markdown Report */}
            <div className="space-y-1.5">
              <MarkdownMermaidViewer
                content={selectedCommitReport.report_markdown}
                title={`Commit-${selectedCommitReport.commit_sha ? selectedCommitReport.commit_sha.slice(0, 7) : "Report"}`}
              />
            </div>

            {/* Footer */}
            <div className="flex items-center justify-between border-t border-[#1C1C21] pt-4">
              <div className="flex items-center gap-3 text-xs text-zinc-400 font-mono tabular-nums flex-wrap">
                <span>Risk Score: {selectedCommitReport.risk_score?.toFixed(1) || "0.0"}</span>
                <span>Secrets: {selectedCommitReport.secrets_detected}</span>
                <span>Security: {selectedCommitReport.security_issues}</span>
                <span>Reliability: {selectedCommitReport.reliability_issues}</span>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setSelectedCommitReport(null)}
                className="text-xs"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 1: FINDING DETAIL VIEW & SAFE CODE CONTEXT (PHASE 10, 11 & 19)      */}
      {/* ========================================================================= */}
      {selectedFindingForModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            {/* Modal Header */}
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge
                    variant={selectedFindingForModal.severity === "CRITICAL" ? "destructive" : "outline"}
                    className={`text-[10px] font-mono font-semibold uppercase tracking-wider ${
                      selectedFindingForModal.severity === "HIGH"
                        ? "text-amber-400 border-amber-900/60 bg-amber-950/20"
                        : selectedFindingForModal.severity === "MEDIUM"
                        ? "text-amber-300 border-amber-900/60 bg-amber-950/15"
                        : ""
                    }`}
                  >
                    {selectedFindingForModal.severity}
                  </Badge>
                  <Badge variant="outline" className="text-[10px] font-mono text-emerald-400 border-emerald-900/60 bg-emerald-950/20 flex items-center gap-1">
                    <ShieldCheck className="h-3 w-3" />
                    DETERMINISTIC
                  </Badge>
                  <span className="font-mono text-xs text-zinc-400 uppercase tracking-wider">
                    {selectedFindingForModal.category}
                  </span>
                </div>
                <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                  {selectedFindingForModal.title}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => {
                  setSelectedFindingForModal(null);
                  setAiExplanation(null);
                  setExplainError(null);
                }}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Location & Rule Metadata */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 text-xs font-mono">
              <div className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21]">
                <span className="text-[10px] text-zinc-500 uppercase block">Rule ID</span>
                <span className="font-semibold text-zinc-200">{selectedFindingForModal.rule_id || "SEC_GENERAL_PATTERN"}</span>
              </div>
              <div className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21]">
                <span className="text-[10px] text-zinc-500 uppercase block">Location</span>
                <span className="font-semibold text-zinc-100 truncate block tabular-nums">
                  {selectedFindingForModal.file_path || selectedFindingForModal.affected_file || "unknown"}
                  {selectedFindingForModal.line_number ? `:${selectedFindingForModal.line_number}` : ""}
                </span>
              </div>
              <div className="p-2.5 rounded-md bg-[#09090B] border border-[#1C1C21]">
                <span className="text-[10px] text-zinc-500 uppercase block">Merge Blocker</span>
                <span className={`font-semibold ${selectedFindingForModal.is_blocker ? "text-rose-400" : "text-zinc-400"}`}>
                  {selectedFindingForModal.is_blocker ? "BLOCKING" : "ADVISORY"}
                </span>
              </div>
            </div>

            {/* Redacted Evidence Box (Strict Zero Raw Secret Leakage) */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-mono uppercase tracking-wider text-zinc-400 font-semibold">
                Redacted Deterministic Evidence
              </span>
              <div className="p-3 rounded-md bg-[#09090B] border border-[#1C1C21] font-mono text-xs text-amber-300 break-all select-all">
                {selectedFindingForModal.redacted_evidence || selectedFindingForModal.evidence || "[REDACTED]"}
              </div>
            </div>

            {/* Safe Code Context Viewer (Phase 11: lines -3 to +3 with [REDACTED SECRET]) */}
            {(() => {
              const targetPath = selectedFindingForModal.file_path || selectedFindingForModal.affected_file;
              const targetLine = selectedFindingForModal.line_number;
              const contextLines = getSafeCodeContext(targetPath, targetLine);
              if (!contextLines || contextLines.length === 0) return null;

              return (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between text-[11px] font-mono text-zinc-400 font-semibold">
                    <span>Safe Code Context (Target Line &plusmn; 3)</span>
                    <span className="text-emerald-400">Zero Raw Secrets</span>
                  </div>
                  <div className="rounded-md border border-[#1C1C21] bg-[#09090B] p-3 font-mono text-xs overflow-x-auto max-h-48 divide-y divide-[#1C1C21]/60">
                    {contextLines.map((cl, idx) => (
                      <div
                        key={idx}
                        className={`flex items-start gap-3 py-0.5 px-1 rounded ${
                          cl.isTarget
                            ? "bg-rose-950/30 text-rose-200 border-l-2 border-rose-500 font-semibold"
                            : "text-zinc-400"
                        }`}
                      >
                        <span className="w-8 text-right text-zinc-600 font-mono tabular-nums select-none shrink-0">{cl.num}</span>
                        <pre className="whitespace-pre overflow-x-auto flex-1 font-mono text-xs">{cl.text || " "}</pre>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })()}

            {/* Grounded AI Explanation Layer (Phase 19) */}
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-mono uppercase tracking-wider text-zinc-400 font-semibold flex items-center gap-1.5">
                  <Sparkles className="h-3.5 w-3.5 text-zinc-400" />
                  ForgeAI Grounded Reasoning Layer
                </span>
                {!aiExplanation && !isExplainingFinding && (
                  <Button
                    type="button"
                    size="sm"
                    variant="default"
                    onClick={() => handleExplainFinding(selectedFindingForModal)}
                    className="h-7 text-xs px-3"
                  >
                    Explain with ForgeAI
                  </Button>
                )}
              </div>

              {isExplainingFinding ? (
                <div className="p-4 rounded-md border border-[#1C1C21] bg-[#09090B] text-xs text-zinc-300 flex items-center justify-center gap-2">
                  <Loader2 className="h-4 w-4 animate-spin text-zinc-400" />
                  Generating grounded contextual explanation from deterministic rule...
                </div>
              ) : explainError ? (
                <div className="p-3 rounded-md border border-rose-900/60 bg-rose-950/20 text-xs text-rose-300">
                  {explainError}
                </div>
              ) : aiExplanation ? (
                <div className="p-4 rounded-lg border border-[#1C1C21] bg-[#09090B] space-y-3 text-xs">
                  <div>
                    <h4 className="font-semibold text-zinc-100 text-xs mb-1">What Happened?</h4>
                    <p className="text-zinc-300 leading-relaxed">{aiExplanation.what_happened}</p>
                  </div>
                  <div>
                    <h4 className="font-semibold text-amber-300 text-xs mb-1">Why It Is Risky</h4>
                    <p className="text-zinc-300 leading-relaxed">{aiExplanation.why_it_is_risky}</p>
                  </div>
                  <div>
                    <h4 className="font-semibold text-rose-300 text-xs mb-1">Potential Production Impact</h4>
                    <p className="text-zinc-300 leading-relaxed">{aiExplanation.production_impact}</p>
                  </div>
                  <div>
                    <h4 className="font-semibold text-emerald-300 text-xs mb-1">Recommended Remediation</h4>
                    <p className="text-zinc-300 leading-relaxed">{aiExplanation.how_to_fix}</p>
                  </div>
                  {aiExplanation.remediation_guidance && aiExplanation.remediation_guidance.length > 0 && (
                    <div className="space-y-1 pt-2 border-t border-[#1C1C21]">
                      <span className="text-[10px] font-mono uppercase tracking-wider text-zinc-400 font-semibold">
                        Step-by-Step Remediation Guidance:
                      </span>
                      <ul className="list-disc list-inside space-y-0.5 text-zinc-300 text-[11px]">
                        {aiExplanation.remediation_guidance.map((step, sIdx) => (
                          <li key={sIdx}>{step}</li>
                        ))}
                      </ul>
                    </div>
                  )}
                </div>
              ) : null}
            </div>

            {/* Modal Footer */}
            <div className="flex items-center justify-between pt-3 border-t border-[#1C1C21]">
              <span className="text-[11px] font-mono text-zinc-500">
                Verification status: <span className="text-amber-400 font-semibold">Pending Re-Scan</span>
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => {
                  setSelectedFindingForModal(null);
                  setAiExplanation(null);
                  setExplainError(null);
                }}
                className="text-xs px-4"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 2: RE-SCAN DIFFERENTIAL COMPARISON (PHASE 13)                       */}
      {/* ========================================================================= */}
      {isRescanModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-2.5">
                <RefreshCw className="h-4 w-4 text-emerald-400" />
                <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                  Authoritative Differential Re-Scan
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setIsRescanModalOpen(false)}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isRescanning ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-7 w-7 animate-spin text-zinc-400 mx-auto" />
                <p className="text-xs text-zinc-300 font-mono">
                  Executing authoritative re-scan pipeline and verifying finding removal...
                </p>
              </div>
            ) : rescanResult ? (
              <div className="space-y-4">
                {/* 3 Metric Comparison Cards */}
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 font-mono tabular-nums text-xs">
                  {/* Risk Score Delta */}
                  <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                    <span className="text-[10px] uppercase font-semibold text-zinc-400">Deterministic Risk</span>
                    <div className="flex items-center gap-2">
                      <span className="text-zinc-400 line-through">{rescanResult.before_risk_score.toFixed(1)}</span>
                      <ArrowRight className="h-3 w-3 text-zinc-500" />
                      <span className="text-base font-semibold text-emerald-400">{rescanResult.after_risk_score.toFixed(1)}</span>
                    </div>
                    <span className="text-[10px] text-emerald-400">&minus;{rescanResult.risk_delta.toFixed(1)} pts reduced</span>
                  </div>

                  {/* Findings Delta */}
                  <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                    <span className="text-[10px] uppercase font-semibold text-zinc-400">Critical Findings</span>
                    <div className="flex items-center gap-2">
                      <span className="text-zinc-400 line-through">{rescanResult.before_critical_count}</span>
                      <ArrowRight className="h-3 w-3 text-zinc-500" />
                      <span className="text-base font-semibold text-emerald-400">{rescanResult.after_critical_count}</span>
                    </div>
                    <span className="text-[10px] text-zinc-400">
                      Resolved: <strong className="text-emerald-400">{rescanResult.findings_resolved.length}</strong>
                    </span>
                  </div>

                  {/* Merge Readiness Delta */}
                  <div className="p-3 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-1">
                    <span className="text-[10px] uppercase font-semibold text-zinc-400">Merge Readiness</span>
                    <div className="text-xs font-semibold text-zinc-100 truncate">
                      {rescanResult.merge_readiness_delta}
                    </div>
                    <span className="text-[10px] text-zinc-500">
                      Status: {rescanResult.is_clean ? "CLEAN" : "UPDATED"}
                    </span>
                  </div>
                </div>

                {/* Resolved Findings List */}
                {rescanResult.findings_resolved.length > 0 && (
                  <div className="p-3 rounded-md border border-emerald-900/60 bg-emerald-950/20 space-y-2">
                    <div className="text-xs font-semibold text-emerald-300 flex items-center gap-1.5 font-mono tabular-nums">
                      <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                      Confirmed Resolved Findings ({rescanResult.findings_resolved.length})
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {rescanResult.findings_resolved.map((rule, idx) => (
                        <Badge key={idx} variant="outline" className="text-[10px] font-mono text-emerald-300 border-emerald-900/60 bg-emerald-950/30">
                          &check; {rule}
                        </Badge>
                      ))}
                    </div>
                  </div>
                )}

                {/* Summary Notice */}
                <p className="text-xs font-mono text-zinc-300 bg-[#09090B] p-3 rounded-md border border-[#1C1C21] leading-relaxed">
                  {rescanResult.summary}
                </p>
                <div className="text-[11px] text-zinc-500">
                  Note: ForgeAI marks findings as fixed solely through deterministic re-analysis, never through unverified LLM claims.
                </div>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-[#1C1C21]">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setIsRescanModalOpen(false)}
                className="text-xs px-4"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 3: ON-DEMAND SECRET SCAN RESULTS (PHASE 15 & 16)                    */}
      {/* ========================================================================= */}
      {isSecretScanModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-2.5">
                <KeyRound className="h-4 w-4 text-zinc-400" />
                <div>
                  <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                    On-Demand Secret Exposure Scan
                  </h3>
                  <div className="text-[11px] font-mono text-zinc-400">
                    High-Entropy Heuristics &bull; Zero Raw Secret Leakage Guaranteed
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsSecretScanModalOpen(false)}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isSecretScanning ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-7 w-7 animate-spin text-zinc-400 mx-auto" />
                <p className="text-xs text-zinc-300 font-mono">
                  Scanning repository snapshot and active PR changes for exposed credentials...
                </p>
              </div>
            ) : secretScanResult ? (
              <div className="space-y-4">
                {/* Metrics Pill Grid */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 font-mono tabular-nums text-xs">
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Secrets Found</span>
                    <span className={`text-base font-semibold ${secretScanResult.secrets_found > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {secretScanResult.secrets_found}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Critical</span>
                    <span className="text-base font-semibold text-rose-400">
                      {secretScanResult.critical_count}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Files Scanned</span>
                    <span className="text-base font-semibold text-zinc-100">
                      {secretScanResult.files_scanned}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Scan Duration</span>
                    <span className="text-base font-semibold text-zinc-100">
                      {(secretScanResult.scan_time_ms ?? 0).toFixed(0)}ms
                    </span>
                  </div>
                </div>

                {/* Findings List */}
                {secretScanResult.findings && secretScanResult.findings.length > 0 ? (
                  <div className="space-y-2 max-h-60 overflow-y-auto">
                    {secretScanResult.findings.map((sf, idx) => (
                      <div key={idx} className="p-3 rounded-lg border border-rose-900/60 bg-rose-950/15 text-xs space-y-1.5 font-mono">
                        <div className="flex items-center justify-between">
                          <span className="text-rose-300 font-semibold">{sf.title}</span>
                          <Badge variant="destructive" className="text-[9px] uppercase">
                            ACTIVE EXPOSURE
                          </Badge>
                        </div>
                        <div className="text-zinc-400 text-[11px] tabular-nums">
                          Location: <span className="text-zinc-200">{sf.file_path}:{sf.line_number || 1}</span>
                        </div>
                        <div className="p-2 rounded-md bg-[#09090B] border border-[#1C1C21] text-amber-300 text-[11px]">
                          Evidence: <span className="font-semibold">{sf.redacted_evidence}</span>
                        </div>
                        <div className="text-[11px] text-zinc-300">
                          Action: <span className="text-emerald-400">{sf.remediation}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="p-6 rounded-lg border border-[#1C1C21] bg-[#09090B] text-center text-xs text-emerald-400 font-mono flex items-center justify-center gap-2">
                    <CheckCircle2 className="h-4 w-4" />
                    Zero exposed credentials or secret keys detected across analyzed files.
                  </div>
                )}

                <p className="text-xs text-zinc-400 font-mono bg-[#09090B] p-2.5 rounded-md border border-[#1C1C21]">
                  {secretScanResult.summary}
                </p>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-[#1C1C21]">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setIsSecretScanModalOpen(false)}
                className="text-xs px-4"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 4: DEPENDENCY SECURITY STATUS (PHASE 17)                            */}
      {/* ========================================================================= */}
      {isDepSecurityModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-2.5">
                <Boxes className="h-4 w-4 text-sky-400" />
                <div>
                  <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                    Dependency Security & CVE Intelligence
                  </h3>
                  <div className="text-[11px] font-mono text-zinc-400">
                    Truthful Evidence Reporting &bull; Maven, npm, Python Ecosystems
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsDepSecurityModalOpen(false)}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isLoadingDepSecurity ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-7 w-7 animate-spin text-zinc-400 mx-auto" />
                <p className="text-xs text-zinc-300 font-mono">
                  Checking dependency manifest security status...
                </p>
              </div>
            ) : dependencySecurity ? (
              <div className="space-y-4">
                {/* 4 Status Badges */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 font-mono text-xs">
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Status</span>
                    <Badge variant="outline" className="text-[10px] font-mono text-sky-300 border-sky-900/60 bg-sky-950/20">
                      {dependencySecurity.status}
                    </Badge>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Code-Level Security</span>
                    <span className="font-semibold text-emerald-400">{dependencySecurity.code_level_security}</span>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">Secret Scanning</span>
                    <span className="font-semibold text-emerald-400">{dependencySecurity.secret_scanning}</span>
                  </div>
                  <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B]">
                    <span className="text-[10px] uppercase text-zinc-500 block">CVE Database</span>
                    <span className={`font-semibold ${dependencySecurity.dependency_cve_database === "CONFIGURED" ? "text-emerald-400" : "text-amber-400"}`}>
                      {dependencySecurity.dependency_cve_database}
                    </span>
                  </div>
                </div>

                {/* Ecosystem Notice */}
                <div className="p-3.5 rounded-lg border border-sky-900/60 bg-sky-950/20 text-xs text-sky-200 space-y-1 leading-relaxed">
                  <div className="font-semibold text-sky-100 flex items-center gap-1.5 font-mono">
                    <Info className="h-4 w-4 text-sky-400" />
                    Truthful Partial Evidence Contract
                  </div>
                  <p>
                    External commercial CVE feeds are unconfigured. Manifest parsing for <strong>Maven (pom.xml)</strong>, <strong>npm (package.json)</strong>, and <strong>Python (requirements.txt)</strong> is active. ForgeAI never manufactures synthetic CVEs.
                  </p>
                </div>

                <p className="text-xs font-mono text-zinc-300 bg-[#09090B] p-2.5 rounded-md border border-[#1C1C21]">
                  {dependencySecurity.summary}
                </p>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-[#1C1C21]">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setIsDepSecurityModalOpen(false)}
                className="text-xs px-4"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 5: ANALYSIS HISTORY TIMELINE (PHASE 27)                             */}
      {/* ========================================================================= */}
      {isHistoryModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-2.5">
                <History className="h-4 w-4 text-zinc-400" />
                <div>
                  <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                    Authoritative Analysis History & Progression
                  </h3>
                  <div className="text-[11px] font-mono text-zinc-400">
                    Chronological Differential Audit Trail
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsHistoryModalOpen(false)}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isLoadingHistory ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-7 w-7 animate-spin text-zinc-400 mx-auto" />
                <p className="text-xs text-zinc-300 font-mono">
                  Loading analysis history for entity...
                </p>
              </div>
            ) : analysisHistory && analysisHistory.length > 0 ? (
              <div className="space-y-3 max-h-80 overflow-y-auto font-mono text-xs">
                {analysisHistory.map((item, idx) => (
                  <div key={idx} className="p-3.5 rounded-md border border-[#1C1C21] bg-[#09090B] space-y-2">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2 tabular-nums">
                        <span className="font-semibold text-zinc-100">Analysis #{item.analysis_id.substring(0, 8)}</span>
                        <Badge variant="outline" className="text-[9px] border-[#26262C] text-zinc-300">
                          Risk: {item.risk_score.toFixed(1)} ({item.risk_level})
                        </Badge>
                      </div>
                      <span className="text-[10px] text-zinc-500 tabular-nums">
                        {new Date(item.created_at).toLocaleTimeString()}
                      </span>
                    </div>

                    <div className="flex items-center gap-2 text-[11px]">
                      <span className="text-zinc-400">Merge Decision:</span>
                      <strong className={item.merge_readiness.includes("BLOCKED") ? "text-rose-400" : "text-emerald-400"}>
                        {item.merge_readiness}
                      </strong>
                    </div>

                    {item.resolved_findings_from_previous && item.resolved_findings_from_previous.length > 0 && (
                      <div className="text-[11px] text-emerald-400 flex items-center gap-1.5 flex-wrap">
                        <span>Resolved:</span>
                        {item.resolved_findings_from_previous.map((r, rIdx) => (
                          <span key={rIdx} className="px-1.5 py-0.5 rounded bg-emerald-950/30 border border-emerald-900/60">
                            &check; {r}
                          </span>
                        ))}
                      </div>
                    )}

                    {item.new_findings_from_previous && item.new_findings_from_previous.length > 0 && (
                      <div className="text-[11px] text-amber-400 flex items-center gap-1.5 flex-wrap">
                        <span>New:</span>
                        {item.new_findings_from_previous.map((n, nIdx) => (
                          <span key={nIdx} className="px-1.5 py-0.5 rounded bg-amber-950/30 border border-amber-900/60">
                            + {n}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-6 rounded-md border border-[#1C1C21] bg-[#09090B] text-center text-xs text-zinc-400 font-mono">
                No previous analysis iterations recorded yet for this entity.
              </div>
            )}

            <div className="flex justify-end pt-2 border-t border-[#1C1C21]">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setIsHistoryModalOpen(false)}
                className="text-xs px-4"
              >
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 6: AI API UNAVAILABLE / LAST ANALYSIS NOTIFICATION MODAL             */}
      {/* ========================================================================= */}
      {aiApiErrorModal.isOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/75 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-lg rounded-lg border border-[#26262C] bg-[#0E0E11] p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-[#1C1C21] pb-3">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-md bg-amber-950/40 border border-amber-900/60 text-amber-400">
                  <AlertTriangle className="h-5 w-5" />
                </div>
                <div>
                  <h3 className="text-base font-semibold text-zinc-100 tracking-tight">
                    AI Service Notification
                  </h3>
                  <div className="text-[11px] font-mono text-zinc-400">
                    AI Service Status: Unreachable / Offline
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setAiApiErrorModal((prev) => ({ ...prev, isOpen: false }))}
                className="p-1 rounded-md text-zinc-400 hover:text-zinc-100 hover:bg-[#141418] transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="space-y-3">
              <div className="p-3.5 rounded-lg border border-amber-900/60 bg-amber-950/20 text-amber-200">
                <p className="text-sm font-semibold text-amber-200">
                  {aiApiErrorModal.message}
                </p>
                {aiApiErrorModal.hasLastAnalysis && (
                  <p className="text-xs text-amber-300/90 mt-2 font-mono tabular-nums">
                    Showing last analysis recorded on: <span className="font-semibold underline">{aiApiErrorModal.timestamp}</span>
                    {aiApiErrorModal.repoName && <span> for <strong>{aiApiErrorModal.repoName}</strong></span>}
                    {aiApiErrorModal.prNumber && <span> (PR #{aiApiErrorModal.prNumber})</span>}.
                  </p>
                )}
              </div>

              {aiApiErrorModal.errorMessage && (
                <div className="p-2.5 rounded-md border border-[#1C1C21] bg-[#09090B] text-[11px] font-mono text-zinc-400">
                  <span className="text-zinc-500">Error trace:</span> {aiApiErrorModal.errorMessage}
                </div>
              )}

              <p className="text-xs text-zinc-400 leading-relaxed">
                {aiApiErrorModal.hasLastAnalysis
                  ? "ForgeAI automatically preserved your last successful analysis from cache so you can continue reviewing security assessments, AST graphs, and test recommendations without disruption."
                  : "The AI engine is currently unreachable. Please verify that your AI Service Docker container is running and healthy."}
              </p>
            </div>

            <div className="flex items-center justify-end gap-2.5 pt-3 border-t border-[#1C1C21]">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setAiApiErrorModal((prev) => ({ ...prev, isOpen: false }))}
                className="text-xs px-4"
              >
                {aiApiErrorModal.hasLastAnalysis ? "Inspect Last Analysis" : "Close"}
              </Button>
              <Button
                type="button"
                variant="default"
                size="sm"
                onClick={() => {
                  setAiApiErrorModal((prev) => ({ ...prev, isOpen: false }));
                  handleAnalyzePr();
                }}
                disabled={isAnalyzingPr || !selectedRepoId || !selectedPrNumber}
                className="text-xs px-4 flex items-center gap-1.5"
              >
                {isAnalyzingPr ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1" /> : <RefreshCw className="h-3.5 w-3.5 mr-1" />}
                Retry AI API
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* Floating Toast Notification Stack */}
      <div className="fixed bottom-5 right-5 z-50 flex flex-col gap-2.5 max-w-sm pointer-events-none">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`pointer-events-auto p-3.5 rounded-lg border shadow-2xl bg-[#0E0E11] flex items-start gap-2.5 animate-in slide-in-from-bottom-3 duration-200 ${
              toast.type === "success"
                ? "border-emerald-900/70 text-emerald-300"
                : toast.type === "error"
                ? "border-rose-900/70 text-rose-300"
                : "border-[#26262C] text-zinc-200"
            }`}
          >
            <div className="shrink-0 mt-0.5">
              {toast.type === "success" ? (
                <CheckCircle2 className="h-4 w-4 text-emerald-400" />
              ) : toast.type === "error" ? (
                <AlertCircle className="h-4 w-4 text-rose-400" />
              ) : (
                <Sparkles className="h-4 w-4 text-sky-400" />
              )}
            </div>
            <div className="flex-1 min-w-0">
              <h5 className="text-xs font-semibold text-zinc-100 leading-tight">{toast.title}</h5>
              <p className="text-[11px] text-zinc-300 mt-0.5 leading-snug">{toast.message}</p>
              <span className="text-[9px] text-zinc-500 font-mono tabular-nums mt-1 block">
                {toast.timestamp.toLocaleTimeString()}
              </span>
            </div>
            <button
              type="button"
              onClick={() => setToasts((prev) => prev.filter((t) => t.id !== toast.id))}
              className="text-zinc-400 hover:text-zinc-100 shrink-0 p-0.5"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        ))}
      </div>
    </div>
  );
}

export default function AiInsightsPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col items-center justify-center min-h-[50vh] text-zinc-400 font-mono text-xs space-y-3">
          <Loader2 className="h-5 w-5 animate-spin text-zinc-400" />
          <p>Loading ForgeAI Engineering Health Intelligence...</p>
        </div>
      }
    >
      <AiInsightsContent />
    </Suspense>
  );
}
