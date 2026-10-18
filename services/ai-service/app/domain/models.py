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


class EvidenceStatus(str, Enum):
    AVAILABLE = "AVAILABLE"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"


class ReliabilityStatus(str, Enum):
    PASSED = "PASSED"
    WARNING = "WARNING"
    FAILED = "FAILED"
    PARTIAL = "PARTIAL"
    UNAVAILABLE = "UNAVAILABLE"


class SecurityCategory(str, Enum):
    SECRET_EXPOSURE = "SECRET_EXPOSURE"
    INSECURE_CONFIGURATION = "INSECURE_CONFIGURATION"
    INJECTION = "INJECTION"
    CODE_LEVEL_SECURITY = "CODE_LEVEL_SECURITY"
    DEPENDENCY_RISK = "DEPENDENCY_RISK"
    AUTHORIZATION = "AUTHORIZATION"
    AUTHENTICATION = "AUTHENTICATION"
    CRYPTOGRAPHY = "CRYPTOGRAPHY"
    DATA_EXPOSURE = "DATA_EXPOSURE"
    INPUT_VALIDATION = "INPUT_VALIDATION"
    DESERIALIZATION = "DESERIALIZATION"
    PATH_TRAVERSAL = "PATH_TRAVERSAL"
    SSRF = "SSRF"
    COMMAND_EXECUTION = "COMMAND_EXECUTION"


class ReliabilityCategory(str, Enum):
    ERROR_HANDLING = "ERROR_HANDLING"
    TIMEOUT = "TIMEOUT"
    RETRY = "RETRY"
    CONCURRENCY = "CONCURRENCY"
    RESOURCE_LEAK = "RESOURCE_LEAK"
    FAILURE_RISK = "FAILURE_RISK"
    NULLABILITY = "NULLABILITY"
    DATA_CONSISTENCY = "DATA_CONSISTENCY"
    AVAILABILITY = "AVAILABILITY"
    OBSERVABILITY = "OBSERVABILITY"
    HISTORICAL_INSTABILITY = "HISTORICAL_INSTABILITY"


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


class SecurityFinding(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: Optional[UUID] = None
    repository_id: Optional[UUID] = None
    analysis_id: Optional[UUID] = None
    pr_number: Optional[int] = None
    commit_sha: Optional[str] = None
    category: str = SecurityCategory.CODE_LEVEL_SECURITY.value
    rule_id: str
    severity: str = FindingSeverity.MEDIUM.value
    confidence: str = "HIGH"                     # HIGH, MEDIUM, LOW
    title: str
    description: str
    file_path: str
    line_number: Optional[int] = None
    redacted_evidence: str                       # Raw secret NEVER persisted/exposed
    remediation: str
    is_blocker: bool = False
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)


class ReliabilityFinding(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: Optional[UUID] = None
    repository_id: Optional[UUID] = None
    analysis_id: Optional[UUID] = None
    pr_number: Optional[int] = None
    commit_sha: Optional[str] = None
    category: str = ReliabilityCategory.ERROR_HANDLING.value
    rule_id: str
    severity: str = FindingSeverity.MEDIUM.value
    confidence: str = "HIGH"                     # HIGH, MEDIUM, LOW
    title: str
    description: str
    file_path: str
    line_number: Optional[int] = None
    evidence: str
    remediation: str
    is_blocker: bool = False
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)


class SecurityAssessment(BaseModel):
    security_status: str = SecurityStatus.NO_SECURITY_FINDINGS.value
    evidence_status: str = EvidenceStatus.AVAILABLE.value
    security_risk_score: float = 0.0             # 0.0 to 100.0
    critical_count: int = 0
    high_count: int = 0
    medium_count: int = 0
    low_count: int = 0
    findings: List[SecurityFinding] = Field(default_factory=list)
    summary: str = ""
    metadata: Dict[str, Any] = Field(default_factory=dict)


class ReliabilityAssessment(BaseModel):
    reliability_status: str = ReliabilityStatus.PASSED.value
    evidence_status: str = EvidenceStatus.AVAILABLE.value
    reliability_risk_score: float = 0.0          # 0.0 to 100.0
    critical_count: int = 0
    high_count: int = 0
    medium_count: int = 0
    low_count: int = 0
    findings: List[ReliabilityFinding] = Field(default_factory=list)
    summary: str = ""
    metadata: Dict[str, Any] = Field(default_factory=dict)


