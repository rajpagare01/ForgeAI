from pydantic import BaseModel, Field, model_validator
from typing import List, Optional, Dict, Any, Union
from uuid import UUID
from datetime import datetime



class IndexFileInput(BaseModel):
    file_path: str = ""
    content: str = ""

    @model_validator(mode="before")
    @classmethod
    def resolve_filename(cls, data: Any) -> Any:
        if isinstance(data, dict):
            if not data.get("file_path") and data.get("filename"):
                data["file_path"] = data["filename"]
        return data


class EnqueueIndexJobRequest(BaseModel):
    repository_id: UUID
    commit_sha: str
    files: List[IndexFileInput]


class EnqueueIndexJobResponse(BaseModel):
    status: str
    repository_id: UUID
    commit_sha: str
    files_enqueued: int
    job_id: Optional[UUID] = None


class SemanticSearchRequest(BaseModel):
    query: str
    limit: int = 5
    repository_id: Optional[UUID] = None


class ImpactAnalysisRequest(BaseModel):
    repository_id: UUID
    changed_symbols: List[str]
    max_depth: int = 3


class PRAnalysisRequest(BaseModel):
    repository_id: UUID
    pr_number: int
    title: Optional[str] = None
    description: Optional[str] = None
    changed_symbols: Optional[List[str]] = None
    changed_files: Optional[List[IndexFileInput]] = None


class TestRecommendationDTO(BaseModel):
    id: Optional[UUID] = None
    test_name: str
    test_file: str
    test_method: Optional[str] = None
    target_symbol: Optional[str] = None
    priority_score: float = 0.0
    priority_bucket: str = "OPTIONAL"
    merge_requirement: str = "TEST_RECOMMENDED"
    confidence: str = "LOW"
    evidence_sources: List[str] = Field(default_factory=list)
    evidence_types: List[str] = Field(default_factory=list)
    evidence_path: List[str] = Field(default_factory=list)
    reason: str = ""
    historical_failure_count: int = 0
    cochange_count: int = 0
    runtime_coverage_verified: bool = False


class ReviewFindingDTO(BaseModel):
    id: Optional[UUID] = None
    severity: str
    category: str
    finding_type: str = "OBSERVED"
    title: str
    description: str
    affected_file: Optional[str] = None
    affected_symbol: Optional[str] = None
    line_number: Optional[int] = None
    evidence: str = ""
    recommendation: str = ""
    confidence: str = "MEDIUM"
    provenance_source: str = "AI_GROUNDED_REVIEW"


class MergeReadinessDTO(BaseModel):
    decision: str
    readiness_score: float
    confidence: str
    policy_version: str
    blocking_reasons: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    required_actions: List[str] = Field(default_factory=list)
    required_checks: List[Dict[str, Any]] = Field(default_factory=list)
    completed_checks: List[str] = Field(default_factory=list)
    failed_checks: List[str] = Field(default_factory=list)
    missing_checks: List[str] = Field(default_factory=list)
    required_tests: List[Dict[str, Any]] = Field(default_factory=list)
    security_status: str = "SECURITY_EVIDENCE_UNAVAILABLE"
    reliability_status: str = "PASSED"
    unified_risk_score: float = 0.0
    github_mode: str = "MODE_A_ADVISORY"
    evidence: List[Dict[str, Any]] = Field(default_factory=list)


class SecurityFindingDTO(BaseModel):
    id: Optional[UUID] = None
    rule_id: str
    category: str
    severity: str
    confidence: str = "HIGH"
    title: str
    description: str
    file_path: str
    line_number: Optional[int] = None
    redacted_evidence: str
    remediation: str
    is_blocker: bool = False


class SecurityAssessmentDTO(BaseModel):
    security_status: str
    evidence_status: str
    security_risk_score: float = 0.0
    critical_count: int = 0
    high_count: int = 0
    medium_count: int = 0
    low_count: int = 0
    findings: List[SecurityFindingDTO] = Field(default_factory=list)
    summary: str = ""


class ReliabilityFindingDTO(BaseModel):
    id: Optional[UUID] = None
    rule_id: str
    category: str
    severity: str
    confidence: str = "HIGH"
    title: str
    description: str
    file_path: str
    line_number: Optional[int] = None
    evidence: str
    remediation: str
    failure_scenario: str = ""
    is_blocker: bool = False


class ReliabilityAssessmentDTO(BaseModel):
    reliability_status: str
    evidence_status: str
    reliability_risk_score: float = 0.0
    critical_count: int = 0
    high_count: int = 0
    medium_count: int = 0
    low_count: int = 0
    findings: List[ReliabilityFindingDTO] = Field(default_factory=list)
    summary: str = ""


