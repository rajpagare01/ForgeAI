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
    confidence: str = "LOW"
    evidence_sources: List[str] = Field(default_factory=list)  # "STATIC", "RUNTIME", "HISTORICAL"
    evidence_types: List[str] = Field(default_factory=list)    # AST_CALL, PATH_PAIRING, etc.
    evidence_path: List[str] = Field(default_factory=list)
    reason: str = ""
    historical_failure_count: int = 0
    cochange_count: int = 0
    runtime_coverage_verified: bool = False
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
    prediction_to_failure_correlation: float = 0.0
    test_selection_efficiency: float = 0.0
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

