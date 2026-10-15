from pydantic import BaseModel, Field
from typing import Optional, List, Dict, Any
from uuid import UUID, uuid4
from datetime import datetime, timezone
from enum import Enum


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class SymbolType(str, Enum):
    CLASS = "CLASS"
    FUNCTION = "FUNCTION"
    METHOD = "METHOD"
    MODULE = "MODULE"


class EdgeType(str, Enum):
    CALLS = "CALLS"
    IMPORTS = "IMPORTS"
    EXTENDS = "EXTENDS"


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class IndexJobStatus(str, Enum):
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class CodeChunk(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    commit_sha: str
    file_path: str
    symbol_name: Optional[str] = None
    symbol_type: Optional[str] = None
    content: str
    content_hash: str
    embedding: Optional[List[float]] = None
    is_current: bool = True
    created_at: datetime = Field(default_factory=utc_now)


class GraphEdge(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    source_symbol: str
    target_symbol: str
    edge_type: str
    valid_at_sha: str
    is_current: bool = True
    created_at: datetime = Field(default_factory=utc_now)


class TestEvidenceType(str, Enum):
    AST_CALL = "AST_CALL"
    FILE_REFERENCE = "FILE_REFERENCE"
    PATH_PAIRING = "PATH_PAIRING"
    RUNTIME_COVERAGE = "RUNTIME_COVERAGE"
    RUNTIME_CI = "RUNTIME_CI"
    HISTORICAL_FAILURE = "HISTORICAL_FAILURE"
    HISTORICAL_COCHANGE = "HISTORICAL_COCHANGE"


class TestPriorityBucket(str, Enum):
    MUST_RUN = "MUST_RUN"
    SHOULD_RUN = "SHOULD_RUN"
    OPTIONAL = "OPTIONAL"


class TestMergeRequirement(str, Enum):
    TEST_REQUIRED = "TEST_REQUIRED"
    TEST_RECOMMENDED = "TEST_RECOMMENDED"
    TEST_OPTIONAL = "TEST_OPTIONAL"


class MergeDecision(str, Enum):
    READY = "READY"
    READY_WITH_WARNINGS = "READY_WITH_WARNINGS"
    NOT_READY = "NOT_READY"
    BLOCKED_BY_MISSING_EVIDENCE = "BLOCKED_BY_MISSING_EVIDENCE"


class SecurityStatus(str, Enum):
    NO_SECURITY_FINDINGS = "NO_SECURITY_FINDINGS"
    SECURITY_WARNING = "SECURITY_WARNING"
    SECURITY_BLOCKER = "SECURITY_BLOCKER"
    SECURITY_EVIDENCE_UNAVAILABLE = "SECURITY_EVIDENCE_UNAVAILABLE"


class FindingSeverity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"
    INFO = "INFO"


class FindingCategory(str, Enum):
    CORRECTNESS = "CORRECTNESS"
    ARCHITECTURE = "ARCHITECTURE"
    SECURITY = "SECURITY"
    PERFORMANCE = "PERFORMANCE"
    TESTING = "TESTING"
    MAINTAINABILITY = "MAINTAINABILITY"
    RELIABILITY = "RELIABILITY"


class FindingType(str, Enum):
    OBSERVED = "OBSERVED"
    INFERRED = "INFERRED"


class TestRecommendation(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: Optional[UUID] = None
    repository_id: Optional[UUID] = None
    analysis_id: Optional[UUID] = None
    test_name: str
    test_file: str
    test_method: Optional[str] = None
    target_symbol: Optional[str] = None
    priority_score: float = 0.0
    priority_bucket: str = "OPTIONAL"
    merge_requirement: str = "TEST_RECOMMENDED"  # TEST_REQUIRED, TEST_RECOMMENDED, TEST_OPTIONAL
    confidence: str = "LOW"
    evidence_sources: List[str] = Field(default_factory=list)  # "STATIC", "RUNTIME", "HISTORICAL"
    evidence_types: List[str] = Field(default_factory=list)    # AST_CALL, PATH_PAIRING, etc.
    evidence_path: List[str] = Field(default_factory=list)
    reason: str = ""
    historical_failure_count: int = 0
    cochange_count: int = 0
    runtime_coverage_verified: bool = False
    created_at: datetime = Field(default_factory=utc_now)


class ReviewFinding(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    severity: str = "MEDIUM"                     # CRITICAL, HIGH, MEDIUM, LOW, INFO
    category: str = "CORRECTNESS"                # CORRECTNESS, ARCHITECTURE, SECURITY, etc.
    finding_type: str = "INFERRED"               # OBSERVED, INFERRED
    title: str
    description: str
    evidence: str
    confidence: str = "MEDIUM"                   # HIGH, MEDIUM, LOW
    recommendation: str
    affected_file: Optional[str] = None
    affected_symbol: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)


class RequiredCheck(BaseModel):
    name: str
    source: str                                  # CI, TEST_INTELLIGENCE, RISK_ENGINE, SECURITY_ANALYSIS, SYNCHRONIZATION, PROVENANCE
    status: str                                  # PASSED, FAILED, MISSING, WARNING
    confidence: str = "HIGH"
    evidence: str = ""
    detail: str = ""


class MergeReadiness(BaseModel):
    decision: str = "NOT_READY"                  # READY, READY_WITH_WARNINGS, NOT_READY, BLOCKED_BY_MISSING_EVIDENCE
    readiness_score: float = 0.0                 # 0.0 to 100.0
    confidence: str = "HIGH"                     # HIGH, MEDIUM, LOW
    policy_version: str = "merge-policy-v1"
    blocking_reasons: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    required_actions: List[str] = Field(default_factory=list)
    required_checks: List[Any] = Field(default_factory=list)
    completed_checks: List[Any] = Field(default_factory=list)
    failed_checks: List[Any] = Field(default_factory=list)
    missing_checks: List[Any] = Field(default_factory=list)
    required_tests: List[Dict[str, Any]] = Field(default_factory=list)
    evidence: List[Dict[str, Any]] = Field(default_factory=list)
    provenance: List[Dict[str, Any]] = Field(default_factory=list)
    security_status: str = "SECURITY_EVIDENCE_UNAVAILABLE"
    github_mode: str = "MODE_A_ADVISORY"
    decision_timestamp: datetime = Field(default_factory=utc_now)


class MergeEvaluation(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    analysis_id: Optional[UUID] = None
    organization_id: UUID
    repository_id: UUID
    pr_number: int
    commit_sha: str
    decision: str
    readiness_score: float
    confidence: str
    policy_version: str
    blocking_reasons: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    required_actions: List[str] = Field(default_factory=list)
    required_checks: List[Dict[str, Any]] = Field(default_factory=list)
    required_tests: List[Dict[str, Any]] = Field(default_factory=list)
    security_status: str = "SECURITY_EVIDENCE_UNAVAILABLE"
    github_mode: str = "MODE_A_ADVISORY"
    decision_timestamp: datetime = Field(default_factory=utc_now)
    created_at: datetime = Field(default_factory=utc_now)


class TestValidationFeedback(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    analysis_id: UUID
    commit_sha: str
    workflow_run_id: Optional[int] = None
    recommended_count: int = 0
    executed_count: int = 0
    passed_count: int = 0
    failed_count: int = 0
    high_priority_missed_count: int = 0
    prediction_to_failure_correlation: Optional[float] = None
    test_selection_efficiency: float = 0.0
    required_test_failure_capture: Optional[float] = None
    required_test_execution_rate: float = 0.0
    false_positive_required_test_rate: float = 0.0
    metrics: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)


class Analysis(BaseModel):
    id: UUID = Field(default_factory=uuid4)
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
    test_strategy: Dict[str, Any] = Field(default_factory=dict)
    test_effectiveness: Optional[Dict[str, Any]] = None
    merge_readiness: Optional[Dict[str, Any]] = None
    review_findings: List[Dict[str, Any]] = Field(default_factory=list)
    retrieved_chunks: Optional[List[Dict[str, Any]]] = None
    execution_duration_ms: int = 0
    trace_id: Optional[str] = None
    fallback_used: bool = False
    created_at: datetime = Field(default_factory=utc_now)


class IndexJob(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    commit_sha: str
    status: IndexJobStatus = IndexJobStatus.QUEUED
    attempt_count: int = 0
    max_attempts: int = 3
    last_error: Optional[str] = None
    files: List[Dict[str, str]] = Field(default_factory=list)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None