class UnifiedRiskScoreDTO(BaseModel):
    local_risk: float = 0.0
    distributed_risk: float = 0.0
    security_risk: float = 0.0
    reliability_risk: float = 0.0
    final_risk: float = 0.0
    final_risk_level: str = "LOW"
    risk_formula: str = ""
    dimension_contributions: Dict[str, float] = Field(default_factory=dict)


class TestStrategyDTO(BaseModel):
    must_run: List[TestRecommendationDTO] = Field(default_factory=list)
    should_run: List[TestRecommendationDTO] = Field(default_factory=list)
    optional: List[TestRecommendationDTO] = Field(default_factory=list)
    static_evidence: List[Dict[str, Any]] = Field(default_factory=list)
    runtime_evidence: List[Dict[str, Any]] = Field(default_factory=list)
    historical_failures: List[Dict[str, Any]] = Field(default_factory=list)
    coverage_summary: Dict[str, Any] = Field(default_factory=dict)
    confidence: str = "MEDIUM"
    confidence_rationale: str = ""


class TestValidationRequest(BaseModel):
    repository_id: UUID
    analysis_id: Optional[UUID] = None
    commit_sha: str
    workflow_run_id: Optional[int] = None
    executed_tests: List[Dict[str, Any]] = Field(default_factory=list)


class TestValidationResponse(BaseModel):
    status: str
    analysis_id: Optional[UUID] = None
    commit_sha: str
    workflow_run_id: Optional[int] = None
    metrics: Dict[str, Any] = Field(default_factory=dict)
    prediction_to_failure_correlation: Optional[float] = None
    test_selection_efficiency: Optional[float] = None
    high_priority_missed_count: Optional[int] = None
    total_recommended: Optional[int] = None
    total_actual_executed: Optional[int] = None
    actual_failed_count: Optional[int] = None
    recommended_failed_count: Optional[int] = None
    missed_failures: List[Any] = Field(default_factory=list)

    @model_validator(mode="before")
    @classmethod
    def populate_root_metrics(cls, data: Any) -> Any:
        if isinstance(data, dict):
            m = data.get("metrics") or {}
            for k in [
                "prediction_to_failure_correlation",
                "test_selection_efficiency",
                "high_priority_missed_count",
                "total_recommended",
                "total_actual_executed",
                "actual_failed_count",
                "recommended_failed_count",
                "missed_failures"
            ]:
                if data.get(k) is None and k in m:
                    data[k] = m[k]
        return data


class AnalysisResponseDTO(BaseModel):
    id: UUID
    analysis_id: Optional[UUID] = None
    organization_id: UUID
    entity_type: str
    entity_id: str
    provider: str = "GEMINI"
    model_version: str
    prompt_version: str
    summary: str
    risk_level: Optional[str] = None
    risk_score: float = 0.0
    confidence: str = "MEDIUM"
    risk_factors: List[Dict[str, Any]] = Field(default_factory=list)
    impact_metadata: Dict[str, Any] = Field(default_factory=dict)
    test_strategy: Optional[Dict[str, Any]] = None
    test_effectiveness: Optional[Dict[str, Any]] = None
    merge_readiness: Optional[Dict[str, Any]] = None
    review_findings: List[Dict[str, Any]] = Field(default_factory=list)
    security_assessment: Optional[Dict[str, Any]] = None
    reliability_assessment: Optional[Dict[str, Any]] = None
    unified_risk: Optional[Dict[str, Any]] = None
    changed_components: Optional[Dict[str, Any]] = None
    direct_impact: Optional[List[Dict[str, Any]]] = None
    transitive_impact: Optional[List[Dict[str, Any]]] = None
    engineering_actions: List[Dict[str, Any]] = Field(default_factory=list)
    remediation_summary: Optional[Union[Dict[str, Any], str]] = None
    affected_modules: Optional[List[str]] = None
    affected_tests: Optional[List[Dict[str, Any]]] = None
    historical_signals: Optional[List[Dict[str, Any]]] = None
    architectural_concerns: Optional[List[str]] = None
    fallback_used: bool = False
    retrieved_chunks: Optional[List[Dict[str, Any]]] = None
    execution_duration_ms: int
    trace_id: Optional[str] = None


class PushSyncFileItem(BaseModel):
    file_path: str
    status: str = "modified"  # "added", "modified", "removed"
    content: Optional[str] = None


class PushSyncRequest(BaseModel):
    repository_id: UUID
    commit_sha: str
    ref: Optional[str] = None
    files: List[PushSyncFileItem] = Field(default_factory=list)


class PushSyncResponse(BaseModel):
    status: str
    repository_id: UUID
    commit_sha: str
    files_processed: int
    files_deactivated: int = 0
    chunks_indexed: int
    cache_hits: int
    edges_indexed: int


# ============================================================
# CROSS-REPOSITORY & DISTRIBUTED CHANGE INTELLIGENCE DTOS
# ============================================================

