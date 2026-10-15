from pydantic import BaseModel, Field
from typing import List, Optional, Dict, Any
from uuid import UUID


class IndexFileInput(BaseModel):
    file_path: str
    content: str


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
    github_mode: str = "MODE_A_ADVISORY"
    evidence: List[Dict[str, Any]] = Field(default_factory=list)


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
    analysis_id: UUID
    commit_sha: str
    workflow_run_id: Optional[int] = None
    executed_tests: List[Dict[str, Any]] = Field(default_factory=list)


class TestValidationResponse(BaseModel):
    status: str
    analysis_id: UUID
    commit_sha: str
    workflow_run_id: Optional[int] = None
    metrics: Dict[str, Any] = Field(default_factory=dict)


class AnalysisResponseDTO(BaseModel):
    id: UUID
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
    changed_components: Optional[Dict[str, Any]] = None
    direct_impact: Optional[List[Dict[str, Any]]] = None
    transitive_impact: Optional[List[Dict[str, Any]]] = None
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

