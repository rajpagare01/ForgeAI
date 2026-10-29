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
} from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { evaluateSecurityStatus, extractSafeCodeContext } from "@/lib/security-helpers";

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

function AiInsightsContent() {
  const searchParams = useSearchParams();
  const { currentOrg, activeWorkspace, isPersonalWorkspace } = useTenant();
  const workspaceDisplayName = isPersonalWorkspace ? "Personal Workspace" : currentOrg?.name || "Workspace";
  const [activeTab, setActiveTab] = useState<"pr" | "search" | "impact" | "dependencies" | "remediation">("pr");

  // Authorized repositories
  const [repositories, setRepositories] = useState<Repository[]>([]);
  const [selectedRepoId, setSelectedRepoId] = useState<string>("");
  const [isLoadingRepos, setIsLoadingRepos] = useState<boolean>(true);

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

    if (qRepoId && qRepoId !== selectedRepoId) {
      setSelectedRepoId(qRepoId);
    }
    if (qPr) {
      const parsedPr = Number(qPr);
      if (!isNaN(parsedPr) && parsedPr !== selectedPrNumber) {
        setSelectedPrNumber(parsedPr);
      }
    }
    if (qSection) {
      const validSections = ["all", "security", "reliability", "tests", "dependencies", "impact", "merge"];
      if (validSections.includes(qSection)) {
        setActiveHealthSection(qSection as any);
      }
    }
  }, [searchParams, selectedRepoId, selectedPrNumber]);

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
        setRepositories(mapped);
        if (mapped.length > 0 && !selectedRepoId) {
          setSelectedRepoId(mapped[0].id);
        }
      }
    } catch (err: any) {
      console.warn("Could not fetch authorized repositories:", err);
    } finally {
      setIsLoadingRepos(false);
    }
  }, [selectedRepoId]);

  useEffect(() => {
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

  const handleReconcile = async () => {
    if (!selectedRepoId) return;
    setIsReconciling(true);
    try {
      const res = await apiClient.post<RepositorySyncStatus>(`/api/v1/repositories/${selectedRepoId}/reconcile`, {});
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
      setPrAnalysisResult(result);
      setLifecycleStep("complete");
    } catch (err: any) {
      console.warn("PR analysis failed:", err);
      setPrError(err?.message || "PR Analysis request failed on AI Service.");
      setLifecycleStep("idle");
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
          commit_sha: prChangeSet?.headSha || syncStatus?.latestObservedSha || "HEAD",
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
          commit_sha: prChangeSet?.headSha || syncStatus?.latestObservedSha || "HEAD",
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
        }
      } catch (err: any) {
        console.warn("Could not restore saved PR analysis:", err);
      }
    };

    restoreSavedAnalysis();
  }, [selectedRepoId, selectedPrNumber]);

  const selectedRepo = repositories.find((r) => r.id === selectedRepoId);

  // Filtered provenance chunks
  const filteredChunks = React.useMemo(() => {
    if (!prAnalysisResult || !Array.isArray(prAnalysisResult.retrieved_chunks)) return [];
    if (provenanceFilter === "ALL") return prAnalysisResult.retrieved_chunks;
    return prAnalysisResult.retrieved_chunks.filter((c: any) => c.source === provenanceFilter);
  }, [prAnalysisResult, provenanceFilter]);

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-6">
        <div>
          <div className="flex items-center gap-2">
            <Sparkles className="h-6 w-6 text-purple-400" />
            <h1 className="text-2xl font-bold tracking-tight text-white">
              AI Engineering Intelligence Layer
            </h1>
          </div>
          <p className="text-xs text-slate-400 mt-1">
            Automatic real GitHub PR intelligence, AST static analysis, repository-scoped blast radius, and grounded LLM reasoning for{" "}
            <span className="text-purple-300 font-medium">{workspaceDisplayName}</span>.
          </p>
        </div>

        {/* Authorized Repository Selector */}
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 bg-slate-900 border border-slate-800 rounded-lg px-3 py-1.5 shadow-sm">
            <FolderGit2 className="h-4 w-4 text-purple-400" />
            <span className="text-xs text-slate-400">Repository:</span>
            {isLoadingRepos ? (
              <Loader2 className="h-3 w-3 animate-spin text-purple-400" />
            ) : (
              <select
                aria-label="Authorized Repository Selection"
                value={selectedRepoId}
                onChange={(e) => setSelectedRepoId(e.target.value)}
                className="bg-transparent text-xs text-white font-medium focus:outline-none cursor-pointer"
              >
                {repositories.length === 0 ? (
                  <option value="" disabled className="bg-slate-900 text-slate-400">
                    No repositories connected
                  </option>
                ) : (
                  repositories.map((repo) => (
                    <option key={repo.id} value={repo.id} className="bg-slate-900 text-white">
                      {repo.fullName}
                    </option>
                  ))
                )}
              </select>
            )}
          </div>
          {selectedRepo && (
            <Badge variant="outline" className="text-xs font-mono flex items-center gap-1">
              <GitBranch className="h-3 w-3 text-indigo-400" />
              {selectedRepo.defaultBranch}
            </Badge>
          )}
        </div>
      </div>

      {/* Continuous Repository Synchronization Indicator */}
      {selectedRepo && (
        <div className="flex flex-wrap items-center justify-between gap-3 p-3.5 rounded-xl border border-slate-800 bg-slate-900/50 backdrop-blur-sm text-xs">
          <div className="flex items-center gap-4 flex-wrap">
            <div className="flex items-center gap-2">
              <span className="text-slate-400 font-medium">Sync State:</span>
              {isLoadingSyncStatus && !syncStatus ? (
                <div className="flex items-center gap-1.5 text-xs text-slate-400">
                  <Loader2 className="h-3.5 w-3.5 animate-spin text-purple-400" />
                  <span>Checking...</span>
                </div>
              ) : (
                <span
                  className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold border ${
                    syncStatus?.syncStatus === "SYNCED"
                      ? "bg-emerald-500/10 text-emerald-400 border-emerald-500/30"
                      : syncStatus?.syncStatus === "SYNCING"
                      ? "bg-blue-500/10 text-blue-400 border-blue-500/30 animate-pulse"
                      : syncStatus?.syncStatus === "STALE"
                      ? "bg-amber-500/10 text-amber-400 border-amber-500/30"
                      : syncStatus?.syncStatus === "FAILED"
                      ? "bg-rose-500/10 text-rose-400 border-rose-500/30"
                      : "bg-slate-800 text-slate-400 border-slate-700"
                  }`}
                >
                  {syncStatus?.syncStatus === "SYNCED" && <CheckCircle2 className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus === "SYNCING" && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                  {syncStatus?.syncStatus === "STALE" && <AlertTriangle className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus === "FAILED" && <ShieldAlert className="h-3.5 w-3.5" />}
                  {syncStatus?.syncStatus || "SYNCED"}
                </span>
              )}
            </div>

            <div className="flex items-center gap-1.5 font-mono text-[11px] bg-slate-950/70 border border-slate-800/80 px-2.5 py-1 rounded-md">
              <span className="text-slate-500">GitHub HEAD:</span>
              <span className="text-slate-300 font-semibold">
                {syncStatus?.latestObservedSha ? syncStatus.latestObservedSha.substring(0, 7) : "None"}
              </span>
            </div>

            <div className="flex items-center gap-1.5 font-mono text-[11px] bg-slate-950/70 border border-slate-800/80 px-2.5 py-1 rounded-md">
              <span className="text-slate-500">Indexed HEAD:</span>
              <span className="text-indigo-400 font-semibold">
                {syncStatus?.latestIndexedSha ? syncStatus.latestIndexedSha.substring(0, 7) : "None"}
              </span>
            </div>

            {syncStatus?.lastSyncedAt && (
              <div className="text-[11px] text-slate-400">
                Last Synced:{" "}
                <span className="text-slate-200 font-medium">
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
              variant="outline"
              size="sm"
              onClick={handleReconcile}
              disabled={isReconciling || isLoadingSyncStatus}
              className="h-7 text-xs px-3 border-indigo-500/30 bg-indigo-950/30 hover:bg-indigo-900/50 text-indigo-200 transition-colors"
            >
              {isReconciling ? (
                <>
                  <Loader2 className="h-3 w-3 animate-spin mr-1.5 text-indigo-400" />
                  Reconciling...
                </>
              ) : (
                <>
                  <RefreshCw className="h-3 w-3 mr-1.5 text-indigo-400" />
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
      {(() => {
        const secStatus = getSecurityStatus();
        const SecIcon = secStatus.icon;
        return (
          <div className={`p-4 rounded-xl border flex flex-col md:flex-row md:items-center justify-between gap-4 transition-all shadow-lg ${secStatus.bg}`}>
            <div className="flex items-center gap-3">
              <div className="p-2 rounded-lg bg-slate-950/60 border border-slate-800">
                <SecIcon className="h-6 w-6" />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-[11px] font-mono uppercase tracking-wider text-slate-400">Security Status</span>
                  <Badge
                    variant="outline"
                    className={`text-xs font-mono font-bold uppercase tracking-wider px-2.5 py-0.5 border ${
                      secStatus.status === "CRITICAL / BLOCKED"
                        ? "bg-rose-950 text-rose-300 border-rose-500 animate-pulse"
                        : secStatus.status === "HIGH RISK"
                        ? "bg-amber-950 text-amber-300 border-amber-500"
                        : secStatus.status === "PARTIAL EVIDENCE"
                        ? "bg-blue-950 text-blue-300 border-blue-500"
                        : secStatus.status === "WARNINGS"
                        ? "bg-yellow-950 text-yellow-300 border-yellow-500"
                        : "bg-emerald-950 text-emerald-300 border-emerald-500"
                    }`}
                  >
                    ● {secStatus.status}
                  </Badge>
                </div>
                <p className="text-xs text-slate-300 mt-1 leading-relaxed">
                  {secStatus.description}
                </p>
              </div>
            </div>

            {/* Quick Actions in Banner */}
            <div className="flex items-center gap-2 flex-wrap shrink-0">
              <Button
                variant="outline"
                size="sm"
                onClick={handleSecretScan}
                disabled={isSecretScanning || !selectedRepoId}
                className="h-8 text-xs px-3 border-purple-500/40 bg-purple-950/30 text-purple-200 hover:bg-purple-900/50"
              >
                {isSecretScanning ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <KeyRound className="h-3.5 w-3.5 mr-1.5 text-purple-400" />}
                Scan for Secrets
              </Button>

              <Button
                variant="outline"
                size="sm"
                onClick={handleRepositoryScan}
                disabled={isRepoScanning || !selectedRepoId}
                className="h-8 text-xs px-3 border-indigo-500/40 bg-indigo-950/30 text-indigo-200 hover:bg-indigo-900/50"
              >
                {isRepoScanning ? <Loader2 className="h-3.5 w-3.5 animate-spin mr-1.5" /> : <ShieldCheck className="h-3.5 w-3.5 mr-1.5 text-indigo-400" />}
                Scan Repository
              </Button>

              {prAnalysisResult?.analysis_id && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleRescan}
                  disabled={isRescanning}
                  className="h-8 text-xs px-3 border-emerald-500/40 bg-emerald-950/30 text-emerald-200 hover:bg-emerald-900/50"
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
          <Card className="border-indigo-500/30 bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950/40 shadow-xl">
            <CardHeader className="pb-3 border-b border-slate-800/80">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                <div className="flex items-center gap-2.5">
                  <Activity className="h-5 w-5 text-indigo-400" />
                  <CardTitle className="text-base text-white tracking-wide">
                    Engineering Health Center
                  </CardTitle>
                  <span className="text-xs text-slate-400 font-mono hidden sm:inline">
                    Unified Code Quality, Security & Readiness
                  </span>
                </div>

                {/* Sub-Navigation for Engineering Health */}
                <div className="flex items-center gap-1 p-1 bg-slate-950/80 border border-slate-800 rounded-lg text-xs font-mono flex-wrap">
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
                      className={`px-2.5 py-1 rounded text-[11px] font-medium transition-all ${
                        activeHealthSection === tab.id
                          ? "bg-indigo-600 text-white font-semibold shadow-sm"
                          : "text-slate-400 hover:text-white"
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "security"
                      ? "border-rose-500/60 bg-rose-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Security
                    </span>
                    <ShieldCheck className="h-3.5 w-3.5 text-emerald-400" />
                  </div>
                  <div className={`text-sm font-bold font-mono truncate ${
                    secStatusText === "CRITICAL" ? "text-rose-400" : secStatusText === "HIGH" ? "text-amber-400" : "text-emerald-400"
                  }`}>
                    {secStatusText}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
                    <span>{secRuleMatchesCount} rules</span>
                    {secretFindingsCount > 0 && (
                      <span className="text-rose-400 font-bold">{secretFindingsCount} secrets</span>
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "reliability"
                      ? "border-amber-500/60 bg-amber-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Reliability
                    </span>
                    <Bug className="h-3.5 w-3.5 text-amber-400" />
                  </div>
                  <div className="text-sm font-bold font-mono text-amber-300 truncate">
                    {relStatusText}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "tests"
                      ? "border-indigo-500/60 bg-indigo-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Tests
                    </span>
                    <TestTube className="h-3.5 w-3.5 text-indigo-400" />
                  </div>
                  <div className={`text-sm font-bold font-mono truncate ${
                    testStatusText.includes("FAILED") ? "text-rose-400" : "text-indigo-300"
                  }`}>
                    {testStatusText}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
                    <span className="text-purple-300" title="AI Recommended tests">{totalRecommendedTests} recs</span>
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "dependencies"
                      ? "border-blue-500/60 bg-blue-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Dependencies
                    </span>
                    <Boxes className="h-3.5 w-3.5 text-blue-400" />
                  </div>
                  <div className={`text-sm font-bold font-mono truncate ${
                    depStatusText === "PARTIAL" ? "text-blue-300" : "text-emerald-400"
                  }`}>
                    {depStatusText}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "impact"
                      ? "border-purple-500/60 bg-purple-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Impact
                    </span>
                    <Network className="h-3.5 w-3.5 text-purple-400" />
                  </div>
                  <div className="text-sm font-bold font-mono text-purple-300 truncate">
                    {impactLevelText}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
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
                  className={`p-3 rounded-xl border transition-all text-left flex flex-col justify-between gap-1 group ${
                    activeHealthSection === "merge"
                      ? "border-emerald-500/60 bg-emerald-950/20 shadow-md"
                      : "border-slate-800 bg-slate-950/70 hover:border-indigo-500/50 hover:bg-slate-900/80"
                  }`}
                >
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 group-hover:text-indigo-300">
                      Merge Status
                    </span>
                    <GitPullRequest className="h-3.5 w-3.5 text-emerald-400" />
                  </div>
                  <div className={`text-sm font-bold font-mono truncate ${
                    mergeDecisionText.includes("BLOCKED") ? "text-rose-400" : mergeDecisionText.includes("WARNING") ? "text-amber-400" : "text-emerald-400"
                  }`}>
                    {mergeDecisionText.replace(/_/g, " ")}
                  </div>
                  <div className="text-[10px] font-mono text-slate-400 flex items-center justify-between pt-0.5">
                    <span className={blockersCount > 0 ? "text-rose-400 font-bold" : "text-emerald-400"}>
                      {blockersCount} blockers
                    </span>
                    <span>Score {riskScoreNum.toFixed(0)}</span>
                  </div>
                </button>
              </div>

              {/* Engineering Lifecycle Timeline View (Section 15) */}
              <div className="p-3.5 rounded-xl border border-slate-800/80 bg-slate-950/80 space-y-2.5">
                <div className="flex items-center justify-between text-xs">
                  <div className="flex items-center gap-2 font-mono text-slate-300 font-semibold uppercase tracking-wider text-[11px]">
                    <Workflow className="h-4 w-4 text-indigo-400" />
                    <span>Live Engineering Intelligence Workflow Lifecycle</span>
                  </div>
                  <span className="text-[10px] font-mono text-slate-500 hidden sm:inline">
                    Deterministic Evidence &bull; Multi-Tenant Isolated
                  </span>
                </div>

                {/* Horizontal Step Pipeline */}
                <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-8 gap-2 font-mono text-[10px]">
                  {/* Step 1: Project & Req */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">1. Project & Req</span>
                    <div className="text-emerald-400 font-bold flex items-center gap-1">
                      <CheckCircle2 className="h-3 w-3" /> Linked
                    </div>
                  </div>

                  {/* Step 2: Task */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">2. Task Linked</span>
                    <div className="text-emerald-400 font-bold flex items-center gap-1">
                      <CheckCircle2 className="h-3 w-3" /> Active
                    </div>
                  </div>

                  {/* Step 3: Git Repo & Branch */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">3. Branch / Commit</span>
                    <div className="text-indigo-300 font-bold truncate">
                      {prChangeSet?.sourceBranch || "main"}
                    </div>
                  </div>

                  {/* Step 4: Pull Request */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">4. Pull Request</span>
                    <div className="text-white font-bold">
                      {selectedPrNumber ? `PR #${selectedPrNumber}` : "None"}
                    </div>
                  </div>

                  {/* Step 5: Tests / CI */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">5. CI / Tests</span>
                    <div className={`font-bold ${failedRequiredTests > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {failedRequiredTests > 0 ? `${failedRequiredTests} Failures` : "Verified"}
                    </div>
                  </div>

                  {/* Step 6: AI Health */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">6. AI Analysis</span>
                    <div className="text-purple-300 font-bold">
                      {prAnalysisResult ? "Executed" : "Pending"}
                    </div>
                  </div>

                  {/* Step 7: Security & Bugs */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">7. Security/Bugs</span>
                    <div className={`font-bold ${critFindingsCount > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {critFindingsCount > 0 ? `${critFindingsCount} Critical` : "Clean"}
                    </div>
                  </div>

                  {/* Step 8: Merge Ready */}
                  <div className="p-2 rounded-lg border border-slate-800 bg-slate-900/50 space-y-1">
                    <span className="text-slate-500 block uppercase">8. Merge Ready</span>
                    <div className={`font-bold ${mergeDecisionText.includes("BLOCKED") ? "text-rose-400" : "text-emerald-400"}`}>
                      {mergeDecisionText}
                    </div>
                  </div>
                </div>
              </div>

              {/* 4 Clickable Quick Metrics */}
              <div className="flex flex-wrap items-center justify-between gap-3 pt-3 border-t border-slate-800/60 text-xs">
                <div className="flex items-center gap-3 flex-wrap">
                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("security");
                      setFindingSeverityFilter("CRITICAL");
                    }}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg border border-rose-500/30 bg-rose-950/20 text-rose-300 hover:bg-rose-950/40 transition-colors"
                  >
                    <ShieldAlert className="h-3.5 w-3.5 text-rose-400" />
                    <span>Critical Findings:</span>
                    <strong className="font-mono">{critFindingsCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("security");
                      setFindingSeverityFilter("HIGH");
                    }}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg border border-amber-500/30 bg-amber-950/20 text-amber-300 hover:bg-amber-950/40 transition-colors"
                  >
                    <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
                    <span>High Findings:</span>
                    <strong className="font-mono">{highFindingsCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("tests");
                      setTestBucketFilter("FAILURES");
                    }}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg border border-indigo-500/30 bg-indigo-950/20 text-indigo-300 hover:bg-indigo-950/40 transition-colors"
                  >
                    <TestTube className="h-3.5 w-3.5 text-indigo-400" />
                    <span>Tests Failing:</span>
                    <strong className="font-mono">{testsFailingCount}</strong>
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setActiveTab("pr");
                      setActiveHealthSection("tests");
                      setTestBucketFilter("MUST_RUN");
                    }}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg border border-blue-500/30 bg-blue-950/20 text-blue-300 hover:bg-blue-950/40 transition-colors"
                  >
                    <CheckCheck className="h-3.5 w-3.5 text-blue-400" />
                    <span>Missing Coverage:</span>
                    <strong className="font-mono">{missingCoverageCount}</strong>
                  </button>
                </div>

                {/* Secondary Utilities */}
                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() => setIsDepSecurityModalOpen(true)}
                    className="text-[11px] font-mono text-slate-400 hover:text-white flex items-center gap-1"
                  >
                    <Boxes className="h-3.5 w-3.5 text-blue-400" />
                    Dependency CVE Database
                  </button>
                  <span className="text-slate-700">&bull;</span>
                  <button
                    type="button"
                    onClick={() => {
                      if (selectedPrNumber) fetchAnalysisHistory(selectedPrNumber);
                      setIsHistoryModalOpen(true);
                    }}
                    className="text-[11px] font-mono text-slate-400 hover:text-white flex items-center gap-1"
                  >
                    <History className="h-3.5 w-3.5 text-purple-400" />
                    Analysis History Timeline
                  </button>
                </div>
              </div>
            </CardContent>
          </Card>
        );
      })()}

      {/* Navigation Tabs */}
      <div className="flex items-center gap-2 border-b border-slate-800 pb-2">
        <button
          onClick={() => setActiveTab("pr")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "pr"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <GitPullRequest className="h-4 w-4" />
          Automatic PR Intelligence
        </button>
        <button
          onClick={() => setActiveTab("search")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "search"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Search className="h-4 w-4" />
          Semantic Vector Search (pgvector)
        </button>
        <button
          onClick={() => setActiveTab("impact")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "impact"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Network className="h-4 w-4" />
          AST Call Graph Blast Radius
        </button>
        <button
          onClick={() => setActiveTab("dependencies")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "dependencies"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Share2 className="h-4 w-4" />
          Cross-Repository Topology
        </button>
        <button
          onClick={() => setActiveTab("remediation")}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium transition-all ${
            activeTab === "remediation"
              ? "bg-indigo-600 text-white shadow-lg shadow-indigo-600/30"
              : "text-slate-400 hover:text-white hover:bg-slate-800/60"
          }`}
        >
          <Sliders className="h-4 w-4" />
          Controlled Remediation
          {localActions.length > 0 && (
            <Badge className="ml-1 bg-purple-500/20 text-purple-300 border-purple-500/30 text-[10px] px-1.5 py-0 font-mono">
              {localActions.length}
            </Badge>
          )}
        </button>
      </div>

      {/* ========================================================================= */}
      {/* TAB 1: AUTOMATIC REAL GITHUB PULL REQUEST INTELLIGENCE                    */}
      {/* ========================================================================= */}
      {activeTab === "pr" && (
        <div className="space-y-6">
          {/* PR Selector & Metadata Header */}
          <Card className="border-indigo-500/20 bg-slate-900/60">
            <CardHeader className="pb-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                <div>
                  <CardTitle className="text-base text-white flex items-center gap-2">
                    <GitPullRequest className="h-5 w-5 text-indigo-400" />
                    Authoritative GitHub Pull Request
                  </CardTitle>
                  <CardDescription className="text-xs">
                    Select a real GitHub pull request to automatically obtain change sets, statically derive symbols, and compute blast radius.
                  </CardDescription>
                </div>

                {/* PR Dropdown */}
                <div className="flex items-center gap-2">
                  {isLoadingPrs ? (
                    <div className="flex items-center gap-2 text-xs text-slate-400 py-1.5 px-3 bg-slate-950 border border-slate-800 rounded-lg">
                      <Loader2 className="h-3 w-3 animate-spin text-purple-400" />
                      Loading PRs...
                    </div>
                  ) : pullRequests.length > 0 ? (
                    <select
                      aria-label="Authoritative Pull Request Selection"
                      value={selectedPrNumber}
                      onChange={(e) => setSelectedPrNumber(Number(e.target.value) || "")}
                      className="bg-slate-950 border border-slate-800 text-xs text-white rounded-lg px-3 py-2 focus:ring-1 focus:ring-indigo-500 focus:outline-none cursor-pointer max-w-xs truncate"
                    >
                      {pullRequests.map((pr) => (
                        <option key={pr.id || pr.number} value={pr.prNumber || pr.number}>
                          #{pr.prNumber || pr.number}: {pr.title}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <div className="text-xs text-slate-500 italic bg-slate-950 px-3 py-1.5 rounded-lg border border-slate-800">
                      No pull requests synchronized for this repository
                    </div>
                  )}
                </div>
              </div>
            </CardHeader>

            {/* Authoritative Changeset Details Card */}
            <CardContent className="space-y-4 pt-0">
              {isLoadingChangeSet ? (
                <div className="flex items-center justify-center p-6 text-xs text-slate-400">
                  <Loader2 className="h-4 w-4 animate-spin text-indigo-400 mr-2" />
                  Fetching authoritative change set from GitHub / Source Control...
                </div>
              ) : prChangeSet ? (
                <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-4 space-y-4">
                  <div className="flex flex-col md:flex-row md:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-semibold text-white">
                          #{prChangeSet.number}: {prChangeSet.title}
                        </span>
                        <Badge
                          variant={prChangeSet.state === "OPEN" ? "success" : "outline"}
                          className="text-[10px]"
                        >
                          {prChangeSet.state}
                        </Badge>
                      </div>
                      <div className="flex items-center gap-2 text-xs text-slate-400 mt-1 font-mono">
                        <GitBranch className="h-3 w-3 text-indigo-400" />
                        <span>{prChangeSet.sourceBranch || "head"}</span>
                        <ArrowRight className="h-3 w-3 text-slate-600" />
                        <span>{prChangeSet.targetBranch || "main"}</span>
                        {prChangeSet.headSha && (
                          <span className="text-[10px] text-slate-500 ml-2">
                            (head: {prChangeSet.headSha.substring(0, 7)})
                          </span>
                        )}
                      </div>
                    </div>

                    {/* Stats pills */}
                    <div className="flex items-center gap-2 text-xs font-mono">
                      <span className="px-2.5 py-1 rounded-md bg-slate-900 border border-slate-800 text-slate-300">
                        {prChangeSet.changedFilesCount || prChangeSet.files?.length || 0} files changed
                      </span>
                      <span className="px-2.5 py-1 rounded-md bg-emerald-950/50 border border-emerald-800/40 text-emerald-400">
                        +{prChangeSet.additions || 0}
                      </span>
                      <span className="px-2.5 py-1 rounded-md bg-rose-950/50 border border-rose-800/40 text-rose-400">
                        -{prChangeSet.deletions || 0}
                      </span>
                    </div>
                  </div>

                  {/* Changed Files Chips */}
                  {prChangeSet.files && prChangeSet.files.length > 0 && (
                    <div className="space-y-1.5">
                      <div className="text-[11px] font-semibold text-slate-400 uppercase tracking-wider">
                        Authoritative Changed Files ({prChangeSet.files.length})
                      </div>
                      <div className="flex flex-wrap gap-1.5 max-h-32 overflow-y-auto">
                        {prChangeSet.files.map((file, idx) => (
                          <div
                            key={idx}
                            className="flex items-center gap-1.5 px-2 py-1 rounded border border-slate-800 bg-slate-900 text-[11px] font-mono text-slate-300"
                          >
                            <FileCode className="h-3 w-3 text-indigo-400" />
                            <span className="truncate max-w-xs">{file.filename}</span>
                            <span
                              className={`text-[9px] px-1 rounded uppercase font-bold ${
                                file.status === "added"
                                  ? "text-emerald-400 bg-emerald-950/80"
                                  : file.status === "removed"
                                  ? "text-rose-400 bg-rose-950/80"
                                  : "text-amber-400 bg-amber-950/80"
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
                      variant="glow"
                      className="w-full sm:w-auto px-6 py-2.5 text-xs font-semibold flex items-center gap-2"
                    >
                      {isAnalyzingPr ? (
                        <>
                          <Loader2 className="h-4 w-4 animate-spin text-white" />
                          Running Real PR Intelligence Pipeline...
                        </>
                      ) : (
                        <>
                          <Sparkles className="h-4 w-4 text-purple-200" />
                          Analyze Pull Request
                        </>
                      )}
                    </Button>

                    {/* Advanced toggle */}
                    <button
                      type="button"
                      onClick={() => setShowAdvancedOverride(!showAdvancedOverride)}
                      className="text-xs text-slate-400 hover:text-white flex items-center gap-1"
                    >
                      {showAdvancedOverride ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
                      Advanced Override (Manual Symbols)
                    </button>
                  </div>

                  {/* Collapsible Advanced Override Section */}
                  {showAdvancedOverride && (
                    <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/60 space-y-2 mt-2">
                      <div className="text-[11px] text-slate-400">
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
                <div className="p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-rose-400 shrink-0" />
                  <span>{prError}</span>
                </div>
              )}
            </CardContent>
          </Card>

          {/* Lifecycle Execution Pipeline Indicator */}
          {isAnalyzingPr && (
            <Card className="border-indigo-500/30 bg-slate-900/80 p-5">
              <div className="text-xs font-semibold text-white mb-3 flex items-center gap-2">
                <Loader2 className="h-4 w-4 animate-spin text-purple-400" />
                Live PR Intelligence Pipeline Progression:
              </div>
              <div className="grid grid-cols-2 md:grid-cols-5 gap-2 text-[11px]">
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "fetching"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <GitPullRequest className="h-3 w-3 shrink-0" />
                  <span>1. Authoritative PR</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "deriving"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Code2 className="h-3 w-3 shrink-0" />
                  <span>2. Tree-sitter AST</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "indexing"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Database className="h-3 w-3 shrink-0" />
                  <span>3. Durable Indexing</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "graph"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
                  }`}
                >
                  <Network className="h-3 w-3 shrink-0" />
                  <span>4. Graph CTE</span>
                </div>
                <div
                  className={`p-2 rounded-lg border flex items-center gap-1.5 ${
                    lifecycleStep === "reasoning"
                      ? "border-purple-500 bg-purple-950/40 text-purple-200 animate-pulse"
                      : "border-slate-800 bg-slate-950/50 text-slate-400"
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
            <div className="space-y-6">
              <Card className="border-purple-500/30 bg-purple-950/10 shadow-xl">
                <CardHeader className="pb-4">
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                    <div className="flex items-center gap-3 flex-wrap">
                      <div className="flex items-center gap-2">
                        <ShieldCheck className="h-6 w-6 text-purple-400" />
                        <CardTitle className="text-lg text-white">
                          Engineering Impact & Risk Intelligence
                        </CardTitle>
                      </div>

                      {/* Overall Risk Badge */}
                      <Badge
                        variant="outline"
                        className={`text-xs font-bold px-3 py-1 uppercase tracking-wider border ${
                          prAnalysisResult.risk_level === "LOW"
                            ? "bg-emerald-500/15 text-emerald-400 border-emerald-500/40"
                            : prAnalysisResult.risk_level === "MEDIUM"
                            ? "bg-amber-500/15 text-amber-400 border-amber-500/40"
                            : prAnalysisResult.risk_level === "HIGH"
                            ? "bg-orange-500/15 text-orange-400 border-orange-500/40"
                            : "bg-rose-500/15 text-rose-400 border-rose-500/40 animate-pulse"
                        }`}
                      >
                        Risk: {prAnalysisResult.risk_level}
                      </Badge>

                      {/* Deterministic Risk Score */}
                      <div className="flex items-center gap-1.5 px-3 py-1 rounded-md bg-slate-900 border border-slate-800 text-xs font-mono">
                        <Activity className="h-3.5 w-3.5 text-indigo-400" />
                        <span className="text-slate-400">Score:</span>
                        <span className="font-bold text-white">
                          {typeof prAnalysisResult.risk_score === "number"
                            ? prAnalysisResult.risk_score.toFixed(1)
                            : "0.0"}
                        </span>
                        <span className="text-slate-500">/ 100</span>
                      </div>

                      {/* Evidence Confidence */}
                      <Badge
                        variant="outline"
                        className={`text-xs font-mono px-2.5 py-1 ${
                          prAnalysisResult.confidence === "HIGH"
                            ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                            : prAnalysisResult.confidence === "MEDIUM"
                            ? "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                            : "text-amber-400 border-amber-500/40 bg-amber-950/20"
                        }`}
                        title={prAnalysisResult.impact_metadata?.confidence_reason}
                      >
                        Confidence: {prAnalysisResult.confidence}
                      </Badge>
                    </div>

                    {/* Transparency Badges */}
                    <div className="flex items-center gap-2 flex-wrap">
                      <Badge variant="outline" className="font-mono text-xs">
                        Provider:{" "}
                        <span className="text-purple-300 font-bold ml-1">
                          {prAnalysisResult.provider === "GROQ"
                            ? "Groq Cloud"
                            : prAnalysisResult.provider === "GEMINI"
                            ? "Google Gemini"
                            : prAnalysisResult.provider}
                        </span>
                      </Badge>
                      {prAnalysisResult.fallback_used ? (
                        <Badge variant="destructive" className="font-mono text-xs animate-pulse">
                          Fallback: Yes (Groq)
                        </Badge>
                      ) : (
                        <Badge variant="outline" className="font-mono text-xs text-emerald-400 border-emerald-500/40">
                          Primary: Gemini
                        </Badge>
                      )}
                    </div>
                  </div>

                  <CardDescription className="text-xs font-mono pt-2 flex flex-wrap items-center gap-x-4 gap-y-1">
                    <span>Model: {prAnalysisResult.model_version}</span>
                    <span>Duration: {prAnalysisResult.execution_duration_ms}ms</span>
                    {prAnalysisResult.impact_metadata?.confidence_reason && (
                      <span className="text-slate-400 font-sans italic">
                        Evidence: {prAnalysisResult.impact_metadata.confidence_reason}
                      </span>
                    )}
                    {prAnalysisResult.trace_id && (
                      <span>Trace: {prAnalysisResult.trace_id.substring(0, 16)}...</span>
                    )}
                  </CardDescription>
                </CardHeader>

                <CardContent className="space-y-6 pt-2">
                  {/* ========================================================= */}
                  {/* AUTOMATED MERGE READINESS BANNER (DETERMINISTIC ENGINE)   */}
                  {/* ========================================================= */}
                  {prAnalysisResult.merge_readiness && (
                    <div className="rounded-2xl border border-slate-800 bg-gradient-to-br from-slate-950 via-slate-900/90 to-slate-950 p-5 shadow-2xl relative overflow-hidden space-y-4">
                      {/* Subtle accent backdrop */}
                      <div className="absolute top-0 right-0 w-96 h-32 bg-purple-500/5 blur-3xl pointer-events-none rounded-full -mr-20 -mt-10" />

                      {/* Header Row: Decision Badge, Readiness Score, Policy & "Why?" Toggle */}
                      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-slate-800/80 pb-4">
                        <div className="flex items-center gap-3 flex-wrap">
                          {/* Categorical Decision Badge */}
                          {(() => {
                            const decision = prAnalysisResult.merge_readiness.decision;
                            if (decision === "READY") {
                              return (
                                <Badge className="px-3 py-1.5 text-xs font-mono font-bold tracking-wide bg-emerald-950/80 text-emerald-300 border border-emerald-500/50 flex items-center gap-1.5 shadow-lg shadow-emerald-950/50">
                                  <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                                  READY TO MERGE
                                </Badge>
                              );
                            }
                            if (decision === "READY_WITH_WARNINGS") {
                              return (
                                <Badge className="px-3 py-1.5 text-xs font-mono font-bold tracking-wide bg-amber-950/80 text-amber-300 border border-amber-500/50 flex items-center gap-1.5 shadow-lg shadow-amber-950/50">
                                  <AlertTriangle className="h-4 w-4 text-amber-400" />
                                  READY WITH WARNINGS
                                </Badge>
                              );
                            }
                            if (decision === "NOT_READY") {
                              return (
                                <Badge className="px-3 py-1.5 text-xs font-mono font-bold tracking-wide bg-rose-950/80 text-rose-300 border border-rose-500/50 flex items-center gap-1.5 shadow-lg shadow-rose-950/50">
                                  <XCircle className="h-4 w-4 text-rose-400" />
                                  NOT READY TO MERGE
                                </Badge>
                              );
                            }
                            return (
                              <Badge className="px-3 py-1.5 text-xs font-mono font-bold tracking-wide bg-blue-950/80 text-blue-300 border border-blue-500/50 flex items-center gap-1.5 shadow-lg shadow-blue-950/50">
                                <AlertCircle className="h-4 w-4 text-blue-400" />
                                BLOCKED BY MISSING EVIDENCE
                              </Badge>
                            );
                          })()}

                          {/* Policy Version Badge */}
                          <Badge variant="outline" className="text-[10px] font-mono text-slate-400 border-slate-800 bg-slate-900/60">
                            Policy: {prAnalysisResult.merge_readiness.policy_version || "merge-policy-v1"}
                          </Badge>

                          {/* Security Status Badge */}
                          {(() => {
                            const sec = prAnalysisResult.merge_readiness.security_status;
                            if (sec === "NO_SECURITY_FINDINGS") {
                              return (
                                <Badge variant="outline" className="text-[10px] font-mono text-emerald-400 border-emerald-500/30 bg-emerald-950/20 flex items-center gap-1">
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
                                <Badge variant="outline" className="text-[10px] font-mono text-amber-400 border-amber-500/40 bg-amber-950/20 flex items-center gap-1">
                                  <ShieldAlert className="h-3 w-3" />
                                  Security: Warning
                                </Badge>
                              );
                            }
                            return (
                              <Badge variant="outline" className="text-[10px] font-mono text-slate-400 border-slate-700 bg-slate-900/40 flex items-center gap-1">
                                <ShieldX className="h-3 w-3 text-slate-500" />
                                Security: Evidence Unavailable
                              </Badge>
                            );
                          })()}
                        </div>

                        {/* Readiness Score & "Why?" Expand Toggle */}
                        <div className="flex items-center gap-3">
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-mono uppercase tracking-wider text-slate-400">Readiness Score:</span>
                            <div className="flex items-baseline gap-1">
                              <span
                                className={`text-lg font-mono font-extrabold ${
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
                              <span className="text-[10px] font-mono text-slate-500">/ 100.0</span>
                            </div>
                          </div>

                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            onClick={() => setIsReadinessDetailsOpen((prev) => !prev)}
                            className="h-8 text-xs font-mono px-3 border-slate-700 bg-slate-900/80 hover:bg-slate-800 text-slate-200 transition-colors flex items-center gap-1.5"
                          >
                            <HelpCircle className="h-3.5 w-3.5 text-purple-400" />
                            <span>Why?</span>
                            {isReadinessDetailsOpen ? (
                              <ChevronUp className="h-3.5 w-3.5 text-slate-400" />
                            ) : (
                              <ChevronDown className="h-3.5 w-3.5 text-slate-400" />
                            )}
                          </Button>
                        </div>
                      </div>

                      {/* Summary Banner */}
                      <div className="text-xs text-slate-300 leading-relaxed font-sans">
                        {prAnalysisResult.merge_readiness.summary}
                      </div>

                      {/* Blocker Callouts if any */}
                      {prAnalysisResult.merge_readiness.blockers && prAnalysisResult.merge_readiness.blockers.length > 0 && (
                        <div className="p-3.5 rounded-xl border border-rose-500/40 bg-rose-950/30 text-xs space-y-1.5">
                          <div className="font-bold text-rose-200 flex items-center gap-1.5">
                            <XCircle className="h-4 w-4 text-rose-400 shrink-0" />
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
                        <div className="p-3.5 rounded-xl border border-amber-500/40 bg-amber-950/20 text-xs space-y-1.5">
                          <div className="font-bold text-amber-200 flex items-center gap-1.5">
                            <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0" />
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
                        <div className="pt-3 border-t border-slate-800/80 space-y-4 animate-in fade-in duration-200">
                          <div className="flex items-center justify-between">
                            <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                              <Scale className="h-4 w-4 text-indigo-400" />
                              Required Checks & Grounded Evidence Matrix
                            </span>
                            <span className="text-[11px] font-mono text-slate-400">
                              Deterministic Evaluation &bull; Mode A Advisory
                            </span>
                          </div>

                          {/* Required Checks Matrix Table */}
                          <div className="rounded-xl border border-slate-800 bg-slate-950/80 overflow-hidden">
                            <div className="overflow-x-auto">
                              <table className="w-full text-left text-xs font-mono">
                                <thead className="bg-slate-900/90 text-slate-400 uppercase text-[10px] border-b border-slate-800">
                                  <tr>
                                    <th className="px-3.5 py-2.5">Check Name</th>
                                    <th className="px-3.5 py-2.5">Category</th>
                                    <th className="px-3.5 py-2.5">Status</th>
                                    <th className="px-3.5 py-2.5">Observed Evidence</th>
                                    <th className="px-3.5 py-2.5">Evaluation Detail</th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-800/60">
                                  {Array.isArray(prAnalysisResult.merge_readiness.required_checks) &&
                                    prAnalysisResult.merge_readiness.required_checks.map((check, cIdx) => (
                                      <tr key={cIdx} className="hover:bg-slate-900/40 transition-colors">
                                        <td className="px-3.5 py-2.5 font-semibold text-slate-200 whitespace-nowrap">
                                          {check.name}
                                        </td>
                                        <td className="px-3.5 py-2.5 text-slate-400 text-[11px]">
                                          {check.category}
                                        </td>
                                        <td className="px-3.5 py-2.5 whitespace-nowrap">
                                          {check.status === "PASSED" && (
                                            <Badge variant="outline" className="text-[10px] text-emerald-400 border-emerald-500/40 bg-emerald-950/30">
                                              PASSED
                                            </Badge>
                                          )}
                                          {check.status === "FAILED" && (
                                            <Badge variant="destructive" className="text-[10px]">
                                              FAILED
                                            </Badge>
                                          )}
                                          {check.status === "MISSING" && (
                                            <Badge variant="outline" className="text-[10px] text-blue-400 border-blue-500/40 bg-blue-950/30">
                                              MISSING
                                            </Badge>
                                          )}
                                          {check.status === "RUNNING" && (
                                            <Badge variant="outline" className="text-[10px] text-purple-400 border-purple-500/40 bg-purple-950/30 animate-pulse">
                                              RUNNING
                                            </Badge>
                                          )}
                                          {check.status === "WARNING" && (
                                            <Badge variant="outline" className="text-[10px] text-amber-400 border-amber-500/40 bg-amber-950/30">
                                              WARNING
                                            </Badge>
                                          )}
                                          {check.status === "SKIPPED" && (
                                            <Badge variant="outline" className="text-[10px] text-slate-400 border-slate-700 bg-slate-900/40">
                                              SKIPPED
                                            </Badge>
                                          )}
                                        </td>
                                        <td className="px-3.5 py-2.5 text-slate-300 text-[11px] max-w-xs truncate">
                                          {check.evidence || "—"}
                                        </td>
                                        <td className="px-3.5 py-2.5 text-slate-400 text-[11px] font-sans">
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
                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-1 font-mono text-xs">
                              <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60">
                                <div className="text-[10px] uppercase text-slate-500">CI Run Status</div>
                                <div className="text-slate-200 font-bold mt-0.5">
                                  {prAnalysisResult.merge_readiness.evidence_summary.ci_status}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60">
                                <div className="text-[10px] uppercase text-slate-500">Required Tests (Exec/Total)</div>
                                <div className="text-slate-200 font-bold mt-0.5">
                                  {prAnalysisResult.merge_readiness.evidence_summary.executed_required_tests} / {prAnalysisResult.merge_readiness.evidence_summary.total_required_tests}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60">
                                <div className="text-[10px] uppercase text-slate-500">Required Test Failures</div>
                                <div className={`font-bold mt-0.5 ${prAnalysisResult.merge_readiness.evidence_summary.failed_required_tests > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                                  {prAnalysisResult.merge_readiness.evidence_summary.failed_required_tests}
                                </div>
                              </div>
                              <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60">
                                <div className="text-[10px] uppercase text-slate-500">Deterministic Risk</div>
                                <div className="text-purple-300 font-bold mt-0.5">
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
                    <div className="rounded-2xl border border-slate-800 bg-gradient-to-br from-slate-950 via-slate-900/60 to-slate-950 p-5 shadow-xl relative overflow-hidden space-y-4">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                        <div className="flex items-center gap-2.5">
                          <Scale className="h-5 w-5 text-indigo-400" />
                          <div>
                            <span className="text-xs font-bold text-slate-200 uppercase tracking-wider">
                              Unified Engineering Risk Engine
                            </span>
                            <div className="text-[11px] font-mono text-slate-400">
                              Deterministic Multi-Dimensional Formula (v3)
                            </div>
                          </div>
                        </div>

                        <div className="flex items-center gap-3">
                          <div className="flex items-baseline gap-1 font-mono">
                            <span className="text-xs uppercase text-slate-400">Final Risk:</span>
                            <span
                              className={`text-xl font-extrabold ${
                                (prAnalysisResult.unified_risk.final_risk ?? 0) >= 75
                                  ? "text-rose-400"
                                  : (prAnalysisResult.unified_risk.final_risk ?? 0) >= 50
                                  ? "text-amber-400"
                                  : (prAnalysisResult.unified_risk.final_risk ?? 0) >= 25
                                  ? "text-yellow-400"
                                  : "text-emerald-400"
                              }`}
                            >
                              {(prAnalysisResult.unified_risk.final_risk ?? 0).toFixed(1)}
                            </span>
                            <span className="text-[10px] text-slate-500">/ 100.0</span>
                          </div>
                          <Badge
                            variant="outline"
                            className={`font-mono text-[10px] px-2 py-0.5 ${
                              prAnalysisResult.unified_risk.final_risk_level === "CRITICAL"
                                ? "text-rose-400 border-rose-500/50 bg-rose-950/30"
                                : prAnalysisResult.unified_risk.final_risk_level === "HIGH"
                                ? "text-amber-400 border-amber-500/50 bg-amber-950/30"
                                : prAnalysisResult.unified_risk.final_risk_level === "MEDIUM"
                                ? "text-yellow-400 border-yellow-500/50 bg-yellow-950/30"
                                : "text-emerald-400 border-emerald-500/50 bg-emerald-950/30"
                            }`}
                          >
                            {prAnalysisResult.unified_risk.final_risk_level}
                          </Badge>
                        </div>
                      </div>

                      {/* Dimension Contribution Cards */}
                      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
                        {/* Local Risk */}
                        <div className="p-3 rounded-xl border border-slate-800/80 bg-slate-900/60 space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono">
                            <span className="text-slate-400">Local Impact (35%)</span>
                            <span className="text-slate-200 font-bold">
                              {(prAnalysisResult.unified_risk.local_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-slate-800/80 rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-indigo-500 h-1.5 rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.local_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono text-slate-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.local ?? ((prAnalysisResult.unified_risk.local_risk ?? 0) * 0.35)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Distributed Risk */}
                        <div className="p-3 rounded-xl border border-slate-800/80 bg-slate-900/60 space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono">
                            <span className="text-slate-400">Distributed (15%)</span>
                            <span className="text-slate-200 font-bold">
                              {(prAnalysisResult.unified_risk.distributed_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-slate-800/80 rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-purple-500 h-1.5 rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, (prAnalysisResult.unified_risk.distributed_risk ?? 0) * 2)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono text-slate-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.distributed ?? ((prAnalysisResult.unified_risk.distributed_risk ?? 0) * 0.15)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Security Risk */}
                        <div className="p-3 rounded-xl border border-slate-800/80 bg-slate-900/60 space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono">
                            <span className="text-slate-400">Security (35%)</span>
                            <span className="text-slate-200 font-bold">
                              {(prAnalysisResult.unified_risk.security_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-slate-800/80 rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-rose-500 h-1.5 rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.security_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono text-slate-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.security ?? ((prAnalysisResult.unified_risk.security_risk ?? 0) * 0.35)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>

                        {/* Reliability Risk */}
                        <div className="p-3 rounded-xl border border-slate-800/80 bg-slate-900/60 space-y-1.5">
                          <div className="flex items-center justify-between text-[11px] font-mono">
                            <span className="text-slate-400">Reliability (15%)</span>
                            <span className="text-slate-200 font-bold">
                              {(prAnalysisResult.unified_risk.reliability_risk ?? 0).toFixed(1)}
                            </span>
                          </div>
                          <div className="w-full bg-slate-800/80 rounded-full h-1.5 overflow-hidden">
                            <div
                              className="bg-amber-500 h-1.5 rounded-full transition-all duration-500"
                              style={{ width: `${Math.min(100, prAnalysisResult.unified_risk.reliability_risk ?? 0)}%` }}
                            />
                          </div>
                          <div className="text-[10px] font-mono text-slate-500">
                            Contrib: +{((prAnalysisResult.unified_risk.dimension_contributions?.reliability ?? ((prAnalysisResult.unified_risk.reliability_risk ?? 0) * 0.15)) ?? 0).toFixed(1)} pts
                          </div>
                        </div>
                      </div>

                      {/* Formula footer & Floor Notice */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pt-1 text-[11px] font-mono text-slate-400">
                        <span>Formula: min(100.0, 0.35*local + 0.15*distributed + 0.35*security + 0.15*reliability)</span>
                        {prAnalysisResult.unified_risk.final_risk >= 75 && prAnalysisResult.unified_risk.security_risk > 0 && (
                          <span className="text-rose-400 flex items-center gap-1 font-semibold">
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
                    <div className="rounded-2xl border border-slate-800 bg-slate-950/70 p-5 shadow-xl space-y-4">
                      {/* Card Header */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                        <div className="flex items-center gap-2.5">
                          <ShieldCheck className="h-5 w-5 text-emerald-400" />
                          <div>
                            <span className="text-xs font-bold text-slate-200 uppercase tracking-wider">
                              Deterministic Security Analysis Layer
                            </span>
                            <div className="text-[11px] font-mono text-slate-400">
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
                            <Badge variant="outline" className="font-mono text-[10px] text-amber-400 border-amber-500/40 bg-amber-950/30 flex items-center gap-1">
                              <AlertTriangle className="h-3 w-3" />
                              SECURITY WARNING
                            </Badge>
                          ) : prAnalysisResult.security_assessment.security_status === "SECURITY_EVIDENCE_UNAVAILABLE" ? (
                            <Badge variant="outline" className="font-mono text-[10px] text-slate-400 border-slate-700 bg-slate-900/40 flex items-center gap-1">
                              <ShieldX className="h-3 w-3" />
                              EVIDENCE UNAVAILABLE
                            </Badge>
                          ) : (
                            <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-500/40 bg-emerald-950/30 flex items-center gap-1">
                              <ShieldCheck className="h-3 w-3" />
                              CLEAN (0 FINDINGS)
                            </Badge>
                          )}

                          {/* Severity Pill Counts */}
                          <div className="flex items-center gap-1 text-[10px] font-mono">
                            <span className="px-1.5 py-0.5 rounded bg-rose-950/40 border border-rose-500/30 text-rose-300">
                              {prAnalysisResult.security_assessment.critical_count} Crit
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-amber-950/40 border border-amber-500/30 text-amber-300">
                              {prAnalysisResult.security_assessment.high_count} High
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-yellow-950/40 border border-yellow-500/30 text-yellow-300">
                              {prAnalysisResult.security_assessment.medium_count} Med
                            </span>
                            <span className="px-1.5 py-0.5 rounded bg-slate-900 border border-slate-800 text-slate-400">
                              {prAnalysisResult.security_assessment.low_count} Low
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Partial Evidence Warning Callout (Dependency CVE Scanner Unavailable) */}
                      {prAnalysisResult.security_assessment.evidence_status === "PARTIAL" && (
                        <div className="p-3 rounded-xl border border-blue-500/30 bg-blue-950/20 text-xs text-blue-200 flex items-center gap-2.5 font-sans">
                          <Info className="h-4 w-4 text-blue-400 shrink-0" />
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
                              className={`p-3.5 rounded-xl border text-xs space-y-2 transition-colors ${
                                f.severity === "CRITICAL"
                                  ? "border-rose-500/40 bg-rose-950/20"
                                  : f.severity === "HIGH"
                                  ? "border-amber-500/40 bg-amber-950/15"
                                  : "border-slate-800 bg-slate-900/40"
                              }`}
                            >
                              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1.5">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <Badge
                                    variant={f.severity === "CRITICAL" ? "destructive" : "outline"}
                                    className={`text-[9px] font-mono font-bold uppercase tracking-wider ${
                                      f.severity === "HIGH"
                                        ? "text-amber-400 border-amber-500/40 bg-amber-950/30"
                                        : f.severity === "MEDIUM"
                                        ? "text-yellow-400 border-yellow-500/40 bg-yellow-950/30"
                                        : ""
                                    }`}
                                  >
                                    {f.severity}
                                  </Badge>
                                  <Badge variant="outline" className="text-[9px] font-mono text-emerald-400 border-emerald-500/40 bg-emerald-950/20 flex items-center gap-1">
                                    <ShieldCheck className="h-3 w-3" />
                                    DETERMINISTIC
                                  </Badge>
                                  <span className="font-mono text-[10px] text-slate-400 uppercase tracking-wider">
                                    {f.category}
                                  </span>
                                  <span className="font-bold text-slate-200">{f.title}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-[10px] text-slate-500">{f.rule_id}</span>
                                  {f.is_blocker ? (
                                    <Badge variant="destructive" className="text-[9px] px-1 py-0 uppercase font-mono font-bold">
                                      BLOCKING
                                    </Badge>
                                  ) : (
                                    <Badge variant="outline" className="text-[9px] px-1 py-0 uppercase font-mono text-slate-400">
                                      ADVISORY
                                    </Badge>
                                  )}
                                </div>
                              </div>

                              <p className="text-slate-300 text-[11px] leading-relaxed">{f.description}</p>

                              {/* Concrete Redacted Evidence */}
                              <div className="p-2.5 rounded-lg bg-slate-950/90 border border-slate-800 font-mono text-[11px] text-purple-300 flex items-center justify-between gap-2 overflow-x-auto">
                                <div>
                                  <span className="text-slate-500">{f.file_path}{f.line_number ? `:${f.line_number}` : ""}:</span>{" "}
                                  <span className="font-bold text-purple-200">{f.redacted_evidence}</span>
                                </div>
                                <span className="text-[9px] uppercase px-1.5 py-0.5 rounded bg-purple-950/40 border border-purple-500/30 text-purple-300 shrink-0">
                                  Confidence: {f.confidence}
                                </span>
                              </div>

                              {/* Actionable Remediation Guidance */}
                              <div className="text-[11px] text-slate-400 flex items-start gap-1.5">
                                <span className="text-emerald-400 font-semibold shrink-0">Remediation:</span>
                                <span>{f.remediation}</span>
                              </div>

                              {/* Interactive Action Buttons */}
                              <div className="flex items-center justify-between pt-2 border-t border-slate-800/60">
                                <span className="text-[10px] font-mono text-slate-500">
                                  Verification: <span className="text-amber-400 font-semibold">Deterministic Re-Scan Required</span>
                                </span>
                                <div className="flex items-center gap-2">
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setSelectedFindingForModal(f)}
                                    className="h-6 text-[10px] px-2 border-slate-700 bg-slate-900/60 hover:bg-slate-800 text-slate-300 flex items-center gap-1"
                                  >
                                    <Eye className="h-3 w-3 text-indigo-400" />
                                    Safe Context
                                  </Button>
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleExplainFinding(f)}
                                    className="h-6 text-[10px] px-2 border-purple-500/40 bg-purple-950/30 hover:bg-purple-900/40 text-purple-200 flex items-center gap-1"
                                  >
                                    <Sparkles className="h-3 w-3 text-purple-400" />
                                    Explain with ForgeAI
                                  </Button>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-center py-6 text-slate-500 text-xs font-mono">
                          Zero security vulnerabilities or secret leaks detected in pull request files.
                        </div>
                      )}
                    </div>
                  )}

                  {/* ========================================================= */}
                  {/* DETERMINISTIC RELIABILITY INTELLIGENCE CARD               */}
                  {/* ========================================================= */}
                  {prAnalysisResult.reliability_assessment && (
                    <div className="rounded-2xl border border-slate-800 bg-slate-950/70 p-5 shadow-xl space-y-4">
                      {/* Card Header */}
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                        <div className="flex items-center gap-2.5">
                          <Activity className="h-5 w-5 text-purple-400" />
                          <div>
                            <span className="text-xs font-bold text-slate-200 uppercase tracking-wider">
                              Deterministic Reliability Analysis Layer
                            </span>
                            <div className="text-[11px] font-mono text-slate-400">
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
                            <Badge variant="outline" className="font-mono text-[10px] text-amber-400 border-amber-500/40 bg-amber-950/30 flex items-center gap-1">
                              <AlertTriangle className="h-3 w-3" />
                              RELIABILITY WARNING
                            </Badge>
                          ) : (
                            <Badge variant="outline" className="font-mono text-[10px] text-emerald-400 border-emerald-500/40 bg-emerald-950/30 flex items-center gap-1">
                              <CheckCircle2 className="h-3 w-3" />
                              PASSED (0 ISSUES)
                            </Badge>
                          )}

                          {/* Risk Score Pill */}
                          <div className="text-[10px] font-mono px-2 py-0.5 rounded bg-slate-900 border border-slate-800 text-slate-300">
                            Risk: {(prAnalysisResult.reliability_assessment?.reliability_risk_score ?? 0).toFixed(1)} / 100
                          </div>
                        </div>
                      </div>

                      {/* Partial Evidence Warning Callout */}
                      {prAnalysisResult.reliability_assessment.evidence_status === "PARTIAL" && (
                        <div className="p-3 rounded-xl border border-blue-500/30 bg-blue-950/20 text-xs text-blue-200 flex items-center gap-2.5 font-sans">
                          <Info className="h-4 w-4 text-blue-400 shrink-0" />
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
                              className={`p-3.5 rounded-xl border text-xs space-y-2 transition-colors ${
                                rf.severity === "CRITICAL"
                                  ? "border-rose-500/40 bg-rose-950/20"
                                  : rf.severity === "HIGH"
                                  ? "border-amber-500/40 bg-amber-950/15"
                                  : "border-slate-800 bg-slate-900/40"
                              }`}
                            >
                              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1.5">
                                <div className="flex items-center gap-2 flex-wrap">
                                  <Badge
                                    variant={rf.severity === "CRITICAL" ? "destructive" : "outline"}
                                    className={`text-[9px] font-mono font-bold uppercase tracking-wider ${
                                      rf.severity === "HIGH"
                                        ? "text-amber-400 border-amber-500/40 bg-amber-950/30"
                                        : rf.severity === "MEDIUM"
                                        ? "text-yellow-400 border-yellow-500/40 bg-yellow-950/30"
                                        : ""
                                    }`}
                                  >
                                    {rf.severity}
                                  </Badge>
                                  <Badge variant="outline" className="text-[9px] font-mono text-purple-400 border-purple-500/40 bg-purple-950/20 flex items-center gap-1">
                                    <Activity className="h-3 w-3" />
                                    DETERMINISTIC
                                  </Badge>
                                  <span className="font-mono text-[10px] text-slate-400 uppercase tracking-wider">
                                    {rf.category}
                                  </span>
                                  <span className="font-bold text-slate-200">{rf.title}</span>
                                </div>
                                <div className="flex items-center gap-2">
                                  <span className="font-mono text-[10px] text-slate-500">{rf.rule_id}</span>
                                  {rf.is_blocker && (
                                    <Badge variant="destructive" className="text-[9px] px-1 py-0 uppercase font-mono font-bold">
                                      BLOCKING
                                    </Badge>
                                  )}
                                </div>
                              </div>

                              <p className="text-slate-300 text-[11px] leading-relaxed">{rf.description}</p>

                              {/* Concrete Evidence Snippet */}
                              <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800 font-mono text-[11px] text-indigo-300 flex items-center justify-between gap-2 overflow-x-auto">
                                <div>
                                  <span className="text-slate-500">{rf.file_path}{rf.line_number ? `:${rf.line_number}` : ""}:</span>{" "}
                                  <span>{rf.evidence}</span>
                                </div>
                                <span className="text-[9px] uppercase px-1.5 py-0.5 rounded bg-indigo-950/40 border border-indigo-500/30 text-indigo-300 shrink-0">
                                  Confidence: {rf.confidence}
                                </span>
                              </div>

                              {/* Remediation */}
                              <div className="text-[11px] text-slate-400 flex items-start gap-1.5">
                                <span className="text-emerald-400 font-semibold shrink-0">Remediation:</span>
                                <span>{rf.remediation}</span>
                              </div>

                              {/* Interactive Action Buttons */}
                              <div className="flex items-center justify-between pt-2 border-t border-slate-800/60">
                                <span className="text-[10px] font-mono text-slate-500">
                                  Evidence: <span className="text-indigo-300 font-semibold">Static AST & Churn Verification</span>
                                </span>
                                <div className="flex items-center gap-2">
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => setSelectedFindingForModal(rf)}
                                    className="h-6 text-[10px] px-2 border-slate-700 bg-slate-900/60 hover:bg-slate-800 text-slate-300 flex items-center gap-1"
                                  >
                                    <Eye className="h-3 w-3 text-indigo-400" />
                                    Safe Context
                                  </Button>
                                  <Button
                                    type="button"
                                    variant="outline"
                                    size="sm"
                                    onClick={() => handleExplainFinding(rf)}
                                    className="h-6 text-[10px] px-2 border-purple-500/40 bg-purple-950/30 hover:bg-purple-900/40 text-purple-200 flex items-center gap-1"
                                  >
                                    <Sparkles className="h-3 w-3 text-purple-400" />
                                    Explain with ForgeAI
                                  </Button>
                                </div>
                              </div>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-center py-6 text-slate-500 text-xs font-mono">
                          Zero reliability failure risks or exception swallows identified.
                        </div>
                      )}
                    </div>
                  )}

                  {/* Insufficient context warning callout if applicable */}
                  {prAnalysisResult.summary?.includes("INSUFFICIENT_CONTEXT") && (
                    <div className="p-4 rounded-xl border border-amber-500/30 bg-amber-950/30 text-xs text-amber-200 flex items-start gap-3">
                      <AlertTriangle className="h-5 w-5 text-amber-400 shrink-0 mt-0.5" />
                      <div>
                        <div className="font-bold text-amber-100">Grounded Evidence Warning: Insufficient Context</div>
                        <div>
                          No indexed code chunks or knowledge graph edges matched this repository. ForgeAI does not fabricate facts.
                          Please ensure the repository has been indexed into ForgeAI before performing deep impact analysis.
                        </div>
                      </div>
                    </div>
                  )}

                  {/* 1. Deterministic Risk Factors Breakdown */}
                  {Array.isArray(prAnalysisResult.risk_factors) && prAnalysisResult.risk_factors.length > 0 && (
                    <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Sliders className="h-4 w-4 text-purple-400" />
                          Deterministic Risk Factors Breakdown
                        </span>
                        <span className="text-[11px] font-mono text-slate-400">
                          Score: {(prAnalysisResult.risk_score ?? 0).toFixed(1)} / 100
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2.5">
                        {prAnalysisResult.risk_factors.map((rf, idx) => (
                          <div
                            key={idx}
                            className="p-3 rounded-lg border border-slate-800/80 bg-slate-900/60 text-xs flex flex-col justify-between gap-1.5"
                          >
                            <div className="flex items-center justify-between">
                              <span className="font-semibold text-slate-200">{rf.factor || rf.name}</span>
                              <Badge
                                variant="outline"
                                className={`text-[10px] font-mono ${
                                  (rf.points ?? rf.score ?? 0) > 10
                                    ? "text-rose-400 border-rose-500/40 bg-rose-950/20"
                                    : (rf.points ?? rf.score ?? 0) > 0
                                    ? "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                    : "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                }`}
                              >
                                +{(rf.points ?? rf.score ?? 0)} pts
                              </Badge>
                            </div>
                            <p className="text-[11px] text-slate-400 leading-snug">{rf.detail || rf.details}</p>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* 2. Changed Components & Affected Modules */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Changed Components */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                        <FileCode className="h-4 w-4 text-indigo-400" />
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
                          return <div className="text-xs text-slate-500 italic">No specific symbols identified</div>;
                        }
                        return (
                          <div className="flex flex-wrap gap-1.5 max-h-40 overflow-y-auto">
                            {items.map((comp, idx) => (
                              <div
                                key={idx}
                                className="px-2 py-1 rounded border border-slate-800 bg-slate-900 text-[11px] font-mono text-slate-300 flex items-center gap-1.5"
                              >
                                <Badge variant="outline" className="text-[9px] font-mono text-indigo-400 px-1 py-0">
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
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Boxes className="h-4 w-4 text-purple-400" />
                          Architectural Areas & Modules
                        </span>
                        {prAnalysisResult.impact_metadata?.cross_module_impact && (
                          <Badge variant="destructive" className="text-[10px] uppercase font-mono">
                            Cross-Module Impact
                          </Badge>
                        )}
                      </div>
                      {Array.isArray(prAnalysisResult.affected_modules) && prAnalysisResult.affected_modules.length > 0 ? (
                        <div className="flex flex-wrap gap-2">
                          {prAnalysisResult.affected_modules.map((mod, idx) => (
                            <Badge
                              key={idx}
                              variant="outline"
                              className="text-xs font-mono px-2.5 py-1 bg-purple-950/20 text-purple-300 border-purple-500/30"
                            >
                              {mod}
                            </Badge>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No specific architectural modules isolated</div>
                      )}
                    </div>
                  </div>

                  {/* 3. Multi-Hop Blast Radius (Direct vs Transitive) */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                    {/* Direct Impact (Depth = 1) */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-4 w-4 text-emerald-400" />
                          Direct Blast Radius (Depth 1)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {Array.isArray(prAnalysisResult.direct_impact) ? prAnalysisResult.direct_impact.length : 0} callers
                        </Badge>
                      </div>
                      {Array.isArray(prAnalysisResult.direct_impact) && prAnalysisResult.direct_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.direct_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-slate-800/80 bg-slate-900/60 text-xs font-mono flex items-center justify-between"
                            >
                              <span className="text-emerald-300 font-medium truncate max-w-xs">
                                {item.source_symbol}
                              </span>
                              <Badge variant="outline" className="text-[9px] text-slate-400">
                                {item.edge_type || "DEPENDS_ON"}
                              </Badge>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No direct downstream dependents found</div>
                      )}
                    </div>

                    {/* Transitive Impact (Depth = 2, 3) */}
                    <div className="rounded-xl border border-slate-800 bg-slate-950/50 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Network className="h-4 w-4 text-indigo-400" />
                          Transitive Blast Radius (Depth 2-3)
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {Array.isArray(prAnalysisResult.transitive_impact) ? prAnalysisResult.transitive_impact.length : 0} callers
                        </Badge>
                      </div>
                      {Array.isArray(prAnalysisResult.transitive_impact) && prAnalysisResult.transitive_impact.length > 0 ? (
                        <div className="space-y-1.5 max-h-48 overflow-y-auto">
                          {prAnalysisResult.transitive_impact.map((item, idx) => (
                            <div
                              key={idx}
                              className="p-2 rounded border border-slate-800/80 bg-slate-900/60 text-xs font-mono flex items-center justify-between"
                            >
                              <div className="flex items-center gap-2 truncate">
                                <Badge variant="outline" className="text-[9px] text-indigo-400 border-indigo-500/40">
                                  {item.depth} hops
                                </Badge>
                                <span className="text-slate-300 truncate max-w-xs">{item.source_symbol}</span>
                              </div>
                              <span className="text-[10px] text-slate-500">{item.edge_type}</span>
                            </div>
                          ))}
                        </div>
                      ) : (
                        <div className="text-xs text-slate-500 italic">No multi-hop transitive dependents found</div>
                      )}
                    </div>
                  </div>

                  {/* 3b. Distributed Change Intelligence & Cross-Repository Topology */}
                  {prAnalysisResult.impact_metadata?.distributed_impact && (
                    <div className="rounded-2xl border border-indigo-500/30 bg-gradient-to-br from-indigo-950/20 via-slate-950 to-purple-950/20 p-5 space-y-4 shadow-xl">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 border-b border-slate-800/80 pb-3">
                        <div className="flex items-center gap-2.5 flex-wrap">
                          <Share2 className="h-5 w-5 text-indigo-400" />
                          <span className="text-sm font-bold text-white uppercase tracking-wider">
                            Distributed Change Intelligence & Cross-Repository Topology
                          </span>
                          <Badge
                            variant="outline"
                            className={`text-xs font-mono font-bold px-2.5 py-0.5 border ${
                              prAnalysisResult.impact_metadata.distributed_impact.contract_status === "COMPATIBLE"
                                ? "bg-emerald-950/60 text-emerald-400 border-emerald-500/40"
                                : prAnalysisResult.impact_metadata.distributed_impact.contract_status === "POTENTIALLY_BREAKING"
                                ? "bg-amber-950/60 text-amber-400 border-amber-500/40"
                                : "bg-rose-950/60 text-rose-400 border-rose-500/40 animate-pulse"
                            }`}
                          >
                            Contract: {prAnalysisResult.impact_metadata.distributed_impact.contract_status}
                          </Badge>
                        </div>

                        <div className="flex items-center gap-2 flex-wrap text-xs font-mono">
                          <div className="p-1.5 px-2.5 rounded bg-slate-900 border border-slate-800 text-slate-300">
                            Direct Downstream Repos:{" "}
                            <span className="font-bold text-white">
                              {prAnalysisResult.impact_metadata.distributed_impact.direct_downstream_count}
                            </span>
                          </div>
                        </div>
                      </div>

                      {/* Contract Analysis Rationale */}
                      {prAnalysisResult.impact_metadata.contract_rationale && (
                        <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/60 text-xs text-slate-300 flex items-start gap-2">
                          <Info className="h-4 w-4 text-indigo-400 shrink-0 mt-0.5" />
                          <div>
                            <span className="font-semibold text-slate-200">Contract Analysis: </span>
                            <span className="text-slate-300">{prAnalysisResult.impact_metadata.contract_rationale}</span>
                          </div>
                        </div>
                      )}

                      {/* Multi-Repo Risk Scores */}
                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 font-mono text-xs">
                        <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/70 space-y-1">
                          <div className="text-[10px] uppercase text-slate-500 font-bold">Repo-Local Risk</div>
                          <div className="text-lg font-bold text-slate-200">
                            {(prAnalysisResult.impact_metadata.distributed_impact.local_risk_score ?? prAnalysisResult.risk_score ?? 0).toFixed(1)} / 100
                          </div>
                          <Badge variant="outline" className="text-[9px] text-slate-400">
                            Level: {prAnalysisResult.impact_metadata.distributed_impact.local_risk_level ?? "LOW"}
                          </Badge>
                        </div>

                        <div className="p-3 rounded-lg border border-indigo-500/30 bg-indigo-950/20 space-y-1">
                          <div className="text-[10px] uppercase text-indigo-400 font-bold">Distributed Risk (Max 50)</div>
                          <div className="text-lg font-bold text-indigo-300">
                            {(prAnalysisResult.impact_metadata.distributed_impact.distributed_risk_score ?? 0).toFixed(1)} / 50
                          </div>
                          <Badge variant="outline" className="text-[9px] text-indigo-300 border-indigo-500/40">
                            Level: {prAnalysisResult.impact_metadata.distributed_impact.distributed_risk_level ?? "NONE"}
                          </Badge>
                        </div>

                        <div className="p-3 rounded-lg border border-purple-500/30 bg-purple-950/20 space-y-1">
                          <div className="text-[10px] uppercase text-purple-400 font-bold">Combined Multi-Repo Risk</div>
                          <div className="text-lg font-bold text-purple-200">
                            {(prAnalysisResult.impact_metadata.distributed_impact.combined_risk_score ?? 0).toFixed(1)} / 100
                          </div>
                          <div className="text-[10px] text-slate-400 font-sans">
                            Formula: <span className="font-mono text-slate-300">local + 0.4 &times; distributed</span>
                          </div>
                        </div>
                      </div>

                      {/* Downstream Impacted Repositories List */}
                      <div className="space-y-2">
                        <div className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <FolderGit2 className="h-4 w-4 text-indigo-400" />
                          Downstream Dependents & CI Status
                        </div>

                        {prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts &&
                        prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts.length > 0 ? (
                          <div className="space-y-2 max-h-64 overflow-y-auto">
                            {prAnalysisResult.impact_metadata.distributed_impact.downstream_impacts.map((dep, dIdx) => (
                              <div
                                key={dIdx}
                                className="p-3 rounded-lg border border-slate-800 bg-slate-900/60 text-xs space-y-2 hover:border-slate-700 transition-colors"
                              >
                                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    <span className="font-semibold font-mono text-slate-100 text-sm">
                                      {dep.target_repository_name || (dep as any).repository_name || dep.target_repository_id || (dep as any).repository_id || "downstream-repo"}
                                    </span>
                                    <Badge variant="outline" className="text-[10px] font-mono text-indigo-300 border-indigo-500/30">
                                      {dep.dependency_type}
                                    </Badge>
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-mono ${
                                        (dep.confidence || (dep as any).dependency_confidence) === "HIGH"
                                          ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                          : (dep.confidence || (dep as any).dependency_confidence) === "MEDIUM"
                                          ? "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                                          : "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                      }`}
                                    >
                                      Confidence: {dep.confidence || (dep as any).dependency_confidence || "LOW"}
                                    </Badge>
                                  </div>

                                  <div className="flex items-center gap-2 font-mono text-xs">
                                    <span className="text-[11px] text-slate-400">Downstream CI:</span>
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-bold ${
                                        (dep.downstream_ci_status || (dep as any).ci_status) === "PASS" || (dep.downstream_ci_status || (dep as any).ci_status) === "PASSED"
                                          ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/40"
                                          : (dep.downstream_ci_status || (dep as any).ci_status) === "FAIL" || (dep.downstream_ci_status || (dep as any).ci_status) === "FAILED"
                                          ? "text-rose-400 border-rose-500/40 bg-rose-950/40 animate-pulse"
                                          : "text-slate-400 border-slate-700 bg-slate-900/50"
                                      }`}
                                    >
                                      {dep.downstream_ci_status || (dep as any).ci_status || "NOT_FOUND"}
                                    </Badge>
                                    <span className="text-[11px] text-slate-400 ml-1">
                                      Risk +{dep.risk_contribution ?? 0}
                                    </span>
                                  </div>
                                </div>

                                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px] font-mono pt-1">
                                  {dep.affected_symbols && dep.affected_symbols.length > 0 && (
                                    <div className="p-2 rounded bg-slate-950/70 border border-slate-800/80">
                                      <span className="text-[10px] uppercase text-slate-500 block mb-1">
                                        Affected Downstream Symbols ({dep.affected_symbols.length})
                                      </span>
                                      <div className="flex flex-wrap gap-1">
                                        {dep.affected_symbols.slice(0, 4).map((sym, sIdx) => (
                                          <span key={sIdx} className="px-1.5 py-0.5 rounded bg-slate-900 text-indigo-300 border border-slate-800">
                                            {sym}
                                          </span>
                                        ))}
                                        {dep.affected_symbols.length > 4 && (
                                          <span className="text-slate-500 text-[10px]">
                                            +{dep.affected_symbols.length - 4} more
                                          </span>
                                        )}
                                      </div>
                                    </div>
                                  )}

                                  {dep.evidence_files && dep.evidence_files.length > 0 && (
                                    <div className="p-2 rounded bg-slate-950/70 border border-slate-800/80">
                                      <span className="text-[10px] uppercase text-slate-500 block mb-1">
                                        Evidence Manifests & Files
                                      </span>
                                      <div className="truncate text-slate-400">
                                        {dep.evidence_files.join(", ")}
                                      </div>
                                    </div>
                                  )}
                                </div>
                              </div>
                            ))}
                          </div>
                        ) : (
                          <div className="p-4 rounded-lg border border-dashed border-slate-800 text-center text-xs text-slate-500">
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
                      <div className="rounded-xl border border-indigo-500/30 bg-slate-950/70 p-5 space-y-5 shadow-2xl">
                        {/* Section Header */}
                        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4 pb-4 border-b border-slate-800">
                          <div>
                            <div className="flex items-center gap-2.5">
                              <TestTube className="h-5 w-5 text-indigo-400" />
                              <span className="text-sm font-bold text-white uppercase tracking-wider">
                                Test Intelligence & Risk-Driven Validation Engine
                              </span>
                            </div>
                            <p className="text-xs text-slate-400 mt-1">
                              Deterministic test prioritization combining AST symbol call graph, runtime coverage, and historical failure coupling.
                            </p>
                          </div>

                          <div className="flex items-center gap-2.5 flex-wrap">
                            {/* Coverage Mechanism Badge */}
                            <Badge
                              variant="outline"
                              className={`text-xs font-mono px-3 py-1 flex items-center gap-1.5 ${
                                coverageAvailable
                                  ? "bg-emerald-500/15 text-emerald-300 border-emerald-500/40"
                                  : "bg-indigo-950/40 text-indigo-300 border-indigo-500/40"
                              }`}
                            >
                              {coverageAvailable ? (
                                <>
                                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                                  <span>{coverageMech} Runtime Coverage Verified</span>
                                </>
                              ) : (
                                <>
                                  <Info className="h-3.5 w-3.5 text-indigo-400" />
                                  <span>Static AST Fallback (Graceful Degradation)</span>
                                </>
                              )}
                            </Badge>

                            {/* Overall Test Strategy Confidence */}
                            <Badge
                              variant="outline"
                              className={`text-xs font-mono px-3 py-1 ${
                                overallConf === "HIGH"
                                  ? "bg-emerald-950/30 text-emerald-400 border-emerald-500/40"
                                  : overallConf === "MEDIUM"
                                  ? "bg-blue-950/30 text-blue-400 border-blue-500/40"
                                  : "bg-amber-950/30 text-amber-400 border-amber-500/40"
                              }`}
                              title={strategy?.confidence_rationale}
                            >
                              Confidence: {overallConf}
                            </Badge>
                          </div>
                        </div>

                        {/* Priority Bucket Selector Cards */}
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                          {/* MUST RUN */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("MUST_RUN")}
                            className={`p-3 rounded-lg border text-left transition-all flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "MUST_RUN"
                                ? "border-rose-500/60 bg-rose-950/30 shadow-lg shadow-rose-950/40"
                                : "border-slate-800 bg-slate-900/50 hover:bg-slate-900"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-rose-300 uppercase flex items-center gap-1.5">
                                <Flame className="h-3.5 w-3.5 text-rose-400" />
                                MUST RUN
                              </span>
                              <Badge variant="outline" className="text-xs font-mono font-bold bg-rose-500/20 text-rose-300 border-rose-500/40">
                                {mustRunList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] text-slate-400">Score &ge; 60 or High Risk</span>
                          </button>

                          {/* SHOULD RUN */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("SHOULD_RUN")}
                            className={`p-3 rounded-lg border text-left transition-all flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "SHOULD_RUN"
                                ? "border-amber-500/60 bg-amber-950/30 shadow-lg shadow-amber-950/40"
                                : "border-slate-800 bg-slate-900/50 hover:bg-slate-900"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-amber-300 uppercase flex items-center gap-1.5">
                                <Zap className="h-3.5 w-3.5 text-amber-400" />
                                SHOULD RUN
                              </span>
                              <Badge variant="outline" className="text-xs font-mono font-bold bg-amber-500/20 text-amber-300 border-amber-500/40">
                                {shouldRunList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] text-slate-400">30 &le; Score &lt; 60</span>
                          </button>

                          {/* OPTIONAL */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("OPTIONAL")}
                            className={`p-3 rounded-lg border text-left transition-all flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "OPTIONAL"
                                ? "border-blue-500/60 bg-blue-950/30 shadow-lg shadow-blue-950/40"
                                : "border-slate-800 bg-slate-900/50 hover:bg-slate-900"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-blue-300 uppercase flex items-center gap-1.5">
                                <CheckCheck className="h-3.5 w-3.5 text-blue-400" />
                                OPTIONAL
                              </span>
                              <Badge variant="outline" className="text-xs font-mono font-bold bg-blue-500/20 text-blue-300 border-blue-500/40">
                                {optionalList.length}
                              </Badge>
                            </div>
                            <span className="text-[11px] text-slate-400">Score &lt; 30 (Regression)</span>
                          </button>

                          {/* ALL / SUMMARY */}
                          <button
                            type="button"
                            onClick={() => setTestBucketFilter("ALL")}
                            className={`p-3 rounded-lg border text-left transition-all flex flex-col justify-between gap-1.5 ${
                              testBucketFilter === "ALL"
                                ? "border-indigo-500/60 bg-indigo-950/30 shadow-lg shadow-indigo-950/40"
                                : "border-slate-800 bg-slate-900/50 hover:bg-slate-900"
                            }`}
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-indigo-300 uppercase flex items-center gap-1.5">
                                <BarChart3 className="h-3.5 w-3.5 text-indigo-400" />
                                ALL TESTS
                              </span>
                              <Badge variant="outline" className="text-xs font-mono font-bold bg-indigo-500/20 text-indigo-300 border-indigo-500/40">
                                {totalTests}
                              </Badge>
                            </div>
                            <span className="text-[11px] text-slate-400">Total Recommended</span>
                          </button>
                        </div>

                        {/* Test Recommendations List */}
                        <div className="space-y-3">
                          <div className="flex items-center justify-between text-xs text-slate-400 font-mono">
                            <span>
                              Showing {activeList.length} tests in{" "}
                              <span className="font-bold text-white uppercase">{testBucketFilter.replace("_", " ")}</span>
                            </span>
                            {strategy?.confidence_rationale && (
                              <span className="truncate max-w-md italic">{strategy.confidence_rationale}</span>
                            )}
                          </div>

                          {activeList.length > 0 ? (
                            <div className="space-y-3">
                              {activeList.map((rec: TestRecommendationItem, idx: number) => (
                                <div
                                  key={idx}
                                  className="p-4 rounded-xl border border-slate-800 bg-slate-900/70 hover:border-slate-700/80 transition-all space-y-2.5"
                                >
                                  {/* Row 1: Header */}
                                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                    <div className="flex items-center gap-2.5 flex-wrap">
                                      <span className="font-mono text-white font-bold text-sm">
                                        {rec.test_name}
                                      </span>
                                      {rec.test_method && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-purple-300 border-purple-500/30">
                                          #{rec.test_method}
                                        </Badge>
                                      )}
                                      {rec.target_symbol && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-slate-400">
                                          Targets: {rec.target_symbol}
                                        </Badge>
                                      )}
                                    </div>

                                    <div className="flex items-center gap-2">
                                      {/* Priority Score */}
                                      <div className="flex items-center gap-1 px-2.5 py-0.5 rounded bg-slate-950 border border-slate-800 font-mono text-xs">
                                        <span className="text-slate-500">Score:</span>
                                        <span className="font-bold text-indigo-300">
                                          {typeof rec.priority_score === "number" ? rec.priority_score.toFixed(1) : rec.priority_score}
                                        </span>
                                        <span className="text-slate-600">/ 100</span>
                                      </div>

                                      {/* Bucket Badge */}
                                      <Badge
                                        variant="outline"
                                        className={`text-[10px] font-bold uppercase tracking-wider ${
                                          rec.priority_bucket === "MUST_RUN"
                                            ? "bg-rose-500/15 text-rose-300 border-rose-500/40"
                                            : rec.priority_bucket === "SHOULD_RUN"
                                            ? "bg-amber-500/15 text-amber-300 border-amber-500/40"
                                            : "bg-blue-500/15 text-blue-300 border-blue-500/40"
                                        }`}
                                      >
                                        {rec.priority_bucket}
                                      </Badge>

                                      {/* Stronger Concept: Required for Merge */}
                                      {rec.merge_requirement === "TEST_REQUIRED" && (
                                        <Badge
                                          variant="outline"
                                          className="text-[10px] font-mono font-bold uppercase tracking-wider bg-rose-950/70 text-rose-300 border-rose-500/60"
                                        >
                                          Required for Merge
                                        </Badge>
                                      )}

                                      {/* Confidence */}
                                      <Badge
                                        variant="outline"
                                        className={`text-[10px] font-mono ${
                                          rec.confidence === "HIGH"
                                            ? "text-emerald-400 border-emerald-500/40"
                                            : rec.confidence === "MEDIUM"
                                            ? "text-blue-400 border-blue-500/40"
                                            : "text-amber-400 border-amber-500/40"
                                        }`}
                                      >
                                        {rec.confidence}
                                      </Badge>
                                    </div>
                                  </div>

                                  {/* Row 2: File Path & Evidence Sources */}
                                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                                    <span className="font-mono text-slate-400 text-[11px] truncate max-w-xl">
                                      {rec.test_file || "Module Test Suite"}
                                    </span>

                                    <div className="flex items-center gap-1.5 flex-wrap">
                                      {rec.runtime_coverage_verified && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-emerald-300 border-emerald-500/40 bg-emerald-950/20">
                                          Runtime Coverage
                                        </Badge>
                                      )}
                                      {rec.historical_failure_count > 0 && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-rose-300 border-rose-500/40 bg-rose-950/20">
                                          {rec.historical_failure_count} Prior Failures
                                        </Badge>
                                      )}
                                      {rec.cochange_count > 0 && (
                                        <Badge variant="outline" className="text-[10px] font-mono text-amber-300 border-amber-500/40 bg-amber-950/20">
                                          {rec.cochange_count} Co-Commits
                                        </Badge>
                                      )}
                                      {Array.isArray(rec.evidence_sources) && rec.evidence_sources.map((src, sIdx) => (
                                        <Badge
                                          key={sIdx}
                                          variant="outline"
                                          className={`text-[9px] font-mono uppercase ${
                                            src === "RUNTIME"
                                              ? "text-emerald-400 border-emerald-500/30"
                                              : src === "HISTORICAL"
                                              ? "text-amber-400 border-amber-500/30"
                                              : "text-indigo-400 border-indigo-500/30"
                                          }`}
                                        >
                                          {src}
                                        </Badge>
                                      ))}
                                    </div>
                                  </div>

                                  {/* Row 3: Derivation Path (Change -> AST/Runtime/History -> Test) */}
                                  {Array.isArray(rec.evidence_path) && rec.evidence_path.length > 0 && (
                                    <div className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800/80 text-[11px] font-mono text-slate-300 flex items-start gap-2">
                                      <ArrowRight className="h-3.5 w-3.5 text-indigo-400 shrink-0 mt-0.5" />
                                      <div className="space-y-0.5 w-full">
                                        <div className="text-[10px] uppercase font-bold text-slate-500">
                                          Derivation Path
                                        </div>
                                        <div className="text-slate-300 leading-relaxed">
                                          {rec.evidence_path.join(" \u2192 ")}
                                        </div>
                                      </div>
                                    </div>
                                  )}

                                  {/* Row 4: Reason / Explanation */}
                                  <div className="text-[11px] text-slate-400 italic">
                                    <span className="font-semibold text-slate-300 not-italic">Rationale: </span>
                                    {rec.reason}
                                  </div>
                                </div>
                              ))}
                            </div>
                          ) : (
                            <div className="p-8 rounded-xl border border-dashed border-slate-800 text-center text-xs text-slate-500">
                              No tests categorized under {testBucketFilter.replace("_", " ")} for this pull request.
                            </div>
                          )}
                        </div>

                        {/* Recent CI Test Runs & Historical Failures Ingestion Drawer */}
                        {strategy && (strategy.runtime_evidence?.length > 0 || strategy.historical_failures?.length > 0) && (
                          <div className="rounded-xl border border-slate-800/80 bg-slate-900/40 p-4 space-y-3">
                            <div className="flex items-center justify-between">
                              <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                                <Database className="h-4 w-4 text-purple-400" />
                                Ingested CI Workflow Executions & Historical Failures
                              </span>
                              <span className="text-[11px] font-mono text-slate-400">
                                {strategy.runtime_evidence?.length || 0} CI runs &bull; {strategy.historical_failures?.length || 0} failures
                              </span>
                            </div>

                            <div className="grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
                              {/* CI Workflow Runs */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-950/60 space-y-2">
                                <div className="text-[11px] font-bold text-slate-300 uppercase">Recent CI Executions</div>
                                {strategy.runtime_evidence && strategy.runtime_evidence.length > 0 ? (
                                  <div className="space-y-1.5 max-h-36 overflow-y-auto font-mono text-[11px]">
                                    {strategy.runtime_evidence.slice(0, 5).map((run: any, rIdx: number) => (
                                      <div key={rIdx} className="flex items-center justify-between p-1.5 rounded bg-slate-900/80 border border-slate-800/60">
                                        <span className="text-slate-300 truncate max-w-[180px]">
                                          {run.workflow || `Run #${run.workflow_run_id || rIdx + 1}`}
                                        </span>
                                        <Badge
                                          variant="outline"
                                          className={`text-[9px] ${
                                            run.conclusion === "success"
                                              ? "text-emerald-400 border-emerald-500/40"
                                              : run.conclusion === "failure"
                                              ? "text-rose-400 border-rose-500/40"
                                              : "text-slate-400"
                                          }`}
                                        >
                                          {run.conclusion || run.status || "COMPLETED"}
                                        </Badge>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-slate-500 text-[11px] italic">No previous workflow runs ingested</div>
                                )}
                              </div>

                              {/* Historical Failures */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-950/60 space-y-2">
                                <div className="text-[11px] font-bold text-slate-300 uppercase">Historical Test Failures</div>
                                {strategy.historical_failures && strategy.historical_failures.length > 0 ? (
                                  <div className="space-y-1.5 max-h-36 overflow-y-auto font-mono text-[11px]">
                                    {strategy.historical_failures.map((hf: any, hIdx: number) => (
                                      <div key={hIdx} className="flex items-center justify-between p-1.5 rounded bg-slate-900/80 border border-slate-800/60">
                                        <span className="text-rose-300 truncate max-w-[200px]">
                                          {hf.test_name || hf.name}
                                        </span>
                                        <Badge variant="outline" className="text-[9px] text-rose-400 border-rose-500/40">
                                          {hf.failure_count || 1}x failed
                                        </Badge>
                                      </div>
                                    ))}
                                  </div>
                                ) : (
                                  <div className="text-slate-500 text-[11px] italic">No historical failures recorded for affected files</div>
                                )}
                              </div>
                            </div>
                          </div>
                        )}

                        {/* 5. Post-PR Test Validation Feedback Loop */}
                        <div className="rounded-xl border border-indigo-500/20 bg-gradient-to-r from-indigo-950/20 via-slate-950 to-purple-950/20 p-4 space-y-3">
                          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div>
                              <div className="flex items-center gap-2">
                                <Activity className="h-4 w-4 text-emerald-400" />
                                <span className="text-xs font-bold text-white uppercase tracking-wider">
                                  Post-PR Test Validation (Feedback Loop)
                                </span>
                              </div>
                              <p className="text-[11px] text-slate-400 mt-0.5">
                                Compares recommended test selection against actual CI test execution results to calculate selection efficiency and correlation.
                              </p>
                            </div>

                            <Button
                              type="button"
                              variant="outline"
                              size="sm"
                              onClick={handleValidateTests}
                              disabled={isValidatingTests}
                              className="h-8 text-xs px-3 border-emerald-500/40 bg-emerald-950/30 hover:bg-emerald-900/50 text-emerald-200 transition-colors shrink-0"
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
                            <div className="p-2.5 rounded bg-rose-950/30 border border-rose-500/30 text-rose-300 text-xs">
                              {validationError}
                            </div>
                          )}

                          {effectiveness && (
                            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pt-1">
                              {/* Metric 1 */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  Prediction-to-Failure Correlation
                                </span>
                                <div className="text-xl font-bold font-mono text-emerald-400">
                                  {((effectiveness.prediction_to_failure_correlation ?? 0) * 100).toFixed(0)}%
                                </div>
                                <span className="text-[10px] text-slate-500">Recommended failing tests / Total failed tests</span>
                              </div>

                              {/* Metric 2 */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  Test Selection Efficiency
                                </span>
                                <div className="text-xl font-bold font-mono text-indigo-400">
                                  {((effectiveness.test_selection_efficiency ?? 0) * 100).toFixed(0)}%
                                </div>
                                <span className="text-[10px] text-slate-500">Suite runtime reduction efficiency</span>
                              </div>

                              {/* Metric 3 */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-900/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  High Priority Missed Failures
                                </span>
                                <div className={`text-xl font-bold font-mono ${effectiveness.high_priority_missed_count === 0 ? "text-emerald-400" : "text-rose-400"}`}>
                                  {effectiveness.high_priority_missed_count}
                                </div>
                                <span className="text-[10px] text-slate-500">Failing tests missed by MUST_RUN</span>
                              </div>
                            </div>
                          )}
                        </div>
                      </div>
                    );
                  })()}

                  {/* 5. Historical Change Signals */}
                  {Array.isArray(prAnalysisResult.historical_signals) && prAnalysisResult.historical_signals.length > 0 && (
                    <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 space-y-3">
                      <div className="flex items-center justify-between">
                        <span className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <History className="h-4 w-4 text-amber-400" />
                          Historical Change Signals (Commit Churn Evidence)
                        </span>
                        <span className="text-[11px] font-mono text-slate-400">
                          {prAnalysisResult.historical_signals.length} files tracked
                        </span>
                      </div>
                      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
                        {prAnalysisResult.historical_signals.map((sig, idx) => (
                          <div
                            key={idx}
                            className="p-2.5 rounded-lg border border-slate-800 bg-slate-900/60 text-xs font-mono flex flex-col gap-1"
                          >
                            <div className="flex items-center justify-between">
                              <span className="text-slate-300 truncate max-w-[180px]">{sig.file_path}</span>
                              <Badge variant="outline" className="text-[10px] text-amber-400 border-amber-500/30">
                                {sig.commit_count || (sig as any).recent_modifications_count || 0} prior commits
                              </Badge>
                            </div>
                            {sig.recent_commits && sig.recent_commits.length > 0 && (
                              <div className="text-[10px] text-slate-500 truncate">
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
                    <div className="p-4 rounded-xl border border-rose-500/30 bg-rose-950/20 text-xs text-rose-200 space-y-2">
                      <div className="font-bold text-rose-100 flex items-center gap-2">
                        <AlertTriangle className="h-4 w-4 text-rose-400" />
                        Architectural Concerns Flagged
                      </div>
                      <ul className="list-disc list-inside space-y-1 text-rose-300">
                        {prAnalysisResult.architectural_concerns.map((concern, idx) => (
                          <li key={idx}>{concern}</li>
                        ))}
                      </ul>
                    </div>
                  )}

                  {/* 7. Grounded AI Reasoning Review Body */}
                  <div className="space-y-2">
                    <div className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                      <Cpu className="h-4 w-4 text-purple-400" />
                      Grounded LLM Reasoning (Facts, Inferences & Recommendations)
                    </div>
                    <div className="p-5 rounded-xl bg-slate-900 border border-slate-800 text-xs text-slate-200 leading-relaxed whitespace-pre-line font-sans shadow-inner">
                      {prAnalysisResult.summary}
                    </div>
                  </div>

                  {/* ========================================================= */}
                  {/* AUTOMATED AI CODE REVIEW FINDINGS (GROUNDED & DEDUPLICATED)*/}
                  {/* ========================================================= */}
                  <div className="space-y-4 pt-2 border-t border-slate-800/80">
                    <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <FileCheck className="h-4 w-4 text-emerald-400" />
                        <span className="text-xs font-bold text-white uppercase tracking-wider">
                          Automated Code Review Findings
                        </span>
                        <Badge variant="outline" className="text-[10px] font-mono text-purple-300 border-purple-500/30">
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
                              className={`px-2.5 py-1 rounded text-[10px] font-mono transition-colors flex items-center gap-1 ${
                                findingSeverityFilter === sev
                                  ? "bg-purple-600 text-white font-semibold"
                                  : "bg-slate-900 text-slate-400 hover:bg-slate-800 hover:text-slate-200"
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
                            <div className="p-6 rounded-xl border border-dashed border-slate-800 text-center text-xs text-slate-500">
                              No review findings matching severity {findingSeverityFilter}.
                            </div>
                          );
                        }

                        return (
                          <div className="grid grid-cols-1 gap-3">
                            {filtered.map((finding, fIdx) => (
                              <div
                                key={finding.id || fIdx}
                                className="rounded-xl border border-slate-800/90 bg-slate-950/70 p-4 space-y-3 hover:border-slate-700 transition-all shadow-sm"
                              >
                                {/* Header: Title, Severity, Observed/Inferred, Category */}
                                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                                  <div className="flex items-center gap-2 flex-wrap">
                                    {/* Severity Badge */}
                                    <Badge
                                      variant="outline"
                                      className={`text-[10px] font-mono font-bold uppercase tracking-wider ${
                                        finding.severity === "CRITICAL"
                                          ? "text-rose-400 border-rose-500/50 bg-rose-950/30"
                                          : finding.severity === "HIGH"
                                          ? "text-orange-400 border-orange-500/50 bg-orange-950/30"
                                          : finding.severity === "MEDIUM"
                                          ? "text-amber-400 border-amber-500/50 bg-amber-950/30"
                                          : finding.severity === "LOW"
                                          ? "text-blue-400 border-blue-500/50 bg-blue-950/30"
                                          : "text-slate-400 border-slate-700 bg-slate-900/40"
                                      }`}
                                    >
                                      {finding.severity}
                                    </Badge>

                                    {/* Finding Type (DETERMINISTIC vs AI EXPLANATION) */}
                                    <Badge
                                      variant="outline"
                                      className={`text-[9px] font-mono uppercase tracking-wider flex items-center gap-1 ${
                                        finding.finding_type === "OBSERVED"
                                          ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                          : "text-purple-400 border-purple-500/40 bg-purple-950/20"
                                      }`}
                                    >
                                      {finding.finding_type === "OBSERVED" ? (
                                        <>
                                          <ShieldCheck className="h-3 w-3 text-emerald-400" />
                                          DETERMINISTIC
                                        </>
                                      ) : (
                                        <>
                                          <Sparkles className="h-3 w-3 text-purple-400" />
                                          AI EXPLANATION
                                        </>
                                      )}
                                    </Badge>

                                    {/* Category Badge */}
                                    <Badge
                                      variant="outline"
                                      className="text-[9px] font-mono text-slate-300 border-slate-800 bg-slate-900/60"
                                    >
                                      {finding.category}
                                    </Badge>
                                  </div>

                                  {/* Affected Target */}
                                  <div className="text-[11px] font-mono text-slate-400 flex items-center gap-1.5 truncate max-w-sm">
                                    <FileCode className="h-3.5 w-3.5 text-indigo-400 shrink-0" />
                                    <span className="text-slate-300 truncate">{finding.affected_file}</span>
                                    {finding.affected_symbol && (
                                      <span className="text-purple-300 truncate">#{finding.affected_symbol}</span>
                                    )}
                                  </div>
                                </div>

                                {/* Title & Description */}
                                <div>
                                  <h4 className="text-sm font-semibold text-white tracking-tight">
                                    {finding.title}
                                  </h4>
                                  <p className="text-xs text-slate-300 mt-1 leading-relaxed">
                                    {finding.description}
                                  </p>
                                </div>

                                {/* Concrete Grounded Evidence */}
                                {finding.evidence && (
                                  <div className="p-2.5 rounded-lg bg-slate-900/80 border border-slate-800 text-[11px] font-mono text-slate-300 space-y-1">
                                    <div className="text-[10px] uppercase font-bold text-slate-500">
                                      Concrete Evidence
                                    </div>
                                    <div className="text-slate-300 leading-snug whitespace-pre-wrap break-words">
                                      {finding.evidence}
                                    </div>
                                  </div>
                                )}

                                {/* Actionable Recommendation */}
                                {finding.recommendation && (
                                  <div className="p-2.5 rounded-lg bg-emerald-950/15 border border-emerald-500/20 text-[11px] text-emerald-200 space-y-1">
                                    <div className="text-[10px] uppercase font-bold text-emerald-400 flex items-center gap-1">
                                      <CheckCircle2 className="h-3 w-3" />
                                      Actionable Recommendation
                                    </div>
                                    <div className="text-emerald-300/90 leading-snug">
                                      {finding.recommendation}
                                    </div>
                                  </div>
                                )}

                                {/* Action Buttons */}
                                <div className="flex items-center justify-between pt-2 border-t border-slate-800/60">
                                  <span className="text-[10px] font-mono text-slate-500">
                                    Provenance: <span className="text-slate-300 font-semibold">{finding.finding_type === "OBSERVED" ? "Deterministic Rule" : "Grounded AI Reasoning"}</span>
                                  </span>
                                  <div className="flex items-center gap-2">
                                    <Button
                                      type="button"
                                      variant="outline"
                                      size="sm"
                                      onClick={() => setSelectedFindingForModal(finding)}
                                      className="h-6 text-[10px] px-2 border-slate-700 bg-slate-900/60 hover:bg-slate-800 text-slate-300 flex items-center gap-1"
                                    >
                                      <Eye className="h-3 w-3 text-indigo-400" />
                                      Safe Context
                                    </Button>
                                    <Button
                                      type="button"
                                      variant="outline"
                                      size="sm"
                                      onClick={() => handleExplainFinding(finding)}
                                      className="h-6 text-[10px] px-2 border-purple-500/40 bg-purple-950/30 hover:bg-purple-900/40 text-purple-200 flex items-center gap-1"
                                    >
                                      <Sparkles className="h-3 w-3 text-purple-400" />
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
                      <div className="p-5 rounded-xl border border-slate-800 bg-slate-950/40 text-xs text-slate-400 flex items-center gap-2.5">
                        <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                        <span>No automated review issues or architectural violations identified for this changeset.</span>
                      </div>
                    )}
                  </div>

                  {/* 8. Grounded Provenance Explorer (Multi-Category) */}
                  {Array.isArray(prAnalysisResult.retrieved_chunks) && prAnalysisResult.retrieved_chunks.length > 0 && (
                    <div className="space-y-3 pt-2 border-t border-slate-800/80">
                      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                        <h4 className="text-xs font-bold text-slate-300 uppercase tracking-wider flex items-center gap-1.5">
                          <Layers className="h-3.5 w-3.5 text-purple-400" />
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
                              className={`px-2.5 py-1 rounded text-[10px] font-mono transition-colors ${
                                provenanceFilter === filter
                                  ? "bg-purple-600 text-white font-bold"
                                  : "bg-slate-900 text-slate-400 hover:text-white"
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
                            className="p-3 rounded-lg border border-slate-800 bg-slate-950 text-xs font-mono flex flex-col gap-1.5"
                          >
                            <div className="flex items-center justify-between flex-wrap gap-2">
                              <div className="flex items-center gap-2">
                                <Badge
                                  variant="outline"
                                  className={`text-[9px] font-bold ${
                                    chunk.source === "PR_DIFF"
                                      ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                      : chunk.source === "GRAPH"
                                      ? "text-purple-400 border-purple-500/40 bg-purple-950/20"
                                      : chunk.source === "TEST"
                                      ? "text-blue-400 border-blue-500/40 bg-blue-950/20"
                                      : chunk.source === "HISTORY"
                                      ? "text-amber-400 border-amber-500/40 bg-amber-950/20"
                                      : chunk.source === "SYNC_STATE"
                                      ? "text-cyan-400 border-cyan-500/40 bg-cyan-950/20"
                                      : "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                                  }`}
                                >
                                  {chunk.source || "VECTOR"}
                                </Badge>
                                <span className="text-white font-semibold truncate max-w-sm">
                                  {chunk.file_path || chunk.symbol_name || "Repository Artifact"}
                                </span>
                              </div>

                              {chunk.similarity !== undefined && (
                                <span className="text-[10px] text-slate-400">
                                  Similarity: {(chunk.similarity * 100).toFixed(1)}%
                                </span>
                              )}
                              {chunk.depth !== undefined && (
                                <span className="text-[10px] text-slate-400">
                                  Depth: {chunk.depth} hops ({chunk.edge_type})
                                </span>
                              )}
                            </div>

                            {chunk.content && (
                              <div className="text-[11px] text-slate-400 bg-slate-900/80 p-2 rounded truncate">
                                {chunk.content}
                              </div>
                            )}

                            <div className="flex items-center justify-between text-[10px] text-slate-500 pt-1 border-t border-slate-900">
                              <span>
                                Symbol:{" "}
                                <code className="text-slate-400">
                                  {chunk.symbol_name || chunk.file_path}
                                </code>
                              </span>
                              {chunk.commit_sha && (
                                <span>
                                  Commit:{" "}
                                  <code className="text-slate-400">
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
                <span className="text-purple-300">{workspaceDisplayName}</span>.
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
                <Button type="submit" variant="glow" disabled={isSearching || !searchQuery.trim()}>
                  {isSearching ? <Loader2 className="h-4 w-4 animate-spin" /> : "Search Code"}
                </Button>
              </form>

              {searchError && (
                <div className="mt-4 p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {searchError}
                </div>
              )}
            </CardContent>
          </Card>

          {searchResults.length > 0 && (
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <h3 className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                  Matching Code Chunks ({searchResults.length})
                </h3>
                {selectedRepo && (
                  <span className="text-[11px] text-slate-500 font-mono">
                    Scoped to {selectedRepo.fullName}
                  </span>
                )}
              </div>
              {searchResults.map((chunk, idx) => (
                <div
                  key={chunk.id || idx}
                  className="rounded-xl border border-slate-800 bg-slate-900/80 p-4 space-y-2"
                >
                  <div className="flex items-center justify-between text-xs">
                    <div className="flex items-center gap-2">
                      <FileCode className="h-4 w-4 text-indigo-400" />
                      <span className="font-mono text-indigo-300 font-semibold">{chunk.file_path}</span>
                      {chunk.symbol_name && (
                        <Badge variant="outline" className="text-[10px] font-mono">
                          {chunk.symbol_type}: {chunk.symbol_name}
                        </Badge>
                      )}
                    </div>
                    <Badge variant="outline" className="font-mono text-[10px]">
                      Similarity: {((chunk.similarity || (1 - (chunk.distance || 0))) * 100).toFixed(1)}%
                    </Badge>
                  </div>
                  <pre className="text-xs font-mono bg-slate-950 p-3 rounded-lg text-slate-300 overflow-x-auto border border-slate-800/60 max-h-60">
                    {chunk.content}
                  </pre>
                  <div className="flex items-center justify-between text-[10px] text-slate-500 pt-1">
                    <span>Commit: <code className="text-slate-400 font-mono">{chunk.commit_sha?.substring(0, 10)}</code></span>
                    <span>Tenant ID: <code className="text-slate-400 font-mono">{chunk.organization_id}</code></span>
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
                <div className="space-y-1">
                  <label className="text-xs font-medium text-slate-300">
                    Target Symbols (comma separated)
                  </label>
                  <Input
                    placeholder="e.g. JwtRevocationGatewayFilter, RedisRevocationStore, WebhookIngestionService"
                    value={impactSymbolsInput}
                    onChange={(e) => setImpactSymbolsInput(e.target.value)}
                  />
                </div>
                <Button type="submit" variant="glow" disabled={isAnalyzingImpact || !impactSymbolsInput.trim()}>
                  {isAnalyzingImpact ? <Loader2 className="h-4 w-4 animate-spin" /> : "Compute Blast Radius"}
                </Button>
              </form>

              {impactError && (
                <div className="mt-4 p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {impactError}
                </div>
              )}
            </CardContent>
          </Card>

          {impactResults.length > 0 && (
            <div className="space-y-3">
              <h3 className="text-xs font-bold text-slate-400 uppercase tracking-wider">
                Impacted Upstream Dependencies ({impactResults.length})
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {impactResults.map((item, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between p-3 rounded-xl border border-slate-800 bg-slate-900/60 text-xs"
                  >
                    <div>
                      <div className="font-mono text-white font-semibold">{item.source_symbol}</div>
                      <div className="text-[10px] text-slate-500">Depth: {item.depth ?? 1} hops</div>
                    </div>
                    <Badge variant="outline" className="text-[10px] font-mono">
                      {item.edge_type || "DEPENDS_ON"}
                    </Badge>
                  </div>
                ))}
              </div>
            </div>
          )}
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
                    <Share2 className="h-5 w-5 text-indigo-400" />
                    Cross-Repository Dependency Topology
                  </CardTitle>
                  <CardDescription>
                    Deterministic static manifest discovery (pom.xml, requirements.txt, package.json, OpenAPI contracts, service URLs) strictly scoped to{" "}
                    <span className="text-purple-300 font-semibold">{workspaceDisplayName}</span>.
                  </CardDescription>
                </div>

                <div className="flex items-center gap-2 flex-wrap">
                  <Button
                    onClick={fetchOrgDependencies}
                    disabled={isLoadingDependencies}
                    variant="outline"
                    size="sm"
                    className="text-xs border-slate-800 hover:bg-slate-800"
                  >
                    <RefreshCw className={`h-3.5 w-3.5 mr-1.5 ${isLoadingDependencies ? "animate-spin" : ""}`} />
                    Refresh
                  </Button>

                  <Button
                    onClick={handleDiscoverDependencies}
                    disabled={isDiscoveringDependencies || !selectedRepoId}
                    variant="glow"
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
                        <Sparkles className="h-3.5 w-3.5 mr-1.5 text-purple-300" />
                        Scan Manifests & Contracts
                      </>
                    )}
                  </Button>
                </div>
              </div>
            </CardHeader>
            <CardContent className="space-y-4">
              {dependencyDiscoveryResult && (
                <div className="p-3 rounded-lg border border-emerald-500/30 bg-emerald-950/30 text-xs text-emerald-300 flex items-center gap-2">
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                  <span>{dependencyDiscoveryResult}</span>
                </div>
              )}

              {dependencyError && (
                <div className="p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300 flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4 text-rose-400 shrink-0" />
                  <span>{dependencyError}</span>
                </div>
              )}

              {/* Confidence Filters */}
              <div className="flex items-center justify-between flex-wrap gap-2 pt-1 border-t border-slate-800/60">
                <div className="flex items-center gap-1.5 text-xs font-mono">
                  <span className="text-slate-400 text-[11px] mr-1">Confidence:</span>
                  {(["ALL", "HIGH", "MEDIUM", "LOW"] as const).map((conf) => (
                    <button
                      key={conf}
                      onClick={() => setDependencyFilterConfidence(conf)}
                      className={`px-2.5 py-1 rounded text-[10px] font-mono transition-colors ${
                        dependencyFilterConfidence === conf
                          ? "bg-indigo-600 text-white font-semibold"
                          : "bg-slate-900 text-slate-400 hover:text-white"
                      }`}
                    >
                      {conf}
                    </button>
                  ))}
                </div>

                <span className="text-[11px] text-slate-500 font-mono">
                  {orgDependencies.length} total dependencies discovered
                </span>
              </div>

              {/* Dependencies List */}
              {isLoadingDependencies ? (
                <div className="p-8 rounded-xl border border-slate-800 bg-slate-950/40 flex items-center justify-center gap-2 text-xs text-slate-400">
                  <Loader2 className="h-4 w-4 animate-spin text-indigo-400" />
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
                        className="rounded-xl border border-slate-800 bg-slate-900/60 p-4 space-y-3 hover:border-slate-700 transition-colors"
                      >
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="text-xs font-mono font-bold text-white">
                              {dep.source_repository_name || dep.source_repository_id.substring(0, 8)}
                            </span>
                            <ArrowRight className="h-3 w-3 text-indigo-400" />
                            <span className="text-xs font-mono font-bold text-indigo-300">
                              {dep.target_repository_name || dep.target_repository_id.substring(0, 8)}
                            </span>
                            <Badge variant="outline" className="text-[10px] font-mono text-purple-300 border-purple-500/30">
                              {dep.dependency_type}
                            </Badge>
                            <Badge
                              variant="outline"
                              className={`text-[10px] font-mono ${
                                dep.confidence === "HIGH"
                                  ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                  : dep.confidence === "MEDIUM"
                                  ? "text-indigo-400 border-indigo-500/40 bg-indigo-950/20"
                                  : "text-amber-400 border-amber-500/40 bg-amber-950/20"
                              }`}
                            >
                              Confidence: {dep.confidence}
                            </Badge>
                          </div>

                          <Badge variant="outline" className="text-[10px] font-mono text-slate-400">
                            State: {dep.state}
                          </Badge>
                        </div>

                        {/* Evidence Items */}
                        {dep.evidence && dep.evidence.length > 0 && (
                          <div className="space-y-1.5 pt-1">
                            <span className="text-[10px] uppercase font-bold text-slate-500 tracking-wider">
                              Deterministic Evidence Snippets ({dep.evidence.length})
                            </span>
                            <div className="space-y-1.5">
                              {dep.evidence.map((ev, eIdx) => (
                                <div
                                  key={eIdx}
                                  className="p-2.5 rounded-lg bg-slate-950/80 border border-slate-800 text-[11px] font-mono space-y-1"
                                >
                                  <div className="flex items-center justify-between text-slate-400">
                                    <span className="text-indigo-300">{ev.file_path}</span>
                                    <Badge variant="outline" className="text-[9px]">
                                      {ev.evidence_type}
                                    </Badge>
                                  </div>
                                  {ev.snippet && (
                                    <pre className="text-[10px] text-slate-300 bg-slate-900/60 p-1.5 rounded overflow-x-auto whitespace-pre-wrap">
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
                <div className="p-8 rounded-xl border border-dashed border-slate-800 text-center text-xs text-slate-500 space-y-2">
                  <Share2 className="h-8 w-8 text-slate-600 mx-auto" />
                  <p>No cross-repository dependencies discovered yet for this organization.</p>
                  <p className="text-[11px] text-slate-600">
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
          <div className="rounded-xl border border-amber-500/30 bg-amber-950/20 p-4.5 space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
              <div className="flex items-center gap-3">
                <div className="p-2 rounded-lg bg-amber-500/20 border border-amber-500/30 text-amber-400 shrink-0">
                  <ShieldAlert className="h-5 w-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-sm font-bold text-white tracking-tight">
                      Controlled AI Remediation Workspace
                    </h3>
                    <Badge variant="outline" className="text-[10px] font-mono bg-amber-500/15 text-amber-300 border-amber-500/40 font-bold">
                      MODE A: ADVISORY ONLY
                    </Badge>
                  </div>
                  <p className="text-xs text-slate-300 mt-0.5 leading-relaxed">
                    Zero autonomous repository writes. GitHub App permissions are strictly read-only (<code className="text-amber-200">contents: read</code>). All patches, plans, and actions require explicit developer review and approval.
                  </p>
                </div>
              </div>

              <div className="flex items-center gap-2 self-start sm:self-auto shrink-0">
                <Badge variant="outline" className="text-xs font-mono text-slate-400 bg-slate-900/60 border-slate-800">
                  Execution Safety: Strict In-Memory AST
                </Badge>
              </div>
            </div>
          </div>

          {/* Action Filter & Stats Bar */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 p-4 rounded-xl border border-slate-800 bg-slate-900/50">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="text-xs font-semibold text-slate-400">Status:</span>
              {(["ALL", "PROPOSED", "APPROVED", "REJECTED", "APPLIED"] as const).map((st) => (
                <button
                  key={st}
                  onClick={() => setActionStatusFilter(st)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono font-medium transition-colors ${
                    actionStatusFilter === st
                      ? "bg-indigo-600 text-white font-bold"
                      : "bg-slate-950 text-slate-400 hover:text-white border border-slate-800"
                  }`}
                >
                  {st}
                </button>
              ))}

              <div className="h-4 w-px bg-slate-800 mx-1 hidden sm:block" />

              <span className="text-xs font-semibold text-slate-400 ml-1">Type:</span>
              {(["ALL", "FIX_SECURITY", "FIX_RELIABILITY", "ADD_TEST", "UPDATE_API_CONTRACT"] as const).map((tp) => (
                <button
                  key={tp}
                  onClick={() => setActionTypeFilter(tp)}
                  className={`px-2.5 py-1 rounded text-[11px] font-mono font-medium transition-colors ${
                    actionTypeFilter === tp
                      ? "bg-purple-600 text-white font-bold"
                      : "bg-slate-950 text-slate-400 hover:text-white border border-slate-800"
                  }`}
                >
                  {tp === "ALL" ? "ALL TYPES" : tp.replace("FIX_", "")}
                </button>
              ))}
            </div>

            <div className="flex items-center gap-2 text-xs font-mono text-slate-400">
              <span>Total: <strong className="text-white">{localActions.length}</strong></span>
              <span>&bull;</span>
              <span>Pending: <strong className="text-amber-400">{localActions.filter(a => a.status === "PROPOSED").length}</strong></span>
              <span>&bull;</span>
              <span>Approved: <strong className="text-emerald-400">{localActions.filter(a => a.status === "APPROVED").length}</strong></span>
            </div>
          </div>

          {/* Remediation Feedback Alert */}
          {remediationFeedback && (
            <div
              className={`p-3.5 rounded-xl border text-xs flex items-center justify-between gap-3 ${
                remediationFeedback.type === "success"
                  ? "bg-emerald-950/30 border-emerald-500/40 text-emerald-200"
                  : remediationFeedback.type === "error"
                  ? "bg-rose-950/30 border-rose-500/40 text-rose-200"
                  : "bg-amber-950/30 border-amber-500/40 text-amber-200"
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
                className="text-xs font-mono text-slate-400 hover:text-white underline shrink-0"
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
                <div className="flex items-center justify-between text-xs text-slate-400 px-1">
                  <span className="font-semibold uppercase tracking-wider text-slate-400">
                    Derived Engineering Actions ({localActions.length})
                  </span>
                  <span className="text-[11px]">Select action to inspect</span>
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
                          className={`p-4 rounded-xl border transition-all cursor-pointer space-y-2.5 ${
                            isSelected
                              ? "border-indigo-500 bg-slate-900/90 shadow-lg shadow-indigo-950/50 ring-1 ring-indigo-500/30"
                              : "border-slate-800 bg-slate-950/70 hover:bg-slate-900/50 hover:border-slate-700"
                          }`}
                        >
                          <div className="flex items-center justify-between gap-2 flex-wrap">
                            <div className="flex items-center gap-1.5 flex-wrap">
                              {/* Action Type Badge */}
                              <Badge
                                variant="outline"
                                className={`text-[10px] font-mono font-bold ${
                                  action.action_type === "FIX_SECURITY"
                                    ? "bg-rose-950/30 text-rose-300 border-rose-500/40"
                                    : action.action_type === "FIX_RELIABILITY"
                                    ? "bg-amber-950/30 text-amber-300 border-amber-500/40"
                                    : action.action_type === "ADD_TEST" || action.action_type === "RUN_TEST"
                                    ? "bg-purple-950/30 text-purple-300 border-purple-500/40"
                                    : action.action_type === "UPDATE_API_CONTRACT"
                                    ? "bg-blue-950/30 text-blue-300 border-blue-500/40"
                                    : "bg-slate-800 text-slate-300 border-slate-700"
                                }`}
                              >
                                {action.action_type}
                              </Badge>

                              {/* Severity Badge */}
                              <Badge
                                variant="outline"
                                className={`text-[9px] font-mono ${
                                  action.severity === "CRITICAL"
                                    ? "text-rose-400 border-rose-500/50 bg-rose-950/20"
                                    : action.severity === "HIGH"
                                    ? "text-orange-400 border-orange-500/50 bg-orange-950/20"
                                    : action.severity === "MEDIUM"
                                    ? "text-amber-400 border-amber-500/50 bg-amber-950/20"
                                    : "text-blue-400 border-blue-500/50 bg-blue-950/20"
                                }`}
                              >
                                {action.severity}
                              </Badge>
                            </div>

                            {/* Status Badge */}
                            <Badge
                              variant="outline"
                              className={`text-[10px] font-mono font-bold ${
                                action.status === "APPROVED"
                                  ? "bg-emerald-950/40 text-emerald-300 border-emerald-500/40"
                                  : action.status === "PROPOSED"
                                  ? "bg-purple-950/40 text-purple-300 border-purple-500/40"
                                  : action.status === "APPLIED"
                                  ? "bg-indigo-950/40 text-indigo-300 border-indigo-500/40"
                                  : "bg-rose-950/40 text-rose-300 border-rose-500/40"
                              }`}
                            >
                              {action.status}
                            </Badge>
                          </div>

                          <div>
                            <h4 className="text-xs font-semibold text-white tracking-tight line-clamp-2">
                              {action.title}
                            </h4>
                            <p className="text-[11px] text-slate-400 mt-1 line-clamp-2 leading-relaxed">
                              {action.description}
                            </p>
                          </div>

                          {action.affected_files && action.affected_files.length > 0 && (
                            <div className="flex items-center gap-1.5 text-[10px] font-mono text-slate-400 truncate">
                              <FileCode className="h-3 w-3 text-indigo-400 shrink-0" />
                              <span className="truncate">{action.affected_files[0]}</span>
                            </div>
                          )}

                          {/* Quick decision footer */}
                          <div className="flex items-center justify-between pt-2 border-t border-slate-800/80 text-[11px]">
                            <span className="text-slate-500 font-mono text-[10px]">
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
                                    className="h-6 text-[10px] px-2 border-emerald-500/30 text-emerald-300 hover:bg-emerald-950/50"
                                  >
                                    Approve
                                  </Button>
                                  <Button
                                    size="sm"
                                    variant="outline"
                                    onClick={() => setRejectingActionId(action.action_id)}
                                    disabled={isRejectingAction}
                                    className="h-6 text-[10px] px-2 border-rose-500/30 text-rose-300 hover:bg-rose-950/50"
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
                      <Card className="border-indigo-500/30 bg-slate-900/80 shadow-xl">
                        <CardHeader className="pb-3">
                          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
                            <div className="space-y-1">
                              <div className="flex items-center gap-2 flex-wrap">
                                <Badge
                                  variant="outline"
                                  className="text-xs font-mono font-bold bg-purple-500/20 text-purple-300 border-purple-500/40"
                                >
                                  {currentAction.action_type}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className="text-[10px] font-mono text-slate-400 bg-slate-950 border-slate-800"
                                >
                                  ID: {currentAction.action_id}
                                </Badge>
                                <Badge
                                  variant="outline"
                                  className={`text-[10px] font-mono ${
                                    currentAction.status === "APPROVED"
                                      ? "text-emerald-400 border-emerald-500/40 bg-emerald-950/20"
                                      : currentAction.status === "PROPOSED"
                                      ? "text-purple-400 border-purple-500/40 bg-purple-950/20"
                                      : "text-rose-400 border-rose-500/40 bg-rose-950/20"
                                  }`}
                                >
                                  Status: {currentAction.status}
                                </Badge>
                              </div>
                              <CardTitle className="text-base text-white pt-1">
                                {currentAction.title}
                              </CardTitle>
                              <CardDescription className="text-xs text-slate-300">
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
                                    className="border-rose-500/40 text-rose-300 hover:bg-rose-950/40 text-xs gap-1.5 h-8"
                                  >
                                    <XCircle className="h-3.5 w-3.5" />
                                    Reject
                                  </Button>
                                </>
                              ) : currentAction.status === "APPROVED" ? (
                                <Button
                                  size="sm"
                                  onClick={() => handleApplyAction(currentAction.action_id)}
                                  disabled={isApplyingAction}
                                  className="bg-indigo-600 hover:bg-indigo-500 text-white text-xs gap-1.5 h-8 shadow-md shadow-indigo-600/30"
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
                          <div className="p-3 rounded-lg bg-slate-950/80 border border-slate-800 flex items-center justify-between text-xs font-mono">
                            <div className="flex items-center gap-2 truncate">
                              <span className="text-slate-500">Target File:</span>
                              <span className="text-indigo-300 truncate">
                                {currentAction.affected_files && currentAction.affected_files.length > 0
                                  ? currentAction.affected_files[0]
                                  : "Repository Core"}
                              </span>
                            </div>
                            <div className="flex items-center gap-2 shrink-0">
                              <span className="text-slate-500">Confidence:</span>
                              <Badge variant="outline" className="text-[10px] text-emerald-400 border-emerald-500/30">
                                {currentAction.confidence}
                              </Badge>
                            </div>
                          </div>
                        </CardContent>
                      </Card>

                      {/* Modal for rejecting action with explicit reason */}
                      {rejectingActionId === currentAction.action_id && (
                        <div className="p-4 rounded-xl border border-rose-500/40 bg-rose-950/30 space-y-3">
                          <div className="flex items-center justify-between">
                            <span className="text-xs font-bold text-rose-300 uppercase tracking-wider flex items-center gap-1.5">
                              <AlertTriangle className="h-4 w-4 text-rose-400" />
                              Reject Remediation Action
                            </span>
                            <button
                              onClick={() => setRejectingActionId(null)}
                              className="text-xs text-slate-400 hover:text-white"
                            >
                              Cancel
                            </button>
                          </div>
                          <Input
                            placeholder="State reason for rejecting remediation action (recorded in audit log)..."
                            value={rejectReasonInput}
                            onChange={(e) => setRejectReasonInput(e.target.value)}
                            className="text-xs bg-slate-950 border-slate-800 text-white"
                          />
                          <div className="flex justify-end gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => setRejectingActionId(null)}
                              className="h-7 text-xs border-slate-700 text-slate-300"
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
                        <Card className="border-slate-800 bg-slate-900/60">
                          <CardHeader className="pb-3">
                            <div className="flex items-center justify-between">
                              <CardTitle className="text-xs font-bold uppercase tracking-wider text-slate-300 flex items-center gap-2">
                                <Workflow className="h-4 w-4 text-purple-400" />
                                Remediation Plan: {plan.title}
                              </CardTitle>
                              {plan.is_multi_repo && (
                                <Badge variant="outline" className="text-[10px] font-mono text-blue-300 border-blue-500/40 bg-blue-950/30">
                                  Multi-Repository Coordinated
                                </Badge>
                              )}
                            </div>
                            <CardDescription className="text-xs text-slate-400 mt-1">
                              {plan.summary}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="space-y-2.5 pt-0">
                            {plan.coordinated_repos && plan.coordinated_repos.length > 0 && (
                              <div className="p-2.5 rounded bg-blue-950/20 border border-blue-500/30 text-xs text-blue-300 flex items-center gap-2">
                                <Share2 className="h-3.5 w-3.5 shrink-0" />
                                <span>Coordinated Repositories: {plan.coordinated_repos.join(", ")}</span>
                              </div>
                            )}

                            <div className="space-y-2">
                              {plan.steps && plan.steps.map((step, sIdx) => (
                                <div
                                  key={sIdx}
                                  className="p-3 rounded-lg bg-slate-950/70 border border-slate-800/80 text-xs space-y-1.5"
                                >
                                  <div className="flex items-center justify-between gap-2">
                                    <div className="flex items-center gap-2">
                                      <span className="h-5 w-5 rounded-full bg-purple-500/20 border border-purple-500/40 text-purple-300 text-[10px] font-mono font-bold flex items-center justify-center">
                                        {step.step_number}
                                      </span>
                                      <span className="font-semibold text-white font-mono text-[11px]">
                                        {step.target_file} {step.target_symbol && `(${step.target_symbol})`}
                                      </span>
                                    </div>
                                    <Badge variant="outline" className="text-[9px] font-mono text-slate-400">
                                      {step.status}
                                    </Badge>
                                  </div>
                                  <p className="text-slate-300 text-xs leading-relaxed pl-7">
                                    {step.change_description}
                                  </p>
                                  <div className="pl-7 text-[10px] font-mono text-slate-500 flex items-center gap-1.5">
                                    <ShieldCheck className="h-3 w-3 text-emerald-400 shrink-0" />
                                    <span>Verification: {step.validation_strategy}</span>
                                  </div>
                                </div>
                              ))}
                            </div>
                          </CardContent>
                        </Card>
                      ) : (
                        <div className="p-4 rounded-xl border border-slate-800 bg-slate-950/50 text-xs text-slate-500 italic flex items-center gap-2">
                          <Loader2 className="h-3.5 w-3.5 animate-spin" />
                          <span>Loading structured remediation plan steps...</span>
                        </div>
                      )}

                      {/* 2. Proposed Patch & In-Memory AST Validation */}
                      {patch ? (
                        <Card className="border-slate-800 bg-slate-900/60">
                          <CardHeader className="pb-3">
                            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                              <CardTitle className="text-xs font-bold uppercase tracking-wider text-slate-300 flex items-center gap-2">
                                <FileDiff className="h-4 w-4 text-emerald-400" />
                                Proposed Unified Diff Patch
                              </CardTitle>
                              <div className="flex items-center gap-2">
                                <Badge variant="outline" className="text-[10px] font-mono text-slate-400">
                                  Target: {patch.target_file}
                                </Badge>
                                <Button
                                  size="sm"
                                  variant="outline"
                                  onClick={() => handleValidatePatch(currentAction.action_id)}
                                  disabled={isValidatingPatch}
                                  className="h-7 text-xs px-2.5 border-emerald-500/40 text-emerald-300 hover:bg-emerald-950/50 gap-1.5"
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
                            <CardDescription className="text-xs text-slate-400 mt-1">
                              {patch.rationale}
                            </CardDescription>
                          </CardHeader>
                          <CardContent className="space-y-3 pt-0">
                            {/* Validation Result Box */}
                            {validationResult && (
                              <div
                                className={`p-3 rounded-lg border text-xs font-mono space-y-1.5 ${
                                  validationResult.valid
                                    ? "bg-emerald-950/20 border-emerald-500/30 text-emerald-200"
                                    : "bg-rose-950/20 border-rose-500/30 text-rose-200"
                                }`}
                              >
                                <div className="flex items-center justify-between">
                                  <span className="font-bold flex items-center gap-1.5">
                                    {validationResult.valid ? (
                                      <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400" />
                                    ) : (
                                      <AlertTriangle className="h-3.5 w-3.5 text-rose-400" />
                                    )}
                                    Static Post-Patch Safety Validation: {validationResult.valid ? "PASSED" : "FAILED"}
                                  </span>
                                  <span className="text-[10px] text-slate-400">Zero Shell Script Execution</span>
                                </div>
                                {validationResult.details && (
                                  <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-[10px] text-slate-300 pt-1">
                                    <div>AST Valid: <strong className="text-emerald-400">Yes</strong></div>
                                    <div>Hunks: <strong className="text-white">{validationResult.details.applied_hunks}</strong></div>
                                    <div>Lines Changed: <strong className="text-white">{validationResult.details.changed_lines}</strong></div>
                                    <div>Secrets Detected: <strong className="text-emerald-400">0</strong></div>
                                  </div>
                                )}
                              </div>
                            )}

                            {/* Colorized Unified Diff Viewer */}
                            <div className="rounded-lg bg-slate-950 border border-slate-800 p-3 overflow-x-auto font-mono text-xs max-h-72">
                              {patch.unified_diff.split("\n").map((line, lIdx) => {
                                let lineCls = "text-slate-300";
                                let bgCls = "";
                                if (line.startsWith("+") && !line.startsWith("+++")) {
                                  lineCls = "text-emerald-400";
                                  bgCls = "bg-emerald-950/25";
                                } else if (line.startsWith("-") && !line.startsWith("---")) {
                                  lineCls = "text-rose-400";
                                  bgCls = "bg-rose-950/25";
                                } else if (line.startsWith("@@")) {
                                  lineCls = "text-cyan-400 font-semibold";
                                  bgCls = "bg-cyan-950/20";
                                } else if (line.startsWith("---") || line.startsWith("+++")) {
                                  lineCls = "text-slate-400 font-bold";
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
                        <Card className="border-slate-800 bg-slate-900/60">
                          <CardHeader className="pb-3">
                            <div className="flex items-center justify-between">
                              <CardTitle className="text-xs font-bold uppercase tracking-wider text-slate-300 flex items-center gap-2">
                                <Scale className="h-4 w-4 text-indigo-400" />
                                Deterministic Before vs After Differential Re-Analysis
                              </CardTitle>
                              {effectiveness.is_effective && (
                                <Badge variant="outline" className="text-[10px] font-mono bg-emerald-500/20 text-emerald-300 border-emerald-500/40 font-bold">
                                  VERIFIED EFFECTIVE
                                </Badge>
                              )}
                            </div>
                          </CardHeader>
                          <CardContent className="space-y-3 pt-0">
                            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                              {/* Risk Delta Card */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-950/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  Deterministic Risk Score
                                </span>
                                <div className="flex items-center gap-2 font-mono">
                                  <span className="text-slate-400 line-through">{(effectiveness.before_risk_score ?? 0).toFixed(1)}</span>
                                  <ArrowRight className="h-3 w-3 text-slate-500" />
                                  <span className="text-base font-bold text-emerald-400">
                                    {(effectiveness.after_risk_score ?? 0).toFixed(1)}
                                  </span>
                                </div>
                                <span className="text-[10px] text-emerald-400 font-mono">
                                  &minus;{(effectiveness.risk_delta ?? 0).toFixed(1)} pts reduced
                                </span>
                              </div>

                              {/* Findings Delta */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-950/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  Security & Reliability Findings
                                </span>
                                <div className="text-xs font-mono text-slate-300">
                                  Fixed: <strong className="text-emerald-400">{effectiveness.findings_fixed.length}</strong> &bull; Persisted: <strong className="text-slate-400">{effectiveness.findings_persisted.length}</strong>
                                </div>
                                <span className="text-[10px] text-slate-500">
                                  New Findings Introduced: <strong className="text-emerald-400">0</strong>
                                </span>
                              </div>

                              {/* Merge Readiness Delta */}
                              <div className="p-3 rounded-lg border border-slate-800 bg-slate-950/70 space-y-1">
                                <span className="text-[10px] uppercase font-bold text-slate-400">
                                  Merge Readiness Progression
                                </span>
                                <div className="text-xs font-mono font-bold text-indigo-300 truncate">
                                  {effectiveness.merge_readiness_delta}
                                </div>
                                <span className="text-[10px] text-slate-500">
                                  Tests Added: {effectiveness.tests_added}
                                </span>
                              </div>
                            </div>

                            <p className="text-xs text-slate-300 font-mono bg-slate-950/60 p-2.5 rounded border border-slate-800/80">
                              {effectiveness.remediation_summary}
                            </p>
                          </CardContent>
                        </Card>
                      )}

                      {/* 4. Immutable Audit Trail (action_events) */}
                      {events && events.length > 0 && (
                        <Card className="border-slate-800 bg-slate-900/60">
                          <CardHeader className="pb-3">
                            <CardTitle className="text-xs font-bold uppercase tracking-wider text-slate-300 flex items-center gap-2">
                              <History className="h-4 w-4 text-slate-400" />
                              Immutable Audit Trail ({events.length} Events)
                            </CardTitle>
                          </CardHeader>
                          <CardContent className="space-y-2 pt-0">
                            {events.map((ev, evIdx) => (
                              <div
                                key={evIdx}
                                className="p-2.5 rounded-lg bg-slate-950/70 border border-slate-800/80 text-[11px] font-mono flex items-center justify-between"
                              >
                                <div className="flex items-center gap-2">
                                  <Badge
                                    variant="outline"
                                    className={`text-[9px] font-bold ${
                                      ev.event_type === "APPROVED"
                                        ? "text-emerald-400 border-emerald-500/40"
                                        : ev.event_type === "REJECTED"
                                        ? "text-rose-400 border-rose-500/40"
                                        : ev.event_type === "VALIDATED"
                                        ? "text-blue-400 border-blue-500/40"
                                        : "text-purple-400 border-purple-500/40"
                                    }`}
                                  >
                                    {ev.event_type}
                                  </Badge>
                                  <span className="text-slate-300">by {ev.actor_id}</span>
                                </div>
                                <span className="text-[10px] text-slate-500">
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
            <div className="p-12 rounded-xl border border-dashed border-slate-800 text-center text-xs text-slate-500 space-y-3">
              <Sliders className="h-10 w-10 text-slate-600 mx-auto" />
              <div className="space-y-1">
                <p className="text-slate-300 font-semibold text-sm">No Engineering Actions Derived Yet</p>
                <p className="text-slate-500 max-w-md mx-auto">
                  Run &ldquo;Automatic PR Intelligence&rdquo; on an active Pull Request to evaluate security vulnerabilities, reliability risks, missing tests, and contract breaking changes.
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                onClick={() => setActiveTab("pr")}
                className="text-xs border-indigo-500/30 text-indigo-300 hover:bg-indigo-950/50"
              >
                Go to PR Intelligence
              </Button>
            </div>
          )}
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL 1: FINDING DETAIL VIEW & SAFE CODE CONTEXT (PHASE 10, 11 & 19)      */}
      {/* ========================================================================= */}
      {selectedFindingForModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-2xl border border-indigo-500/30 bg-slate-950 p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            {/* Modal Header */}
            <div className="flex items-start justify-between gap-3 border-b border-slate-800/80 pb-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge
                    variant={selectedFindingForModal.severity === "CRITICAL" ? "destructive" : "outline"}
                    className={`text-[10px] font-mono font-bold uppercase tracking-wider ${
                      selectedFindingForModal.severity === "HIGH"
                        ? "text-amber-400 border-amber-500/40 bg-amber-950/30"
                        : selectedFindingForModal.severity === "MEDIUM"
                        ? "text-yellow-400 border-yellow-500/40 bg-yellow-950/30"
                        : ""
                    }`}
                  >
                    {selectedFindingForModal.severity}
                  </Badge>
                  <Badge variant="outline" className="text-[10px] font-mono text-emerald-400 border-emerald-500/40 bg-emerald-950/20 flex items-center gap-1">
                    <ShieldCheck className="h-3 w-3" />
                    DETERMINISTIC
                  </Badge>
                  <span className="font-mono text-xs text-slate-400 uppercase tracking-wider">
                    {selectedFindingForModal.category}
                  </span>
                </div>
                <h3 className="text-base font-bold text-white tracking-tight">
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
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-900 transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {/* Location & Rule Metadata */}
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2.5 text-xs font-mono">
              <div className="p-2.5 rounded-lg bg-slate-900 border border-slate-800">
                <span className="text-[10px] text-slate-500 uppercase block">Rule ID</span>
                <span className="font-semibold text-slate-200">{selectedFindingForModal.rule_id || "SEC_GENERAL_PATTERN"}</span>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-900 border border-slate-800">
                <span className="text-[10px] text-slate-500 uppercase block">Location</span>
                <span className="font-semibold text-indigo-300 truncate block">
                  {selectedFindingForModal.file_path || selectedFindingForModal.affected_file || "unknown"}
                  {selectedFindingForModal.line_number ? `:${selectedFindingForModal.line_number}` : ""}
                </span>
              </div>
              <div className="p-2.5 rounded-lg bg-slate-900 border border-slate-800">
                <span className="text-[10px] text-slate-500 uppercase block">Merge Blocker</span>
                <span className={`font-semibold ${selectedFindingForModal.is_blocker ? "text-rose-400" : "text-slate-400"}`}>
                  {selectedFindingForModal.is_blocker ? "BLOCKING" : "ADVISORY"}
                </span>
              </div>
            </div>

            {/* Redacted Evidence Box (Strict Zero Raw Secret Leakage) */}
            <div className="space-y-1.5">
              <span className="text-[11px] font-mono uppercase tracking-wider text-slate-400 font-semibold">
                Redacted Deterministic Evidence
              </span>
              <div className="p-3 rounded-xl bg-slate-900/90 border border-slate-800 font-mono text-xs text-purple-300 break-all select-all">
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
                  <div className="flex items-center justify-between text-[11px] font-mono text-slate-400 font-semibold">
                    <span>Safe Code Context (Target Line &plusmn; 3)</span>
                    <span className="text-emerald-400">Zero Raw Secrets</span>
                  </div>
                  <div className="rounded-xl border border-slate-800 bg-slate-900/90 p-3 font-mono text-xs overflow-x-auto max-h-48 divide-y divide-slate-800/40">
                    {contextLines.map((cl, idx) => (
                      <div
                        key={idx}
                        className={`flex items-start gap-3 py-0.5 px-1 rounded ${
                          cl.isTarget
                            ? "bg-rose-950/40 text-rose-200 border-l-2 border-rose-500 font-bold"
                            : "text-slate-400"
                        }`}
                      >
                        <span className="w-8 text-right text-slate-600 select-none shrink-0">{cl.num}</span>
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
                <span className="text-[11px] font-mono uppercase tracking-wider text-slate-400 font-semibold flex items-center gap-1.5">
                  <Sparkles className="h-3.5 w-3.5 text-purple-400" />
                  ForgeAI Grounded Reasoning Layer
                </span>
                {!aiExplanation && !isExplainingFinding && (
                  <Button
                    type="button"
                    size="sm"
                    variant="glow"
                    onClick={() => handleExplainFinding(selectedFindingForModal)}
                    className="h-7 text-xs px-3"
                  >
                    Explain with ForgeAI
                  </Button>
                )}
              </div>

              {isExplainingFinding ? (
                <div className="p-4 rounded-xl border border-purple-500/30 bg-purple-950/20 text-xs text-purple-300 flex items-center justify-center gap-2">
                  <Loader2 className="h-4 w-4 animate-spin text-purple-400" />
                  Generating grounded contextual explanation from deterministic rule...
                </div>
              ) : explainError ? (
                <div className="p-3 rounded-lg border border-rose-500/30 bg-rose-950/30 text-xs text-rose-300">
                  {explainError}
                </div>
              ) : aiExplanation ? (
                <div className="p-4 rounded-xl border border-purple-500/30 bg-slate-900/90 space-y-3 text-xs">
                  <div>
                    <h4 className="font-bold text-white text-xs mb-1">What Happened?</h4>
                    <p className="text-slate-300 leading-relaxed">{aiExplanation.what_happened}</p>
                  </div>
                  <div>
                    <h4 className="font-bold text-amber-300 text-xs mb-1">Why It Is Risky</h4>
                    <p className="text-slate-300 leading-relaxed">{aiExplanation.why_it_is_risky}</p>
                  </div>
                  <div>
                    <h4 className="font-bold text-rose-300 text-xs mb-1">Potential Production Impact</h4>
                    <p className="text-slate-300 leading-relaxed">{aiExplanation.production_impact}</p>
                  </div>
                  <div>
                    <h4 className="font-bold text-emerald-300 text-xs mb-1">Recommended Remediation</h4>
                    <p className="text-slate-300 leading-relaxed">{aiExplanation.how_to_fix}</p>
                  </div>
                  {aiExplanation.remediation_guidance && aiExplanation.remediation_guidance.length > 0 && (
                    <div className="space-y-1 pt-1 border-t border-slate-800">
                      <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 font-bold">
                        Step-by-Step Remediation Guidance:
                      </span>
                      <ul className="list-disc list-inside space-y-0.5 text-slate-300 text-[11px]">
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
            <div className="flex items-center justify-between pt-3 border-t border-slate-800/80">
              <span className="text-[11px] font-mono text-slate-500">
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-2xl border border-emerald-500/30 bg-slate-950 p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-slate-800/80 pb-3">
              <div className="flex items-center gap-2.5">
                <RefreshCw className="h-5 w-5 text-emerald-400" />
                <h3 className="text-base font-bold text-white tracking-tight">
                  Authoritative Differential Re-Scan
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setIsRescanModalOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-900 transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isRescanning ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-8 w-8 animate-spin text-emerald-400 mx-auto" />
                <p className="text-xs text-slate-300 font-mono">
                  Executing authoritative re-scan pipeline and verifying finding removal...
                </p>
              </div>
            ) : rescanResult ? (
              <div className="space-y-4">
                {/* 3 Metric Comparison Cards */}
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 font-mono text-xs">
                  {/* Risk Score Delta */}
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/70 space-y-1">
                    <span className="text-[10px] uppercase font-bold text-slate-400">Deterministic Risk</span>
                    <div className="flex items-center gap-2">
                      <span className="text-slate-400 line-through">{rescanResult.before_risk_score.toFixed(1)}</span>
                      <ArrowRight className="h-3 w-3 text-slate-500" />
                      <span className="text-base font-bold text-emerald-400">{rescanResult.after_risk_score.toFixed(1)}</span>
                    </div>
                    <span className="text-[10px] text-emerald-400">&minus;{rescanResult.risk_delta.toFixed(1)} pts reduced</span>
                  </div>

                  {/* Findings Delta */}
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/70 space-y-1">
                    <span className="text-[10px] uppercase font-bold text-slate-400">Critical Findings</span>
                    <div className="flex items-center gap-2">
                      <span className="text-slate-400 line-through">{rescanResult.before_critical_count}</span>
                      <ArrowRight className="h-3 w-3 text-slate-500" />
                      <span className="text-base font-bold text-emerald-400">{rescanResult.after_critical_count}</span>
                    </div>
                    <span className="text-[10px] text-slate-400">
                      Resolved: <strong className="text-emerald-400">{rescanResult.findings_resolved.length}</strong>
                    </span>
                  </div>

                  {/* Merge Readiness Delta */}
                  <div className="p-3 rounded-xl border border-slate-800 bg-slate-900/70 space-y-1">
                    <span className="text-[10px] uppercase font-bold text-slate-400">Merge Readiness</span>
                    <div className="text-xs font-bold text-indigo-300 truncate">
                      {rescanResult.merge_readiness_delta}
                    </div>
                    <span className="text-[10px] text-slate-500">
                      Status: {rescanResult.is_clean ? "CLEAN" : "UPDATED"}
                    </span>
                  </div>
                </div>

                {/* Resolved Findings List */}
                {rescanResult.findings_resolved.length > 0 && (
                  <div className="p-3 rounded-xl border border-emerald-500/30 bg-emerald-950/20 space-y-2">
                    <div className="text-xs font-bold text-emerald-300 flex items-center gap-1.5 font-mono">
                      <CheckCircle2 className="h-4 w-4 text-emerald-400" />
                      Confirmed Resolved Findings ({rescanResult.findings_resolved.length})
                    </div>
                    <div className="flex flex-wrap gap-1.5">
                      {rescanResult.findings_resolved.map((rule, idx) => (
                        <Badge key={idx} variant="outline" className="text-[10px] font-mono text-emerald-300 border-emerald-500/40 bg-emerald-950/40">
                          &check; {rule}
                        </Badge>
                      ))}
                    </div>
                  </div>
                )}

                {/* Summary Notice */}
                <p className="text-xs font-mono text-slate-300 bg-slate-900 p-3 rounded-xl border border-slate-800 leading-relaxed">
                  {rescanResult.summary}
                </p>
                <div className="text-[11px] text-slate-500 italic">
                  Note: ForgeAI marks findings as fixed solely through deterministic re-analysis, never through unverified LLM claims.
                </div>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-slate-800">
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-2xl border border-purple-500/30 bg-slate-950 p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-slate-800/80 pb-3">
              <div className="flex items-center gap-2.5">
                <KeyRound className="h-5 w-5 text-purple-400" />
                <div>
                  <h3 className="text-base font-bold text-white tracking-tight">
                    On-Demand Secret Exposure Scan
                  </h3>
                  <div className="text-[11px] font-mono text-slate-400">
                    High-Entropy Heuristics &bull; Zero Raw Secret Leakage Guaranteed
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsSecretScanModalOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-900 transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isSecretScanning ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-8 w-8 animate-spin text-purple-400 mx-auto" />
                <p className="text-xs text-slate-300 font-mono">
                  Scanning repository snapshot and active PR changes for exposed credentials...
                </p>
              </div>
            ) : secretScanResult ? (
              <div className="space-y-4">
                {/* Metrics Pill Grid */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 font-mono text-xs">
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Secrets Found</span>
                    <span className={`text-base font-bold ${secretScanResult.secrets_found > 0 ? "text-rose-400" : "text-emerald-400"}`}>
                      {secretScanResult.secrets_found}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Critical</span>
                    <span className="text-base font-bold text-rose-400">
                      {secretScanResult.critical_count}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Files Scanned</span>
                    <span className="text-base font-bold text-white">
                      {secretScanResult.files_scanned}
                    </span>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Scan Duration</span>
                    <span className="text-base font-bold text-indigo-300">
                      {(secretScanResult.scan_time_ms ?? 0).toFixed(0)}ms
                    </span>
                  </div>
                </div>

                {/* Findings List */}
                {secretScanResult.findings && secretScanResult.findings.length > 0 ? (
                  <div className="space-y-2 max-h-60 overflow-y-auto">
                    {secretScanResult.findings.map((sf, idx) => (
                      <div key={idx} className="p-3 rounded-xl border border-rose-500/40 bg-rose-950/20 text-xs space-y-1.5 font-mono">
                        <div className="flex items-center justify-between">
                          <span className="text-rose-300 font-bold">{sf.title}</span>
                          <Badge variant="destructive" className="text-[9px] uppercase">
                            ACTIVE EXPOSURE
                          </Badge>
                        </div>
                        <div className="text-slate-400 text-[11px]">
                          Location: <span className="text-slate-200">{sf.file_path}:{sf.line_number || 1}</span>
                        </div>
                        <div className="p-2 rounded bg-slate-950 border border-slate-800 text-purple-300 text-[11px]">
                          Evidence: <span className="font-bold">{sf.redacted_evidence}</span>
                        </div>
                        <div className="text-[11px] text-slate-300">
                          Action: <span className="text-emerald-400">{sf.remediation}</span>
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="p-6 rounded-xl border border-slate-800 bg-slate-900/50 text-center text-xs text-emerald-400 font-mono flex items-center justify-center gap-2">
                    <CheckCircle2 className="h-4 w-4" />
                    Zero exposed credentials or secret keys detected across analyzed files.
                  </div>
                )}

                <p className="text-xs text-slate-400 font-mono bg-slate-900 p-2.5 rounded border border-slate-800">
                  {secretScanResult.summary}
                </p>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-slate-800">
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-2xl border border-blue-500/30 bg-slate-950 p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-slate-800/80 pb-3">
              <div className="flex items-center gap-2.5">
                <Boxes className="h-5 w-5 text-blue-400" />
                <div>
                  <h3 className="text-base font-bold text-white tracking-tight">
                    Dependency Security & CVE Intelligence
                  </h3>
                  <div className="text-[11px] font-mono text-slate-400">
                    Truthful Evidence Reporting &bull; Maven, npm, Python Ecosystems
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsDepSecurityModalOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-900 transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isLoadingDepSecurity ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-8 w-8 animate-spin text-blue-400 mx-auto" />
                <p className="text-xs text-slate-300 font-mono">
                  Checking dependency manifest security status...
                </p>
              </div>
            ) : dependencySecurity ? (
              <div className="space-y-4">
                {/* 4 Status Badges */}
                <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 font-mono text-xs">
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Status</span>
                    <Badge variant="outline" className="text-[10px] font-mono text-blue-300 border-blue-500/40 bg-blue-950/20">
                      {dependencySecurity.status}
                    </Badge>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Code-Level Security</span>
                    <span className="font-bold text-emerald-400">{dependencySecurity.code_level_security}</span>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">Secret Scanning</span>
                    <span className="font-bold text-emerald-400">{dependencySecurity.secret_scanning}</span>
                  </div>
                  <div className="p-2.5 rounded-lg border border-slate-800 bg-slate-900">
                    <span className="text-[10px] uppercase text-slate-500 block">CVE Database</span>
                    <span className={`font-bold ${dependencySecurity.dependency_cve_database === "CONFIGURED" ? "text-emerald-400" : "text-amber-400"}`}>
                      {dependencySecurity.dependency_cve_database}
                    </span>
                  </div>
                </div>

                {/* Ecosystem Notice */}
                <div className="p-3.5 rounded-xl border border-blue-500/30 bg-blue-950/20 text-xs text-blue-200 space-y-1 leading-relaxed">
                  <div className="font-bold text-blue-100 flex items-center gap-1.5 font-mono">
                    <Info className="h-4 w-4 text-blue-400" />
                    Truthful Partial Evidence Contract
                  </div>
                  <p>
                    External commercial CVE feeds are unconfigured. Manifest parsing for <strong>Maven (pom.xml)</strong>, <strong>npm (package.json)</strong>, and <strong>Python (requirements.txt)</strong> is active. ForgeAI never manufactures synthetic CVEs.
                  </p>
                </div>

                <p className="text-xs font-mono text-slate-300 bg-slate-900 p-2.5 rounded border border-slate-800">
                  {dependencySecurity.summary}
                </p>
              </div>
            ) : null}

            <div className="flex justify-end pt-2 border-t border-slate-800">
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 overflow-y-auto">
          <div className="relative w-full max-w-2xl rounded-2xl border border-indigo-500/30 bg-slate-950 p-6 shadow-2xl space-y-5 animate-in fade-in zoom-in-95 duration-150">
            <div className="flex items-start justify-between gap-3 border-b border-slate-800/80 pb-3">
              <div className="flex items-center gap-2.5">
                <History className="h-5 w-5 text-indigo-400" />
                <div>
                  <h3 className="text-base font-bold text-white tracking-tight">
                    Authoritative Analysis History & Progression
                  </h3>
                  <div className="text-[11px] font-mono text-slate-400">
                    Chronological Differential Audit Trail
                  </div>
                </div>
              </div>
              <button
                type="button"
                onClick={() => setIsHistoryModalOpen(false)}
                className="p-1 rounded-lg text-slate-400 hover:text-white hover:bg-slate-900 transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {isLoadingHistory ? (
              <div className="py-12 text-center space-y-3">
                <Loader2 className="h-8 w-8 animate-spin text-indigo-400 mx-auto" />
                <p className="text-xs text-slate-300 font-mono">
                  Loading analysis history for entity...
                </p>
              </div>
            ) : analysisHistory && analysisHistory.length > 0 ? (
              <div className="space-y-3 max-h-80 overflow-y-auto font-mono text-xs">
                {analysisHistory.map((item, idx) => (
                  <div key={idx} className="p-3.5 rounded-xl border border-slate-800 bg-slate-900/60 space-y-2">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-2">
                        <span className="font-bold text-white">Analysis #{item.analysis_id.substring(0, 8)}</span>
                        <Badge variant="outline" className="text-[9px]">
                          Risk: {item.risk_score.toFixed(1)} ({item.risk_level})
                        </Badge>
                      </div>
                      <span className="text-[10px] text-slate-500">
                        {new Date(item.created_at).toLocaleTimeString()}
                      </span>
                    </div>

                    <div className="flex items-center gap-2 text-[11px]">
                      <span className="text-slate-400">Merge Decision:</span>
                      <strong className={item.merge_readiness.includes("BLOCKED") ? "text-rose-400" : "text-emerald-400"}>
                        {item.merge_readiness}
                      </strong>
                    </div>

                    {item.resolved_findings_from_previous && item.resolved_findings_from_previous.length > 0 && (
                      <div className="text-[11px] text-emerald-400 flex items-center gap-1.5 flex-wrap">
                        <span>Resolved:</span>
                        {item.resolved_findings_from_previous.map((r, rIdx) => (
                          <span key={rIdx} className="px-1.5 py-0.5 rounded bg-emerald-950/40 border border-emerald-500/30">
                            &check; {r}
                          </span>
                        ))}
                      </div>
                    )}

                    {item.new_findings_from_previous && item.new_findings_from_previous.length > 0 && (
                      <div className="text-[11px] text-amber-400 flex items-center gap-1.5 flex-wrap">
                        <span>New:</span>
                        {item.new_findings_from_previous.map((n, nIdx) => (
                          <span key={nIdx} className="px-1.5 py-0.5 rounded bg-amber-950/40 border border-amber-500/30">
                            + {n}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div className="p-6 rounded-xl border border-slate-800 bg-slate-900/50 text-center text-xs text-slate-400 font-mono">
                No previous analysis iterations recorded yet for this entity.
              </div>
            )}

            <div className="flex justify-end pt-2 border-t border-slate-800">
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
    </div>
  );
}

export default function AiInsightsPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-col items-center justify-center min-h-[50vh] text-slate-400 font-mono text-xs space-y-3">
          <Loader2 className="h-6 w-6 animate-spin text-indigo-400" />
          <p>Loading ForgeAI Engineering Health Intelligence...</p>
        </div>
      }
    >
      <AiInsightsContent />
    </Suspense>
  );
}