class DependencyEvidenceDTO(BaseModel):
    id: Optional[UUID] = None
    evidence_kind: Optional[str] = None
    file_path: Optional[str] = None
    excerpt: Optional[str] = None
    commit_sha: Optional[str] = None


class RepositoryDependencyDTO(BaseModel):
    id: Optional[UUID] = None
    organization_id: UUID
    source_repository_id: UUID
    target_repository_id: UUID
    dependency_type: str
    evidence_type: str = "EXPLICIT"
    confidence: str = "HIGH"
    source_reference: Optional[str] = None
    target_reference: Optional[str] = None
    current_state: str = "ACTIVE"
    metadata: Dict[str, Any] = Field(default_factory=dict)
    evidence: List[DependencyEvidenceDTO] = Field(default_factory=list)


class DownstreamRepositoryImpactDTO(BaseModel):
    repository_id: UUID
    repository_name: str = ""
    dependency_type: str
    dependency_confidence: str = "LOW"
    evidence_type: str = "DISCOVERED"
    source_reference: Optional[str] = None
    target_reference: Optional[str] = None
    contract_status: str = "UNKNOWN"
    affected_symbols: List[str] = Field(default_factory=list)
    downstream_tests: List[Dict[str, Any]] = Field(default_factory=list)
    ci_status: str = "NOT_FOUND"
    ci_run_id: Optional[str] = None
    ci_conclusion: Optional[str] = None
    depth: int = 1
    provenance_path: List[str] = Field(default_factory=list)


class DistributedImpactDTO(BaseModel):
    distributed_risk_score: float = 0.0
    distributed_risk_level: str = "NONE"
    combined_risk_score: float = 0.0
    combined_risk_level: str = "LOW"
    direct_downstream_count: int = 0
    transitive_downstream_count: int = 0
    max_cross_repo_depth: int = 0
    contract_status: str = "UNKNOWN"
    contract_rationale: Optional[str] = None
    downstream_impacts: List[DownstreamRepositoryImpactDTO] = Field(default_factory=list)
    distributed_risk_factors: List[Dict[str, Any]] = Field(default_factory=list)
    provenance: List[Dict[str, Any]] = Field(default_factory=list)


class RegisterDependencyRequest(BaseModel):
    source_repository_id: UUID
    target_repository_id: UUID
    dependency_type: str
    evidence_type: str = "EXPLICIT"
    confidence: str = "HIGH"
    source_reference: Optional[str] = None
    target_reference: Optional[str] = None
    evidence_file_path: Optional[str] = None
    evidence_excerpt: Optional[str] = None
    commit_sha: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class DiscoverDependenciesRequest(BaseModel):
    repository_id: Optional[UUID] = None
    files: List[IndexFileInput] = Field(default_factory=list)
    commit_sha: Optional[str] = None


# ======================================================================
# Milestone: Engineering Workflow Automation & Controlled AI Remediation DTOs
# ======================================================================

class EngineeringActionDTO(BaseModel):
    id: UUID
    action_id: str
    analysis_id: Optional[UUID] = None
    organization_id: UUID
    repository_id: UUID
    pr_number: int
    commit_sha: str
    action_type: str
    title: str
    description: str
    reason: str
    severity: str
    confidence: str
    evidence: str
    affected_files: List[str] = Field(default_factory=list)
    affected_symbols: List[str] = Field(default_factory=list)
    provenance: Dict[str, Any] = Field(default_factory=dict)
    status: str
    source_finding_id: Optional[str] = None
    source_finding_type: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime
    updated_at: datetime


class RemediationStepDTO(BaseModel):
    step_number: int
    target_file: str
    target_symbol: str
    change_description: str
    validation_strategy: str
    status: str
    repository_id: Optional[UUID] = None
    repository_name: Optional[str] = None


class RemediationPlanDTO(BaseModel):
    id: UUID
    action_id: str
    organization_id: UUID
    repository_id: UUID
    title: str
    summary: str
    steps: List[RemediationStepDTO] = Field(default_factory=list)
    is_multi_repo: bool = False
    coordinated_repos: List[str] = Field(default_factory=list)
    expected_impact: str
    provenance: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime
    updated_at: datetime


class PatchProposalDTO(BaseModel):
    id: UUID
    action_id: str
    organization_id: UUID
    repository_id: UUID
    target_file: str
    target_symbol: str
    original_context: str
    proposed_replacement: str
    unified_diff: str
    rationale: str
    source_finding: str
    confidence: str
    validation_plan: Dict[str, Any] = Field(default_factory=dict)
    status: str
    model: str
    provider: str
    fallback_used: bool = False
    prompt_version: str
    trace_id: Optional[str] = None
    created_at: datetime
    updated_at: datetime