class UnifiedRiskScore(BaseModel):
    local_risk: float = 0.0                      # 0.0 to 100.0
    distributed_risk: float = 0.0                # 0.0 to 50.0
    security_risk: float = 0.0                   # 0.0 to 100.0
    reliability_risk: float = 0.0                # 0.0 to 100.0
    final_risk: float = 0.0                      # 0.0 to 100.0
    final_risk_level: str = "LOW"                # LOW, MEDIUM, HIGH, CRITICAL
    risk_formula: str = "min(100.0, 0.35*local + 0.15*distributed + 0.35*security + 0.15*reliability)"
    dimension_contributions: Dict[str, float] = Field(default_factory=dict)


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
    reliability_status: str = "PASSED"
    unified_risk_score: float = 0.0
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
    security_assessment: Optional[Dict[str, Any]] = None
    reliability_assessment: Optional[Dict[str, Any]] = None
    unified_risk: Optional[Dict[str, Any]] = None
    engineering_actions: List[Dict[str, Any]] = Field(default_factory=list)
    remediation_summary: Optional[Dict[str, Any]] = None
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


# ============================================================
# CROSS-REPOSITORY DEPENDENCY & DISTRIBUTED CHANGE INTELLIGENCE
# ============================================================

class DependencyType(str, Enum):
    """Authoritative cross-repository dependency types with deterministic evidence requirements."""
    HTTP_API           = "HTTP_API"            # OpenAPI spec, service URL, generated client
    SHARED_LIBRARY     = "SHARED_LIBRARY"      # Maven/Gradle artifact, Python package
    EVENT_CONTRACT     = "EVENT_CONTRACT"      # Explicit schema file / event config
    SHARED_SCHEMA      = "SHARED_SCHEMA"       # protobuf, Avro, JSON Schema, OpenAPI schema ref
    DATABASE_CONTRACT  = "DATABASE_CONTRACT"   # Shared Flyway/Liquibase schema
    OTHER_EXPLICIT     = "OTHER_EXPLICIT"      # Explicit reference not matching above


class DependencyEvidenceType(str, Enum):
    """How the dependency was established."""
    EXPLICIT   = "EXPLICIT"    # Declared in manifest / explicit config
    DISCOVERED = "DISCOVERED"  # Derived via static analysis (never from similarity alone)


class DependencyConfidence(str, Enum):
    HIGH   = "HIGH"    # Exact manifest coord, explicit URL, OpenAPI ref, schema import
    MEDIUM = "MEDIUM"  # Strong static config relationship
    LOW    = "LOW"     # Heuristic — never auto-blocks merge


class DependencyState(str, Enum):
    ACTIVE  = "ACTIVE"   # Evidence still present in synchronized repo state
    STALE   = "STALE"    # Evidence no longer found in current synchronized state
    REMOVED = "REMOVED"  # Explicitly removed or superseded


class ContractCompatibilityStatus(str, Enum):
    COMPATIBLE           = "COMPATIBLE"
    POTENTIALLY_BREAKING = "POTENTIALLY_BREAKING"
    BREAKING             = "BREAKING"
    UNKNOWN              = "UNKNOWN"  # Insufficient evidence — must remain UNKNOWN


class DownstreamCIStatus(str, Enum):
    PASS      = "PASS"
    FAIL      = "FAIL"
    RUNNING   = "RUNNING"
    CANCELLED = "CANCELLED"
    UNKNOWN   = "UNKNOWN"
    NOT_FOUND = "NOT_FOUND"  # No CI evidence found — never treated as success


