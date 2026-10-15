import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from fastapi import APIRouter, Depends, Query, HTTPException
from app.presentation.dependencies import get_tenant_org_id, get_trace_id, check_ai_quota
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
    AsyncpgIndexJobRepository,
)
from app.application.indexing_service import IndexingService
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.workers.index_worker import DurableIndexWorker

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/ai", tags=["AI Core"])

# Global worker instance (initialized in lifespan)
index_worker: DurableIndexWorker | None = None


async def get_services():
    pool = await db_pool.get_pool()
    chunk_repo = AsyncpgCodeChunkRepository(pool)
    edge_repo = AsyncpgGraphEdgeRepository(pool)
    analysis_repo = AsyncpgAnalysisRepository(pool)
    job_repo = AsyncpgIndexJobRepository(pool)
    indexing_service = IndexingService(chunk_repo, edge_repo)
    retrieval_service = RetrievalService(chunk_repo, edge_repo)
    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        indexing_service=indexing_service,
        job_repo=job_repo,
    )
    return indexing_service, retrieval_service, orchestration_service


@router.get("/healthz")
@router.get("/health")
async def health_check():
    """Liveness & readiness probe for AI intelligence service"""
    return {"status": "ok", "service": "ai-service"}


@router.post("/index/enqueue", response_model=EnqueueIndexJobResponse, status_code=202)
async def enqueue_indexing_job(
    req: EnqueueIndexJobRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Asynchronously and durably enqueues repository files for AST parsing and vector indexing.
    Returns HTTP 202 immediately to decouple user-facing HTTP request from parsing.
    """
    global index_worker
    if index_worker is None:
        try:
            pool = await db_pool.get_pool()
            chunk_repo = AsyncpgCodeChunkRepository(pool)
            edge_repo = AsyncpgGraphEdgeRepository(pool)
            job_repo = AsyncpgIndexJobRepository(pool)
            indexing_service = IndexingService(chunk_repo, edge_repo)
            index_worker = DurableIndexWorker(indexing_service, job_repo=job_repo)
            await index_worker.start_worker()
        except Exception as e:
            logger.error(f"Failed to initialize DurableIndexWorker with persistent store: {e}")
            raise HTTPException(
                status_code=503,
                detail=f"Durable indexing queue unavailable: failed to initialize database-backed store: {e}",
            )

    files_payload = [{"file_path": f.file_path, "content": f.content} for f in req.files]
    try:
        job = await index_worker.enqueue_index_job(
            organization_id=org_id,
            repository_id=req.repository_id,
            commit_sha=req.commit_sha,
            files=files_payload,
        )
    except Exception as e:
        logger.error(f"Failed to durably persist index job: {e}")
        raise HTTPException(
            status_code=503,
            detail=f"Durable index queue failed to persist job: {e}",
        )

    if not job or not job.id:
        raise HTTPException(
            status_code=500,
            detail="Failed to obtain valid job ID from durable queue",
        )

    return EnqueueIndexJobResponse(
        status="ENQUEUED",
        repository_id=req.repository_id,
        commit_sha=req.commit_sha,
        files_enqueued=len(req.files),
        job_id=job.id,
    )


@router.get("/jobs/{job_id}", response_model=Dict[str, Any])
async def get_index_job_status(
    job_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves the execution status of an asynchronous durable indexing job.
    Enforces tenant isolation by verifying the job belongs to the authenticated tenant.
    """
    pool = await db_pool.get_pool()
    job_repo = AsyncpgIndexJobRepository(pool)
    job = await job_repo.get_job_by_id(job_id)
    if not job or job.organization_id != org_id:
        raise HTTPException(status_code=404, detail="Index job not found or unauthorized.")

    return {
        "id": str(job.id),
        "organization_id": str(job.organization_id),
        "repository_id": str(job.repository_id),
        "commit_sha": job.commit_sha,
        "status": job.status.value if hasattr(job.status, "value") else str(job.status),
        "attempt_count": job.attempt_count,
        "max_attempts": job.max_attempts,
        "last_error": job.last_error,
        "files_count": len(job.files),
        "created_at": job.created_at.isoformat() if job.created_at else None,
        "updated_at": job.updated_at.isoformat() if job.updated_at else None,
        "started_at": job.started_at.isoformat() if job.started_at else None,
        "completed_at": job.completed_at.isoformat() if job.completed_at else None,
    }


@router.post("/search", response_model=List[Dict[str, Any]])
async def semantic_search_code(
    req: SemanticSearchRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Performs cosine similarity search using pgvector across code chunks.
    Tenant isolation is enforced by org_id.
    """
    _, retrieval_service, _ = await get_services()
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
    _, retrieval_service, _ = await get_services()
    return await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        repository_id=req.repository_id,
        changed_symbols=req.changed_symbols,
        max_depth=req.max_depth,
    )


@router.post("/pr-analysis", response_model=AnalysisResponseDTO)
async def analyze_pull_request(
    req: PRAnalysisRequest,
    org_id: UUID = Depends(get_tenant_org_id),
    trace_id: str = Depends(get_trace_id),
    _quota: None = Depends(check_ai_quota),
):
    """
    Generates an executive impact review for a PR using Hybrid RAG (graph + vectors + Gemini Primary / Groq Fallback)
    and saves an audit record into analyses correlated with the W3C trace_id.
    """
    _, _, orchestration_service = await get_services()
    raw_files = [f.model_dump() for f in req.changed_files] if req.changed_files else None
    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=req.repository_id,
        pr_number=req.pr_number,
        title=req.title,
        description=req.description,
        changed_symbols=req.changed_symbols,
        changed_files=raw_files,
        trace_id=trace_id,
    )
    return AnalysisResponseDTO(
        id=analysis.id,
        organization_id=analysis.organization_id,
        entity_type=analysis.entity_type,
        entity_id=analysis.entity_id,
        provider=analysis.provider,
        model_version=analysis.model_version,
        prompt_version=analysis.prompt_version,
        summary=analysis.summary,
        risk_level=analysis.risk_level,
        fallback_used=analysis.fallback_used,
        retrieved_chunks=analysis.retrieved_chunks,
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
    pool = await db_pool.get_pool()
    analysis_repo = AsyncpgAnalysisRepository(pool)
    records = await analysis_repo.get_by_entity(org_id, entity_type, entity_id)
    return [
        AnalysisResponseDTO(
            id=a.id,
            organization_id=a.organization_id,
            entity_type=a.entity_type,
            entity_id=a.entity_id,
            provider=a.provider,
            model_version=a.model_version,
            prompt_version=a.prompt_version,
            summary=a.summary,
            risk_level=a.risk_level,
            fallback_used=a.fallback_used,
            retrieved_chunks=a.retrieved_chunks,
            execution_duration_ms=a.execution_duration_ms,
            trace_id=a.trace_id,
        )
        for a in records
    ]