class RemediationEffectivenessDTO(BaseModel):
    id: UUID
    action_id: str
    organization_id: UUID
    repository_id: UUID
    before_risk_score: float
    after_risk_score: float
    risk_delta: float
    before_merge_readiness: str
    after_merge_readiness: str
    merge_readiness_delta: str
    findings_fixed: List[str] = Field(default_factory=list)
    findings_persisted: List[str] = Field(default_factory=list)
    new_findings_introduced: List[str] = Field(default_factory=list)
    tests_added: int = 0
    tests_passed: bool = True
    is_effective: bool = False
    remediation_summary: str
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime


class ActionEventDTO(BaseModel):
    id: UUID
    organization_id: UUID
    repository_id: UUID
    action_id: str
    event_type: str
    actor_id: str
    details: Dict[str, Any] = Field(default_factory=dict)
    trace_id: Optional[str] = None
    created_at: datetime


class ApproveActionRequest(BaseModel):
    actor_id: str = "developer"
    comment: Optional[str] = None


class RejectActionRequest(BaseModel):
    actor_id: str = "developer"
    reason: Optional[str] = None


class ApplyActionRequest(BaseModel):
    actor_id: str = "developer"
    write_mode: Optional[str] = None  # ADVISORY_ONLY or CONTROLLED_WRITE


class ActionDetailsResponseDTO(BaseModel):
    action: EngineeringActionDTO
    plan: Optional[RemediationPlanDTO] = None
    patch: Optional[PatchProposalDTO] = None
    effectiveness: Optional[RemediationEffectivenessDTO] = None
    events: List[ActionEventDTO] = Field(default_factory=list)
    write_mode: str = "ADVISORY_ONLY"


# ======================================================================
# Engineering Health & Security Center DTOs
# ======================================================================

class RepositoryScanRequest(BaseModel):
    commit_sha: Optional[str] = ""
    files: Optional[List[IndexFileInput]] = None
    dependency_scanner_configured: bool = False


class SecretScanRequest(BaseModel):
    commit_sha: Optional[str] = ""
    files: Optional[List[IndexFileInput]] = None


class SecretScanResponseDTO(BaseModel):
    secrets_found: int = 0
    critical_count: int = 0
    high_count: int = 0
    warning_count: int = 0
    files_scanned: int = 0
    scan_time_ms: float = 0.0
    commit_sha: str = ""
    status: str = "CLEAN"  # ACTIVE_EXPOSURE, CLEAN
    findings: List[Dict[str, Any]] = Field(default_factory=list)


class ReScanRequest(BaseModel):
    pr_number: Optional[int] = None
    commit_sha: Optional[str] = None
    changed_files: Optional[List[IndexFileInput]] = None


class ReScanResponseDTO(BaseModel):
    analysis_id: UUID
    before_risk_score: float
    after_risk_score: float
    risk_delta: float
    before_merge_readiness: str
    after_merge_readiness: str
    merge_readiness_delta: str
    before_critical_count: int
    after_critical_count: int
    before_high_count: int
    after_high_count: int
    findings_resolved: List[str] = Field(default_factory=list)
    findings_persisted: List[str] = Field(default_factory=list)
    findings_new: List[str] = Field(default_factory=list)
    is_clean: bool = False
    summary: str
    updated_analysis: Optional[AnalysisResponseDTO] = None


class ExplainFindingRequest(BaseModel):
    finding_id: Optional[str] = None
    rule_id: str
    category: str
    severity: str
    title: str
    file_path: str
    line_number: Optional[int] = None
    redacted_evidence: str


class ExplainFindingResponseDTO(BaseModel):
    finding_id: Optional[str] = None
    rule_id: str
    title: str
    what_happened: str
    why_it_is_risky: str
    production_impact: str
    how_to_fix: str
    supporting_evidence: str
    remediation_guidance: List[str] = Field(default_factory=list)


class DependencySecurityStatusDTO(BaseModel):
    status: str = "PARTIAL"  # AVAILABLE or PARTIAL
    code_level_security: str = "AVAILABLE"
    secret_scanning: str = "AVAILABLE"
    dependency_cve_database: str = "NOT_CONFIGURED"  # CONFIGURED or NOT_CONFIGURED
    dependencies_scanned: int = 0
    vulnerabilities_found: int = 0
    findings: List[Dict[str, Any]] = Field(default_factory=list)
    summary: str = ""


class AnalysisHistoryItemDTO(BaseModel):
    analysis_id: UUID
    entity_id: str
    created_at: datetime
    risk_score: float
    risk_level: str
    merge_readiness: str
    critical_findings_count: int = 0
    high_findings_count: int = 0
    resolved_findings_from_previous: List[str] = Field(default_factory=list)
    new_findings_from_previous: List[str] = Field(default_factory=list)




