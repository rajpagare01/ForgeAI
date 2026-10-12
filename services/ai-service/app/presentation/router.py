import logging
from typing import List, Dict, Any
from uuid import UUID
from fastapi import APIRouter, Depends, Query
from app.presentation.dependencies import get_tenant_org_id, get_trace_id
from app.presentation.dtos import (
    EnqueueIndexJobRequest,
    EnqueueIndexJobResponse,
    SemanticSearchRequest,
    ImpactAnalysisRequest,
    PRAnalysisRequest,
    AnalysisResponseDTO,
)
from app.core.database import db_pool
from app.infrastructure.persistence.repositories import (
    AsyncpgCodeChunkRepository,
    AsyncpgGraphEdgeRepository,
    AsyncpgAnalysisRepository,
)
from app.application.indexing_service import IndexingService
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.workers.index_worker import IndexJobQueue

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/ai", tags=["AI Core"])

# Global worker instance (initialized in lifespan)
index_worker: IndexJobQueue | None = None


def get_services():
    pool = db_pool.pool
    chunk_repo = AsyncpgCodeChunkRepository(pool)
    edge_repo = AsyncpgGraphEdgeRepository(pool)
    analysis_repo = AsyncpgAnalysisRepository(pool)
    indexing_service = IndexingService(chunk_repo, edge_repo)
    retrieval_service = RetrievalService(chunk_repo, edge_repo)
    orchestration_service = GeminiOrchestrationService(retrieval_service, analysis_repo)
    return indexing_service, retrieval_service, orchestration_service


@router.post("/index/enqueue", response_model=EnqueueIndexJobResponse, status_code=202)
async def enqueue_indexing_job(
    req: EnqueueIndexJobRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Asynchronously enqueues repository files for AST parsing and vector indexing.
    Returns HTTP 202 immediately to decouple user-facing HTTP request from parsing.
    """
    global index_worker
    files_payload = [{"file_path": f.file_path, "content": f.content} for f in req.files]
    if index_worker:
        await index_worker.enqueue_index_job(
            organization_id=org_id,
            repository_id=req.repository_id,
            commit_sha=req.commit_sha,
            files=files_payload,
        )
    return EnqueueIndexJobResponse(
        status="ENQUEUED",
        repository_id=req.repository_id,
        commit_sha=req.commit_sha,
        files_enqueued=len(req.files),
    )


@router.post("/search", response_model=List[Dict[str, Any]])
async def semantic_search_code(
    req: SemanticSearchRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Performs cosine similarity search using pgvector across code chunks.
    Tenant isolation is enforced by org_id.
    """
    _, retrieval_service, _ = get_services()
    results = await retrieval_service.semantic_search(
        organization_id=org_id,
        query=req.query,
        limit=req.limit,
        repository_id=req.repository_id,
    )
    return results


@router.post("/impact", response_model=List[Dict[str, Any]])
async def graph_impact_analysis(
    req: ImpactAnalysisRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Traverses the knowledge graph up to N hops deep using recursive CTE to identify
    impacted upstream components.
    """
    _, retrieval_service, _ = get_services()
    return await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        changed_symbols=req.changed_symbols,
        max_depth=req.max_depth,
    )


@router.post("/pr-analysis", response_model=AnalysisResponseDTO)
async def analyze_pull_request(
    req: PRAnalysisRequest,
    org_id: UUID = Depends(get_tenant_org_id),
    trace_id: str = Depends(get_trace_id),
):
    """
    Generates an executive impact review for a PR using Hybrid RAG (graph + vectors + Gemini)
    and saves an audit record into analyses correlated with the W3C trace_id.
    """
    _, _, orchestration_service = get_services()
    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=req.repository_id,
        pr_number=req.pr_number,
        title=req.title,
        description=req.description,
        changed_symbols=req.changed_symbols,
        trace_id=trace_id,
    )
    return AnalysisResponseDTO(
        id=analysis.id,
        organization_id=analysis.organization_id,
        entity_type=analysis.entity_type,
        entity_id=analysis.entity_id,
        model_version=analysis.model_version,
        prompt_version=analysis.prompt_version,
        summary=analysis.summary,
        risk_level=analysis.risk_level,
        execution_duration_ms=analysis.execution_duration_ms,
        trace_id=analysis.trace_id,
    )


@router.get("/analyses/{entity_type}/{entity_id}", response_model=List[AnalysisResponseDTO])
async def get_analyses_for_entity(
    entity_type: str,
    entity_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Fetches historical AI analysis audit records for a given entity (e.g., PR or REPO).
    """
    pool = db_pool.pool
    analysis_repo = AsyncpgAnalysisRepository(pool)
    records = await analysis_repo.get_by_entity(org_id, entity_type, entity_id)
    return [
        AnalysisResponseDTO(
            id=a.id,
            organization_id=a.organization_id,
            entity_type=a.entity_type,
            entity_id=a.entity_id,
            model_version=a.model_version,
            prompt_version=a.prompt_version,
            summary=a.summary,
            risk_level=a.risk_level,
            execution_duration_ms=a.execution_duration_ms,
        )
        for a in records
    ]
