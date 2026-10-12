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


class SemanticSearchRequest(BaseModel):
    query: str
    limit: int = 5
    repository_id: Optional[UUID] = None


class ImpactAnalysisRequest(BaseModel):
    changed_symbols: List[str]
    max_depth: int = 3


class PRAnalysisRequest(BaseModel):
    repository_id: UUID
    pr_number: int
    title: str
    description: str
    changed_symbols: List[str] = Field(default_factory=list)


class AnalysisResponseDTO(BaseModel):
    id: UUID
    organization_id: UUID
    entity_type: str
    entity_id: str
    model_version: str
    prompt_version: str
    summary: str
    risk_level: Optional[str] = None
    execution_duration_ms: int
    trace_id: Optional[str] = None