class RepositoryDependency(BaseModel):
    """Persisted cross-repository dependency relationship."""
    id:                   UUID = Field(default_factory=uuid4)
    organization_id:      UUID
    source_repository_id: UUID                              # the repo that depends on target
    target_repository_id: UUID                              # the repo being depended upon
    dependency_type:      str = DependencyType.OTHER_EXPLICIT
    evidence_type:        str = DependencyEvidenceType.DISCOVERED
    confidence:           str = DependencyConfidence.LOW
    source_reference:     Optional[str] = None              # e.g. "pom.xml:com.forgeai:shared-lib"
    target_reference:     Optional[str] = None              # e.g. "/api/v1/users"
    current_state:        str = DependencyState.ACTIVE
    metadata:             Dict[str, Any] = Field(default_factory=dict)
    discovered_at:        datetime = Field(default_factory=utc_now)
    last_verified_at:     datetime = Field(default_factory=utc_now)
    created_at:           datetime = Field(default_factory=utc_now)


class DependencyEvidence(BaseModel):
    """Raw evidence record proving a RepositoryDependency row."""
    id:             UUID = Field(default_factory=uuid4)
    organization_id: UUID
    dependency_id:  UUID
    evidence_kind:  Optional[str] = None  # MAVEN_POM, REQUIREMENTS_TXT, OPENAPI_REF, DOCKERFILE_URL, etc.
    file_path:      Optional[str] = None
    excerpt:        Optional[str] = None  # Short text excerpt (never full file)
    commit_sha:     Optional[str] = None
    created_at:     datetime = Field(default_factory=utc_now)


class DownstreamRepositoryImpact(BaseModel):
    """Impact on a single downstream repository derived from cross-repo analysis."""
    repository_id:        UUID
    repository_name:      str = ""
    dependency_type:      str = DependencyType.OTHER_EXPLICIT
    dependency_confidence: str = DependencyConfidence.LOW
    evidence_type:        str = DependencyEvidenceType.DISCOVERED
    source_reference:     Optional[str] = None
    target_reference:     Optional[str] = None
    contract_status:      str = ContractCompatibilityStatus.UNKNOWN
    affected_symbols:     List[str] = Field(default_factory=list)
    downstream_tests:     List[Dict[str, Any]] = Field(default_factory=list)
    ci_status:            str = DownstreamCIStatus.NOT_FOUND
    ci_run_id:            Optional[str] = None
    ci_workflow:          Optional[str] = None
    ci_conclusion:        Optional[str] = None
    depth:                int = 1
    provenance_path:      List[str] = Field(default_factory=list)


class DistributedRiskAssessment(BaseModel):
    """
    Distributed risk produced by CrossRepositoryImpactEngine.
    Separate from and additive to local DeterministicRiskEngine output.

    ======================================================================
    DISTRIBUTED RISK FORMULA (max 50 pts, then * 0.4 combined factor):
    ======================================================================
    - direct_downstream_repos    * 3.0   → max 15.0
    - transitive_downstream_repos * 1.5  → max 7.5
    - BREAKING contract (HIGH conf)       → +20.0  (max 20.0)
    - POTENTIALLY_BREAKING (HIGH conf)    → +10.0  (max 10.0)
    - downstream required test failures   * 5.0   → max 15.0
    - downstream CI failures              * 7.5   → max 15.0
    - total downstream components        * 0.5   → max 7.5
    Raw cap: min(sum, 50.0)
    LOW-confidence-only deps contribute 0 pts.

    combined_risk_score = min(100.0, local_risk_score + distributed_risk_score * 0.4)

    Distributed risk level boundaries:
      0–10  → NONE
      10–20 → LOW
      20–35 → MEDIUM
      35–50 → HIGH
    ======================================================================
    """
    local_risk_score:        float = 0.0             # repo-local baseline risk (0–100)
    local_risk_level:        str = "LOW"             # LOW, MEDIUM, HIGH, CRITICAL
    distributed_risk_score:  float = 0.0             # raw distributed score (0–50)
    distributed_risk_level:  str = "NONE"            # NONE, LOW, MEDIUM, HIGH
    combined_risk_score:     float = 0.0             # combined with local (0–100)
    combined_risk_level:     str = "LOW"             # LOW, MEDIUM, HIGH, CRITICAL
    direct_downstream_count: int = 0
    transitive_downstream_count: int = 0
    max_cross_repo_depth:    int = 0
    contract_status:         str = ContractCompatibilityStatus.UNKNOWN
    downstream_impacts:      List[DownstreamRepositoryImpact] = Field(default_factory=list)
    distributed_risk_factors: List[Dict[str, Any]] = Field(default_factory=list)
    provenance:              List[Dict[str, Any]] = Field(default_factory=list)


