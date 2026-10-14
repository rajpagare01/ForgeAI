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
    created_at: datetime = Field(default_factory=utc_now)


class GraphEdge(BaseModel):
    id: UUID = Field(default_factory=uuid4)
    organization_id: UUID
    repository_id: UUID
    source_symbol: str
    target_symbol: str
    edge_type: str
    valid_at_sha: str
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

