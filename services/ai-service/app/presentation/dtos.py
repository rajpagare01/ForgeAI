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