# ======================================================================
# Milestone: Engineering Workflow Automation & Controlled AI Remediation
# ======================================================================

class ActionType(str, Enum):
    RUN_TEST = "RUN_TEST"
    ADD_TEST = "ADD_TEST"
    FIX_SECURITY = "FIX_SECURITY"
    FIX_RELIABILITY = "FIX_RELIABILITY"
    UPDATE_CONFIGURATION = "UPDATE_CONFIGURATION"
    UPDATE_DEPENDENCY = "UPDATE_DEPENDENCY"
    UPDATE_API_CONTRACT = "UPDATE_API_CONTRACT"
    DOCUMENT_CHANGE = "DOCUMENT_CHANGE"
    REVIEW_REQUIRED = "REVIEW_REQUIRED"
    NO_ACTION = "NO_ACTION"


class ActionStatus(str, Enum):
    PROPOSED = "PROPOSED"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    APPLIED = "APPLIED"
    FAILED = "FAILED"
    SUPERSEDED = "SUPERSEDED"


class ActionSeverity(str, Enum):
    CRITICAL = "CRITICAL"
    HIGH = "HIGH"
    MEDIUM = "MEDIUM"
    LOW = "LOW"


class PlanStepStatus(str, Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    BLOCKED = "BLOCKED"


class PatchStatus(str, Enum):
    PROPOSED = "PROPOSED"
    VALIDATED = "VALIDATED"
    APPLIED = "APPLIED"
    REJECTED = "REJECTED"
    SUPERSEDED = "SUPERSEDED"


class GitHubWriteMode(str, Enum):
    ADVISORY_ONLY = "ADVISORY_ONLY"
    CONTROLLED_WRITE = "CONTROLLED_WRITE"


class EngineeringAction(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    action_id: str
    analysis_id: Optional[UUID] = None
    organization_id: UUID
    repository_id: UUID
    pr_number: int
    commit_sha: str
    action_type: str = ActionType.NO_ACTION.value
    title: str
    description: str
    reason: str
    severity: str = ActionSeverity.MEDIUM.value
    confidence: str = "HIGH"
    evidence: str
    affected_files: List[str] = Field(default_factory=list)
    affected_symbols: List[str] = Field(default_factory=list)
    provenance: Dict[str, Any] = Field(default_factory=dict)
    status: str = ActionStatus.PROPOSED.value
    source_finding_id: Optional[str] = None
    source_finding_type: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class RemediationStep(BaseModel):
    step_number: int
    target_file: str
    target_symbol: str
    change_description: str
    validation_strategy: str
    status: str = PlanStepStatus.PENDING.value
    repository_id: Optional[UUID] = None
    repository_name: Optional[str] = None


class RemediationPlan(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    action_id: str
    organization_id: UUID
    repository_id: UUID
    title: str
    summary: str
    steps: List[RemediationStep] = Field(default_factory=list)
    is_multi_repo: bool = False
    coordinated_repos: List[str] = Field(default_factory=list)
    expected_impact: str
    provenance: Dict[str, Any] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class PatchProposal(BaseModel):
    id: UUID = Field(default_factory=uuid4)
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
    confidence: str = "HIGH"
    validation_plan: Dict[str, Any] = Field(default_factory=dict)
    status: str = PatchStatus.PROPOSED.value
    model: str = "gemini-2.5-pro"
    provider: str = "GEMINI"
    fallback_used: bool = False
    prompt_version: str = "v1.5.0"
    trace_id: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)
    updated_at: datetime = Field(default_factory=utc_now)


class RemediationEffectiveness(BaseModel):
    id: UUID = Field(default_factory=uuid4)
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
    created_at: datetime = Field(default_factory=utc_now)


class ActionEvent(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    action_id: str
    event_type: str
    actor_id: str
    details: Dict[str, Any] = Field(default_factory=dict)
    trace_id: Optional[str] = None
    created_at: datetime = Field(default_factory=utc_now)


