import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from fastapi import APIRouter, Depends, Query, HTTPException
from app.presentation.dependencies import (
    get_tenant_org_id,
    get_trace_id,
    check_ai_quota,
    verify_internal_attestation,
)
from app.presentation.dtos import (
    EnqueueIndexJobRequest,
    EnqueueIndexJobResponse,
    SemanticSearchRequest,
    ImpactAnalysisRequest,
    PRAnalysisRequest,
    AnalysisResponseDTO,
    PushSyncRequest,
    PushSyncResponse,
    TestRecommendationDTO,
    TestStrategyDTO,
    TestValidationRequest,
    TestValidationResponse,
    RepositoryDependencyDTO,
    DependencyEvidenceDTO,
    DownstreamRepositoryImpactDTO,
    DistributedImpactDTO,
    RegisterDependencyRequest,
    DiscoverDependenciesRequest,
    EngineeringActionDTO,
    RemediationStepDTO,
    RemediationPlanDTO,
    PatchProposalDTO,
    RemediationEffectivenessDTO,
    ActionEventDTO,
    ApproveActionRequest,
    RejectActionRequest,
    ApplyActionRequest,
    ActionDetailsResponseDTO,
    RepositoryScanRequest,
    SecretScanRequest,
    SecretScanResponseDTO,
    ReScanRequest,
    ReScanResponseDTO,
    ExplainFindingRequest,
    ExplainFindingResponseDTO,
    DependencySecurityStatusDTO,
    AnalysisHistoryItemDTO,
)
from app.application.security_intelligence_engine import SecurityIntelligenceEngine
from app.application.dependency_vulnerability_scanner import DependencyVulnerabilityScanner
from app.core.database import db_pool
from app.domain.models import (
    TestValidationFeedback,
    RepositoryDependency,
    DependencyEvidence,
    DependencyType,
    DependencyEvidenceType,
    DependencyConfidence,
    GitHubWriteMode,
)
from app.infrastructure.persistence.repositories import (
    AsyncpgCodeChunkRepository,
    AsyncpgGraphEdgeRepository,
    AsyncpgAnalysisRepository,
    AsyncpgIndexJobRepository,
    AsyncpgTestRecommendationRepository,
    AsyncpgTestValidationFeedbackRepository,
    AsyncpgMergeEvaluationRepository,
    AsyncpgRepositoryDependencyRepository,
    AsyncpgSecurityAssessmentRepository,
    AsyncpgReliabilityAssessmentRepository,
    AsyncpgEngineeringActionRepository,
    AsyncpgRemediationPlanRepository,
    AsyncpgPatchProposalRepository,
    AsyncpgRemediationEffectivenessRepository,
    AsyncpgActionEventRepository,
)
from app.application.indexing_service import IndexingService
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.application.test_intelligence_engine import TestIntelligenceEngine
from app.application.dependency_discovery_engine import DependencyDiscoveryEngine
from app.application.cross_repo_impact_engine import CrossRepositoryImpactEngine
from app.application.remediation_service import RemediationService
from app.infrastructure.client.source_control_client import SourceControlClient
from app.infrastructure.client.testing_service_client import TestingServiceClient
from app.workers.index_worker import DurableIndexWorker

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/api/v1/ai",
    tags=["AI Core"],
    dependencies=[Depends(verify_internal_attestation)],
)

# Global worker instance (initialized in lifespan)
index_worker: DurableIndexWorker | None = None


async def get_services():
    pool = await db_pool.get_pool()
    chunk_repo = AsyncpgCodeChunkRepository(pool)
    edge_repo = AsyncpgGraphEdgeRepository(pool)
    analysis_repo = AsyncpgAnalysisRepository(pool)
    job_repo = AsyncpgIndexJobRepository(pool)
    test_rec_repo = AsyncpgTestRecommendationRepository(pool)
    test_feedback_repo = AsyncpgTestValidationFeedbackRepository(pool)
    merge_eval_repo = AsyncpgMergeEvaluationRepository(pool)
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)
    sec_repo = AsyncpgSecurityAssessmentRepository(pool)
    rel_repo = AsyncpgReliabilityAssessmentRepository(pool)
    action_repo = AsyncpgEngineeringActionRepository(pool)
    plan_repo = AsyncpgRemediationPlanRepository(pool)
    patch_repo = AsyncpgPatchProposalRepository(pool)
    effectiveness_repo = AsyncpgRemediationEffectivenessRepository(pool)
    event_repo = AsyncpgActionEventRepository(pool)

    indexing_service = IndexingService(chunk_repo, edge_repo, job_repo=job_repo)
    retrieval_service = RetrievalService(chunk_repo, edge_repo)
    source_control_client = SourceControlClient()
    remediation_service = RemediationService(
        action_repo=action_repo,
        plan_repo=plan_repo,
        patch_repo=patch_repo,
        effectiveness_repo=effectiveness_repo,
        event_repo=event_repo,
        source_control_client=source_control_client,
        default_write_mode=GitHubWriteMode.ADVISORY_ONLY.value,
    )
    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        indexing_service=indexing_service,
        job_repo=job_repo,
        test_rec_repo=test_rec_repo,
        merge_eval_repo=merge_eval_repo,
        dependency_repo=dep_repo,
        security_assessment_repo=sec_repo,
        reliability_assessment_repo=rel_repo,
        action_repo=action_repo,
        plan_repo=plan_repo,
        patch_repo=patch_repo,
    )
    return indexing_service, retrieval_service, orchestration_service, analysis_repo, test_rec_repo, test_feedback_repo, merge_eval_repo, dep_repo, sec_repo, rel_repo, remediation_service




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


@router.post("/sync/push", response_model=PushSyncResponse)
async def sync_push_event(
    req: PushSyncRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Incrementally synchronizes a repository push event into the intelligence layer:
    - Removes / deactivates deleted file chunks and edges
    - Re-indexes modified and added files with content hash embedding reuse
    - Enforces tenant and repository isolation
    - Idempotently succeeds if commit state is already indexed
    """
    indexing_service, *_ = await get_services()
    files_payload = [{"file_path": f.file_path, "status": f.status, "content": f.content} for f in req.files]
    result = await indexing_service.sync_incremental_push(
        organization_id=org_id,
        repository_id=req.repository_id,
        commit_sha=req.commit_sha,
        ref=req.ref,
        files=files_payload,
    )
    return PushSyncResponse(
        status=result.get("status", "COMPLETED"),
        repository_id=req.repository_id,
        commit_sha=req.commit_sha,
        files_processed=result.get("files_processed", 0),
        files_deactivated=result.get("files_deactivated", 0),
        chunks_indexed=result.get("chunks_indexed", 0),
        cache_hits=result.get("cache_hits", 0),
        edges_indexed=result.get("edges_indexed", 0),
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
    _, retrieval_service, *_ = await get_services()
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
    _, retrieval_service, *_ = await get_services()
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
    _, _, orchestration_service, *_ = await get_services()
    raw_files = [f.model_dump() for f in req.changed_files] if req.changed_files else None
    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=req.repository_id,
        pr_number=req.pr_number,
        title=req.title,
        description=req.description,
        head_sha=req.head_sha,
        base_sha=req.base_sha,
        changed_symbols=req.changed_symbols,
        changed_files=raw_files,
        trace_id=trace_id,
    )
    meta = analysis.impact_metadata or {}
    return AnalysisResponseDTO(
        id=analysis.id,
        analysis_id=analysis.id,
        organization_id=analysis.organization_id,
        entity_type=analysis.entity_type,
        entity_id=analysis.entity_id,
        provider=analysis.provider,
        model_version=analysis.model_version,
        prompt_version=analysis.prompt_version,
        summary=analysis.summary,
        risk_level=analysis.risk_level,
        risk_score=analysis.risk_score,
        confidence=analysis.confidence,
        risk_factors=analysis.risk_factors or [],
        impact_metadata=meta,
        test_strategy=analysis.test_strategy or {},
        test_effectiveness=analysis.test_effectiveness,
        merge_readiness=analysis.merge_readiness,
        review_findings=analysis.review_findings or [],
        security_assessment=analysis.security_assessment or meta.get("security_assessment"),
        reliability_assessment=analysis.reliability_assessment or meta.get("reliability_assessment"),
        unified_risk=analysis.unified_risk or meta.get("unified_risk"),
        changed_components=meta.get("changed_components"),
        direct_impact=meta.get("direct_impact"),
        transitive_impact=meta.get("transitive_impact"),
        engineering_actions=getattr(analysis, "engineering_actions", None) or meta.get("engineering_actions") or [],
        remediation_summary=getattr(analysis, "remediation_summary", None) or meta.get("remediation_summary"),
        affected_modules=meta.get("affected_modules"),
        affected_tests=meta.get("affected_tests"),
        historical_signals=meta.get("historical_signals"),
        architectural_concerns=meta.get("architectural_concerns"),
        fallback_used=analysis.fallback_used,
        retrieved_chunks=analysis.retrieved_chunks,
        execution_duration_ms=analysis.execution_duration_ms,
        trace_id=analysis.trace_id,
    )


@router.get("/test-impact/{analysis_id}", response_model=Dict[str, Any])
async def get_test_impact_for_analysis(
    analysis_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves the explainable test intelligence strategy for a completed PR analysis:
    - MUST RUN, SHOULD RUN, OPTIONAL test groupings
    - Static AST, runtime CI, and historical failure evidence
    - Change-to-test derivation paths
    - Enforces tenant isolation.
    """
    pool = await db_pool.get_pool()
    analysis_repo = AsyncpgAnalysisRepository(pool)
    analysis = await analysis_repo.get_by_id(org_id, analysis_id)
    if not analysis:
        raise HTTPException(status_code=404, detail="Analysis record not found or unauthorized.")

    strat = analysis.test_strategy or {}
    return {
        "analysis_id": str(analysis.id),
        "organization_id": str(analysis.organization_id),
        "entity_id": analysis.entity_id,
        "risk_level": analysis.risk_level,
        "risk_score": analysis.risk_score,
        "confidence": strat.get("confidence", analysis.confidence),
        "confidence_rationale": strat.get("confidence_rationale", ""),
        "must_run": strat.get("must_run", []),
        "should_run": strat.get("should_run", []),
        "optional": strat.get("optional", []),
        "static_evidence": strat.get("static_evidence", []),
        "runtime_evidence": strat.get("runtime_evidence", []),
        "historical_failures": strat.get("historical_failures", []),
        "coverage_summary": strat.get("coverage_summary", {}),
        "test_effectiveness": analysis.test_effectiveness,
    }


@router.post("/test-validation/validate", response_model=TestValidationResponse)
async def validate_post_pr_test_execution(
    req: TestValidationRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Feedback Loop: Correlates actual post-PR GitHub Actions / CI test run results with prior test recommendations.
    Calculates prediction-to-failure correlation and test selection efficiency, and stores persistent feedback.
    """
    pool = await db_pool.get_pool()
    analysis_repo = AsyncpgAnalysisRepository(pool)
    feedback_repo = AsyncpgTestValidationFeedbackRepository(pool)

    analysis = None
    if req.analysis_id:
        analysis = await analysis_repo.get_by_id(org_id, req.analysis_id)
    if not analysis:
        # Fallback lookup by repository_id for PR or REPO
        analyses = await analysis_repo.get_by_entity(org_id, "PR", f"{req.repository_id}")
        if not analyses:
            analyses = await analysis_repo.get_by_entity(org_id, "REPO", f"{req.repository_id}")
        if analyses:
            analysis = analyses[0]
    if not analysis:
        raise HTTPException(status_code=404, detail="Analysis record not found or unauthorized.")

    resolved_analysis_id = analysis.id

    strat = analysis.test_strategy or {}
    all_recs = []
    from app.domain.models import TestRecommendation
    for r_data in (strat.get("must_run", []) + strat.get("should_run", []) + strat.get("optional", [])):
        all_recs.append(TestRecommendation(**r_data))

    metrics = TestIntelligenceEngine.calculate_validation_metrics(
        recommended_tests=all_recs,
        executed_test_results=req.executed_tests,
        workflow_run_id=req.workflow_run_id,
    )

    feedback = TestValidationFeedback(
        organization_id=org_id,
        repository_id=req.repository_id,
        analysis_id=resolved_analysis_id,
        commit_sha=req.commit_sha,
        workflow_run_id=req.workflow_run_id,
        recommended_count=metrics["recommended_count"],
        executed_count=metrics["executed_count"],
        passed_count=metrics["passed_count"],
        failed_count=metrics["failed_count"],
        high_priority_missed_count=metrics["high_priority_missed_count"],
        prediction_to_failure_correlation=metrics["prediction_to_failure_correlation"],
        test_selection_efficiency=metrics["test_selection_efficiency"],
        metrics=metrics,
    )
    await feedback_repo.save_feedback(feedback)

    # Update analysis with test effectiveness metrics
    analysis.test_effectiveness = metrics
    await analysis_repo.save_analysis(analysis)

    return TestValidationResponse(
        status="VALIDATED",
        analysis_id=resolved_analysis_id,
        commit_sha=req.commit_sha,
        workflow_run_id=req.workflow_run_id,
        metrics=metrics,
    )


@router.get("/analyses/{analysis_id}/security", response_model=Dict[str, Any])
async def get_security_assessment_for_analysis(
    analysis_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves deterministic static security assessment and redacted findings for an analysis.
    Enforces tenant isolation via organization_id.
    """
    pool = await db_pool.get_pool()
    sec_repo = AsyncpgSecurityAssessmentRepository(pool)
    result = await sec_repo.get_by_analysis_id(org_id, analysis_id)
    if result:
        assessment, findings = result
        return {
            "analysis_id": str(analysis_id),
            "organization_id": str(org_id),
            "security_status": assessment.security_status,
            "evidence_status": assessment.evidence_status,
            "security_risk_score": assessment.security_risk_score,
            "critical_count": assessment.critical_count,
            "high_count": assessment.high_count,
            "medium_count": assessment.medium_count,
            "low_count": assessment.low_count,
            "summary": assessment.summary,
            "findings": [f.model_dump() for f in findings],
        }
    analysis_repo = AsyncpgAnalysisRepository(pool)
    analysis = await analysis_repo.get_by_id(org_id, analysis_id)
    if not analysis:
        raise HTTPException(status_code=404, detail="Analysis record not found or unauthorized.")
    sec = analysis.security_assessment or (analysis.impact_metadata or {}).get("security_assessment") or {}
    return {
        "analysis_id": str(analysis_id),
        "organization_id": str(org_id),
        **sec,
    }


@router.get("/analyses/{analysis_id}/reliability", response_model=Dict[str, Any])
async def get_reliability_assessment_for_analysis(
    analysis_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves deterministic reliability assessment and findings for an analysis.
    Enforces tenant isolation via organization_id.
    """
    pool = await db_pool.get_pool()
    rel_repo = AsyncpgReliabilityAssessmentRepository(pool)
    result = await rel_repo.get_by_analysis_id(org_id, analysis_id)
    if result:
        assessment, findings = result
        return {
            "analysis_id": str(analysis_id),
            "organization_id": str(org_id),
            "reliability_status": assessment.reliability_status,
            "evidence_status": assessment.evidence_status,
            "reliability_risk_score": assessment.reliability_risk_score,
            "critical_count": assessment.critical_count,
            "high_count": assessment.high_count,
            "medium_count": assessment.medium_count,
            "low_count": assessment.low_count,
            "summary": assessment.summary,
            "findings": [f.model_dump() for f in findings],
        }
    analysis_repo = AsyncpgAnalysisRepository(pool)
    analysis = await analysis_repo.get_by_id(org_id, analysis_id)
    if not analysis:
        raise HTTPException(status_code=404, detail="Analysis record not found or unauthorized.")
    rel = analysis.reliability_assessment or (analysis.impact_metadata or {}).get("reliability_assessment") or {}
    return {
        "analysis_id": str(analysis_id),
        "organization_id": str(org_id),
        **rel,
    }


@router.get("/analyses/{analysis_id}/actions", response_model=List[EngineeringActionDTO])
async def get_analysis_actions(
    analysis_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves all deterministic engineering actions derived for a specific analysis.
    Enforces tenant isolation via organization_id.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    actions = await remediation_service.get_actions_for_analysis(org_id, analysis_id)
    return [
        EngineeringActionDTO(
            id=a.id,
            action_id=a.action_id,
            analysis_id=a.analysis_id,
            organization_id=a.organization_id,
            repository_id=a.repository_id,
            pr_number=a.pr_number,
            commit_sha=a.commit_sha,
            action_type=a.action_type,
            title=a.title,
            description=a.description,
            reason=a.reason,
            severity=a.severity,
            confidence=a.confidence,
            evidence=a.evidence,
            affected_files=a.affected_files,
            affected_symbols=a.affected_symbols,
            provenance=a.provenance,
            status=a.status,
            source_finding_id=a.source_finding_id,
            source_finding_type=a.source_finding_type,
            metadata=a.metadata,
            created_at=a.created_at,
            updated_at=a.updated_at,
        )
        for a in actions
    ]


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
            analysis_id=a.id,
            organization_id=a.organization_id,
            entity_type=a.entity_type,
            entity_id=a.entity_id,
            provider=a.provider,
            model_version=a.model_version,
            prompt_version=a.prompt_version,
            summary=a.summary,
            risk_level=a.risk_level,
            risk_score=a.risk_score,
            confidence=a.confidence,
            risk_factors=a.risk_factors or [],
            impact_metadata=a.impact_metadata or {},
            test_strategy=a.test_strategy or {},
            test_effectiveness=a.test_effectiveness,
            merge_readiness=a.merge_readiness,
            review_findings=a.review_findings or [],
            security_assessment=a.security_assessment or (a.impact_metadata or {}).get("security_assessment"),
            reliability_assessment=a.reliability_assessment or (a.impact_metadata or {}).get("reliability_assessment"),
            unified_risk=a.unified_risk or (a.impact_metadata or {}).get("unified_risk"),
            changed_components=(a.impact_metadata or {}).get("changed_components"),
            direct_impact=(a.impact_metadata or {}).get("direct_impact"),
            transitive_impact=(a.impact_metadata or {}).get("transitive_impact"),
            engineering_actions=getattr(a, "engineering_actions", None) or (a.impact_metadata or {}).get("engineering_actions") or [],
            remediation_summary=getattr(a, "remediation_summary", None) or (a.impact_metadata or {}).get("remediation_summary"),
            affected_modules=(a.impact_metadata or {}).get("affected_modules"),
            affected_tests=(a.impact_metadata or {}).get("affected_tests"),
            historical_signals=(a.impact_metadata or {}).get("historical_signals"),
            architectural_concerns=(a.impact_metadata or {}).get("architectural_concerns"),
            fallback_used=a.fallback_used,
            retrieved_chunks=a.retrieved_chunks,
            execution_duration_ms=a.execution_duration_ms,
            trace_id=a.trace_id,
        )
        for a in records
    ]


@router.get("/merge-readiness/{analysis_id}", response_model=Dict[str, Any])
async def get_merge_readiness_for_analysis(
    analysis_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves the deterministic merge readiness evaluation and structured review findings
    for a completed PR analysis:
    - Categorical decision (READY, READY_WITH_WARNINGS, NOT_READY, BLOCKED_BY_MISSING_EVIDENCE)
    - Deterministic readiness score [0.0 - 100.0]
    - Required checks matrix across CI, TEST_INTELLIGENCE, RISK_ENGINE, SECURITY, SYNCHRONIZATION, PROVENANCE
    - Required tests execution status
    - Structured grounded review findings with OBSERVED vs INFERRED classification
    """
    pool = await db_pool.get_pool()
    analysis_repo = AsyncpgAnalysisRepository(pool)
    analysis = await analysis_repo.get_by_id(org_id, analysis_id)
    if not analysis:
        raise HTTPException(status_code=404, detail="Analysis record not found or unauthorized.")

    return {
        "analysis_id": str(analysis.id),
        "organization_id": str(analysis.organization_id),
        "entity_id": analysis.entity_id,
        "merge_readiness": analysis.merge_readiness or {},
        "review_findings": analysis.review_findings or [],
        "risk_level": analysis.risk_level,
        "risk_score": analysis.risk_score,
        "created_at": analysis.created_at.isoformat() if hasattr(analysis.created_at, "isoformat") else str(analysis.created_at),
    }


# ============================================================
# CROSS-REPOSITORY DEPENDENCY & DISTRIBUTED CHANGE INTELLIGENCE
# ============================================================

@router.post("/repositories/{repository_id}/dependencies", response_model=RepositoryDependencyDTO)
async def register_repository_dependency(
    repository_id: UUID,
    req: RegisterDependencyRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Registers or updates an authoritative cross-repository dependency.
    Tenant isolation is enforced via organization_id.
    """
    pool = await db_pool.get_pool()
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)

    dep = RepositoryDependency(
        organization_id=org_id,
        source_repository_id=req.source_repository_id,
        target_repository_id=req.target_repository_id,
        dependency_type=req.dependency_type,
        evidence_type=req.evidence_type,
        confidence=req.confidence,
        source_reference=req.source_reference,
        target_reference=req.target_reference,
        metadata=req.metadata,
    )
    saved = await dep_repo.upsert_dependency(dep)

    evidences_dto = []
    if req.evidence_file_path:
        ev = DependencyEvidence(
            organization_id=org_id,
            dependency_id=saved.id,
            evidence_kind=f"MANUAL_{req.dependency_type}",
            file_path=req.evidence_file_path,
            excerpt=req.evidence_excerpt,
            commit_sha=req.commit_sha,
        )
        saved_ev = await dep_repo.save_evidence(ev)
        evidences_dto.append(DependencyEvidenceDTO(
            id=saved_ev.id,
            evidence_kind=saved_ev.evidence_kind,
            file_path=saved_ev.file_path,
            excerpt=saved_ev.excerpt,
            commit_sha=saved_ev.commit_sha,
        ))

    return RepositoryDependencyDTO(
        id=saved.id,
        organization_id=saved.organization_id,
        source_repository_id=saved.source_repository_id,
        target_repository_id=saved.target_repository_id,
        dependency_type=saved.dependency_type,
        evidence_type=saved.evidence_type,
        confidence=saved.confidence,
        source_reference=saved.source_reference,
        target_reference=saved.target_reference,
        current_state=saved.current_state,
        metadata=saved.metadata,
        evidence=evidences_dto,
    )


@router.get("/repositories/{repository_id}/dependencies", response_model=List[RepositoryDependencyDTO])
async def list_repository_dependencies(
    repository_id: UUID,
    role: str = Query("both", description="Role to filter by: 'source' (repos this repo depends on), 'target' (repos that depend on this repo), or 'both'"),
    state: str = Query("ACTIVE", description="Dependency state: 'ACTIVE', 'STALE', or empty for all"),
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Lists cross-repository dependencies involving repository_id.
    Strictly isolated by organization_id.
    """
    pool = await db_pool.get_pool()
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)

    deps = []
    if role in ("source", "both"):
        as_source = await dep_repo.get_dependencies_for_source(org_id, repository_id, state=state)
        deps.extend(as_source)
    if role in ("target", "both"):
        as_target = await dep_repo.get_dependencies_for_target(org_id, repository_id, state=state)
        existing_ids = {d.id for d in deps}
        deps.extend([d for d in as_target if d.id not in existing_ids])

    results = []
    for d in deps:
        evidence_list = await dep_repo.get_evidence_for_dependency(org_id, d.id)
        results.append(RepositoryDependencyDTO(
            id=d.id,
            organization_id=d.organization_id,
            source_repository_id=d.source_repository_id,
            target_repository_id=d.target_repository_id,
            dependency_type=d.dependency_type,
            evidence_type=d.evidence_type,
            confidence=d.confidence,
            source_reference=d.source_reference,
            target_reference=d.target_reference,
            current_state=d.current_state,
            metadata=d.metadata,
            evidence=[
                DependencyEvidenceDTO(
                    id=e.id,
                    evidence_kind=e.evidence_kind,
                    file_path=e.file_path,
                    excerpt=e.excerpt,
                    commit_sha=e.commit_sha,
                )
                for e in evidence_list
            ],
        ))
    return results


@router.get("/organizations/dependencies", response_model=List[RepositoryDependencyDTO])
async def list_organization_dependencies(
    state: str = Query("ACTIVE", description="Dependency state: 'ACTIVE', 'STALE', or empty for all"),
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves the entire cross-repository dependency topology for an organization.
    Used for organizational architecture diagrams and system blast radius mapping.
    """
    pool = await db_pool.get_pool()
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)
    deps = await dep_repo.get_all_org_dependencies(org_id, state=state)

    results = []
    for d in deps:
        evidence_list = await dep_repo.get_evidence_for_dependency(org_id, d.id)
        results.append(RepositoryDependencyDTO(
            id=d.id,
            organization_id=d.organization_id,
            source_repository_id=d.source_repository_id,
            target_repository_id=d.target_repository_id,
            dependency_type=d.dependency_type,
            evidence_type=d.evidence_type,
            confidence=d.confidence,
            source_reference=d.source_reference,
            target_reference=d.target_reference,
            current_state=d.current_state,
            metadata=d.metadata,
            evidence=[
                DependencyEvidenceDTO(
                    id=e.id,
                    evidence_kind=e.evidence_kind,
                    file_path=e.file_path,
                    excerpt=e.excerpt,
                    commit_sha=e.commit_sha,
                )
                for e in evidence_list
            ],
        ))
    return results


@router.post("/repositories/{repository_id}/discover-dependencies", response_model=List[RepositoryDependencyDTO])
async def discover_repository_dependencies(
    repository_id: UUID,
    req: DiscoverDependenciesRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Scans submitted manifest files (pom.xml, requirements.txt, package.json, openapi.yaml, etc.)
    and discovers authoritative cross-repository dependencies without embeddings or heuristics.
    """
    pool = await db_pool.get_pool()
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)
    sc_client = SourceControlClient()

    all_org_repos = await sc_client.get_organization_repositories(org_id)
    files_payload = [{"file_path": f.file_path, "content": f.content} for f in req.files]

    discovered = DependencyDiscoveryEngine.discover_dependencies_from_files(
        organization_id=org_id,
        repository_id=repository_id,
        changed_files=files_payload,
        all_org_repositories=all_org_repos,
        commit_sha=req.commit_sha or "",
    )

    results = []
    for dep, evidences in discovered:
        saved = await dep_repo.upsert_dependency(dep)
        ev_dtos = []
        for ev in evidences:
            ev.dependency_id = saved.id
            saved_ev = await dep_repo.save_evidence(ev)
            ev_dtos.append(DependencyEvidenceDTO(
                id=saved_ev.id,
                evidence_kind=saved_ev.evidence_kind,
                file_path=saved_ev.file_path,
                excerpt=saved_ev.excerpt,
                commit_sha=saved_ev.commit_sha,
            ))
        results.append(RepositoryDependencyDTO(
            id=saved.id,
            organization_id=saved.organization_id,
            source_repository_id=saved.source_repository_id,
            target_repository_id=saved.target_repository_id,
            dependency_type=saved.dependency_type,
            evidence_type=saved.evidence_type,
            confidence=saved.confidence,
            source_reference=saved.source_reference,
            target_reference=saved.target_reference,
            current_state=saved.current_state,
            metadata=saved.metadata,
            evidence=ev_dtos,
        ))
    return results


@router.get("/repositories/{repository_id}/distributed-impact", response_model=DistributedImpactDTO)
async def get_repository_distributed_impact(
    repository_id: UUID,
    pr_number: Optional[int] = Query(None, description="PR number to assess distributed impact for"),
    commit_sha: Optional[str] = Query(None, description="Commit SHA to assess"),
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Evaluates distributed change impact for a repository PR or commit:
    - Downstream repository dependents
    - Contract compatibility status (COMPATIBLE, POTENTIALLY_BREAKING, BREAKING)
    - Downstream CI and test health
    - Deterministic distributed and combined risk scores
    """
    pool = await db_pool.get_pool()
    dep_repo = AsyncpgRepositoryDependencyRepository(pool)
    sc_client = SourceControlClient()
    testing_client = TestingServiceClient()

    # 1. Fetch changed files for PR if pr_number is provided
    changed_files = []
    changed_symbols = []
    if pr_number:
        pr_data = await sc_client.get_pull_request_changes(org_id, repository_id, pr_number)
        if pr_data:
            changed_files = pr_data.get("files", [])

    # 2. Derive contract compatibility
    contract_status, contract_rationale = DependencyDiscoveryEngine.classify_contract_compatibility(
        changed_files=changed_files,
        changed_symbols=changed_symbols,
    )

    # 3. Retrieve downstream dependencies
    direct_downstream_deps = await dep_repo.get_dependencies_for_target(org_id, repository_id, state="ACTIVE")
    all_org_repos = await sc_client.get_organization_repositories(org_id)

    # 4. Ingest downstream CI & test evidence
    downstream_ci_evidence = {}
    downstream_test_evidence = {}
    for dep in direct_downstream_deps:
        downstream_rid = dep.source_repository_id
        downstream_rid_str = str(downstream_rid)
        try:
            runs = await testing_client.get_recent_workflow_runs(org_id, downstream_rid)
            if runs:
                latest_run = runs[0]
                downstream_ci_evidence[downstream_rid_str] = {
                    "id": latest_run.get("id"),
                    "workflowRunId": latest_run.get("workflowRunId") or latest_run.get("id"),
                    "status": latest_run.get("status"),
                    "conclusion": latest_run.get("conclusion") or latest_run.get("status"),
                }
                test_results = await testing_client.list_test_runs(org_id, downstream_rid)
                if test_results:
                    downstream_test_evidence[downstream_rid_str] = test_results
        except Exception as e:
            logger.debug("Could not fetch downstream CI/test data for %s: %s", downstream_rid, e)

    # 5. Execute distributed impact evaluation
    assessment = CrossRepositoryImpactEngine.analyze_distributed_impact(
        organization_id=org_id,
        source_repository_id=repository_id,
        source_repository_name=str(repository_id),
        changed_files=changed_files,
        changed_symbols=changed_symbols,
        contract_status=contract_status,
        contract_rationale=contract_rationale,
        local_risk_score=20.0,
        direct_downstream_deps=direct_downstream_deps,
        downstream_ci_evidence=downstream_ci_evidence,
        downstream_test_evidence=downstream_test_evidence,
        all_org_repositories=all_org_repos,
        pr_number=pr_number or 0,
        commit_sha=commit_sha or "",
    )

    return DistributedImpactDTO(
        distributed_risk_score=assessment.distributed_risk_score,
        distributed_risk_level=assessment.distributed_risk_level,
        combined_risk_score=assessment.combined_risk_score,
        combined_risk_level=assessment.combined_risk_level,
        direct_downstream_count=assessment.direct_downstream_count,
        transitive_downstream_count=assessment.transitive_downstream_count,
        max_cross_repo_depth=assessment.max_cross_repo_depth,
        contract_status=assessment.contract_status,
        contract_rationale=contract_rationale,
        downstream_impacts=[
            DownstreamRepositoryImpactDTO(
                repository_id=imp.repository_id,
                repository_name=imp.repository_name,
                dependency_type=imp.dependency_type,
                dependency_confidence=imp.dependency_confidence,
                evidence_type=imp.evidence_type,
                source_reference=imp.source_reference,
                target_reference=imp.target_reference,
                contract_status=imp.contract_status,
                affected_symbols=imp.affected_symbols,
                downstream_tests=imp.downstream_tests,
                ci_status=imp.ci_status,
                ci_run_id=imp.ci_run_id,
                ci_conclusion=imp.ci_conclusion,
                depth=imp.depth,
                provenance_path=imp.provenance_path,
            )
            for imp in assessment.downstream_impacts
        ],
        distributed_risk_factors=assessment.distributed_risk_factors,
        provenance=assessment.provenance,
    )


# ======================================================================
# Milestone: Engineering Workflow Automation & Controlled AI Remediation Endpoints
# ======================================================================

@router.get("/actions/{action_id}", response_model=ActionDetailsResponseDTO)
async def get_action_details(
    action_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves complete action details including structured remediation plan,
    patch proposal diff, post-patch validation, effectiveness, and audit trail.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    details = await remediation_service.get_action_details(org_id, action_id)

    a = details["action"]
    action_dto = EngineeringActionDTO(**a)

    plan_dto = None
    if details.get("plan"):
        p = details["plan"]
        steps = [RemediationStepDTO(**s) for s in p.get("steps", [])]
        plan_dto = RemediationPlanDTO(
            id=p["id"],
            action_id=p["action_id"],
            organization_id=p["organization_id"],
            repository_id=p["repository_id"],
            title=p["title"],
            summary=p["summary"],
            steps=steps,
            is_multi_repo=p.get("is_multi_repo", False),
            coordinated_repos=p.get("coordinated_repos", []),
            expected_impact=p.get("expected_impact", ""),
            provenance=p.get("provenance", {}),
            created_at=p["created_at"],
            updated_at=p["updated_at"],
        )

    patch_dto = None
    if details.get("patch"):
        patch_dto = PatchProposalDTO(**details["patch"])

    eff_dto = None
    if details.get("effectiveness"):
        eff_dto = RemediationEffectivenessDTO(**details["effectiveness"])

    events_dtos = [ActionEventDTO(**e) for e in details.get("events", [])]

    return ActionDetailsResponseDTO(
        action=action_dto,
        plan=plan_dto,
        patch=patch_dto,
        effectiveness=eff_dto,
        events=events_dtos,
        write_mode=details.get("write_mode", "ADVISORY_ONLY"),
    )


@router.post("/actions/{action_id}/approve", response_model=EngineeringActionDTO)
async def approve_action(
    action_id: str,
    req: ApproveActionRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Human Approval Gate: Explicitly records developer approval for an engineering action.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    action = await remediation_service.approve_action(
        organization_id=org_id,
        action_id=action_id,
        actor_id=req.actor_id,
        comment=req.comment,
    )
    return EngineeringActionDTO(
        id=action.id,
        action_id=action.action_id,
        analysis_id=action.analysis_id,
        organization_id=action.organization_id,
        repository_id=action.repository_id,
        pr_number=action.pr_number,
        commit_sha=action.commit_sha,
        action_type=action.action_type,
        title=action.title,
        description=action.description,
        reason=action.reason,
        severity=action.severity,
        confidence=action.confidence,
        evidence=action.evidence,
        affected_files=action.affected_files,
        affected_symbols=action.affected_symbols,
        provenance=action.provenance,
        status=action.status,
        source_finding_id=action.source_finding_id,
        source_finding_type=action.source_finding_type,
        metadata=action.metadata,
        created_at=action.created_at,
        updated_at=action.updated_at,
    )


@router.post("/actions/{action_id}/reject", response_model=EngineeringActionDTO)
async def reject_action(
    action_id: str,
    req: RejectActionRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Human Approval Gate: Explicitly records developer rejection for an engineering action.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    action = await remediation_service.reject_action(
        organization_id=org_id,
        action_id=action_id,
        actor_id=req.actor_id,
        reason=req.reason,
    )
    return EngineeringActionDTO(
        id=action.id,
        action_id=action.action_id,
        analysis_id=action.analysis_id,
        organization_id=action.organization_id,
        repository_id=action.repository_id,
        pr_number=action.pr_number,
        commit_sha=action.commit_sha,
        action_type=action.action_type,
        title=action.title,
        description=action.description,
        reason=action.reason,
        severity=action.severity,
        confidence=action.confidence,
        evidence=action.evidence,
        affected_files=action.affected_files,
        affected_symbols=action.affected_symbols,
        provenance=action.provenance,
        status=action.status,
        source_finding_id=action.source_finding_id,
        source_finding_type=action.source_finding_type,
        metadata=action.metadata,
        created_at=action.created_at,
        updated_at=action.updated_at,
    )


@router.get("/actions/{action_id}/patch", response_model=PatchProposalDTO)
async def get_action_patch(
    action_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves proposed unified diff patch for an engineering action.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    details = await remediation_service.get_action_details(org_id, action_id)
    if not details.get("patch"):
        raise HTTPException(status_code=404, detail=f"No patch proposal exists for action '{action_id}'.")
    return PatchProposalDTO(**details["patch"])


@router.post("/actions/{action_id}/validate")
async def validate_action_patch(
    action_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Executes safe static post-patch validation (in-memory patch application,
    AST syntax parsing, zero secret introduction, rule re-evaluation).
    Never executes arbitrary repository scripts.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    return await remediation_service.validate_patch(org_id, action_id)


@router.post("/actions/{action_id}/apply")
async def apply_action_patch(
    action_id: str,
    req: ApplyActionRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Controlled Patch Application Endpoint:
    Enforces:
    1. Authentication & tenant isolation
    2. Explicit developer approval (status == APPROVED)
    3. GitHub write mode check (Advisory Mode blocks application with 403)
    4. Safe branch & PR creation (never modifies main directly)
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    return await remediation_service.apply_action(
        organization_id=org_id,
        action_id=action_id,
        actor_id=req.actor_id,
        write_mode_override=req.write_mode,
    )


@router.get("/actions/{action_id}/effectiveness", response_model=RemediationEffectivenessDTO)
async def get_action_effectiveness(
    action_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves deterministic before/after effectiveness metrics for an applied remediation.
    """
    services = await get_services()
    remediation_service: RemediationService = services[10]
    details = await remediation_service.get_action_details(org_id, action_id)
    if not details.get("effectiveness"):
        raise HTTPException(status_code=404, detail=f"No effectiveness metrics found for action '{action_id}'.")
    return RemediationEffectivenessDTO(**details["effectiveness"])


# ======================================================================
# Engineering Health & Security Center Endpoints
# ======================================================================

@router.post("/repositories/{repository_id}/security-scan")
async def scan_repository_security(
    repository_id: UUID,
    req: RepositoryScanRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Executes a comprehensive deterministic security scan across repository snapshot files.
    Enforces organization & workspace isolation.
    Detects hardcoded secrets, dangerous configuration, code-level vulnerabilities, and dependency CVEs.
    """
    pool = await db_pool.get_pool()
    chunk_repo = AsyncpgCodeChunkRepository(pool)

    files_to_scan = []
    if req.files:
        files_to_scan = [{"filename": f.file_path, "content": f.content} for f in req.files]
    else:
        chunks = await chunk_repo.get_by_repository(org_id, repository_id)
        # Deduplicate files from chunks
        seen_files = {}
        for c in chunks:
            if c.file_path not in seen_files:
                seen_files[c.file_path] = c.content
            else:
                seen_files[c.file_path] += f"\n{c.content}"
        files_to_scan = [{"filename": fp, "content": cnt} for fp, cnt in seen_files.items()]

    assessment = SecurityIntelligenceEngine.scan_repository_snapshot(
        organization_id=org_id,
        repository_id=repository_id,
        files=files_to_scan,
        commit_sha=req.commit_sha or "",
        dependency_scanner_configured=req.dependency_scanner_configured,
    )
    return assessment.model_dump()


@router.post("/repositories/{repository_id}/secret-scan", response_model=SecretScanResponseDTO)
async def scan_repository_secrets(
    repository_id: UUID,
    req: SecretScanRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Executes a dedicated on-demand secret exposure scan across repository snapshot files.
    Strictly guarantees zero raw secret exposure — all detected credentials are redacted.
    Enforces tenant isolation by organization_id.
    """
    pool = await db_pool.get_pool()
    chunk_repo = AsyncpgCodeChunkRepository(pool)

    files_to_scan = []
    if req.files:
        files_to_scan = [{"filename": f.file_path, "content": f.content} for f in req.files]
    else:
        chunks = await chunk_repo.get_by_repository(org_id, repository_id)
        seen_files = {}
        for c in chunks:
            if c.file_path not in seen_files:
                seen_files[c.file_path] = c.content
            else:
                seen_files[c.file_path] += f"\n{c.content}"
        files_to_scan = [{"filename": fp, "content": cnt} for fp, cnt in seen_files.items()]

    result = SecurityIntelligenceEngine.scan_repository_for_secrets(
        organization_id=org_id,
        repository_id=repository_id,
        files=files_to_scan,
        commit_sha=req.commit_sha or "",
    )
    return SecretScanResponseDTO(**result)


@router.get("/repositories/{repository_id}/dependency-security", response_model=DependencySecurityStatusDTO)
async def get_repository_dependency_security(
    repository_id: UUID,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves dependency vulnerability scanner status and CVE findings for a repository.
    Truthfully reports PARTIAL evidence when external CVE databases are not configured.
    """
    pool = await db_pool.get_pool()
    chunk_repo = AsyncpgCodeChunkRepository(pool)
    chunks = await chunk_repo.get_by_repository(org_id, repository_id)

    files_to_scan = []
    seen_files = {}
    for c in chunks:
        if c.file_path not in seen_files:
            seen_files[c.file_path] = c.content
        else:
            seen_files[c.file_path] += f"\n{c.content}"
    files_to_scan = [{"filename": fp, "content": cnt} for fp, cnt in seen_files.items()]

    scanner = DependencyVulnerabilityScanner(is_external_configured=False)
    scan_res = scanner.scan_files(files_to_scan)

    return DependencySecurityStatusDTO(
        status=scan_res.status,
        code_level_security="AVAILABLE",
        secret_scanning="AVAILABLE",
        dependency_cve_database="NOT_CONFIGURED" if not scan_res.is_configured else "CONFIGURED",
        dependencies_scanned=scan_res.dependencies_scanned,
        vulnerabilities_found=scan_res.vulnerabilities_found,
        findings=[f.model_dump() for f in scan_res.findings],
        summary=scan_res.summary,
    )


@router.post("/analyses/{analysis_id}/re-scan", response_model=ReScanResponseDTO)
async def rescan_analysis_after_fix(
    analysis_id: UUID,
    req: ReScanRequest,
    org_id: UUID = Depends(get_tenant_org_id),
    trace_id: str = Depends(get_trace_id),
):
    """
    Authoritative Differential Re-Scan Pipeline:
    Executes authoritative re-analysis after code fixes or remediations are applied.
    Compares Before vs After:
    - Security findings change
    - Risk score change
    - Merge readiness status recalculation
    - Confirms resolved findings only via deterministic re-verification.
    """
    services = await get_services()
    orchestration_service: GeminiOrchestrationService = services[2]
    analysis_repo: AsyncpgAnalysisRepository = services[3]

    prior_analysis = await analysis_repo.get_by_id(org_id, analysis_id)
    if not prior_analysis:
        raise HTTPException(status_code=404, detail="Analysis not found or unauthorized.")

    before_risk_score = prior_analysis.risk_score
    before_merge_readiness = (prior_analysis.merge_readiness or {}).get("decision", "NOT_READY")
    before_sec = prior_analysis.security_assessment or (prior_analysis.impact_metadata or {}).get("security_assessment") or {}
    before_findings = before_sec.get("findings", [])
    before_rule_ids = {f.get("rule_id") for f in before_findings if isinstance(f, dict)}
    before_crit = before_sec.get("critical_count", 0)
    before_high = before_sec.get("high_count", 0)

    # Determine files to re-analyze
    raw_files = [f.model_dump() for f in req.changed_files] if req.changed_files else None
    pr_num = req.pr_number if req.pr_number is not None else 0
    if not pr_num and prior_analysis.entity_type == "PR":
        if "#" in prior_analysis.entity_id:
            try:
                pr_num = int(prior_analysis.entity_id.split("#")[1])
            except (ValueError, IndexError):
                pass
        elif prior_analysis.entity_id.isdigit():
            pr_num = int(prior_analysis.entity_id)

    # Determine repository_id safely
    repo_id_candidate = (
        prior_analysis.impact_metadata.get("repository_id")
        or (prior_analysis.entity_id.split("#")[0] if "#" in prior_analysis.entity_id else None)
    )
    if not repo_id_candidate and prior_analysis.entity_type == "REPO":
        repo_id_candidate = prior_analysis.entity_id
    try:
        resolved_repo_id = UUID(str(repo_id_candidate)) if repo_id_candidate else org_id
    except Exception:
        resolved_repo_id = org_id

    # Execute authoritative re-analysis
    new_analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=resolved_repo_id,
        pr_number=pr_num,
        title=f"[Re-Scan] {prior_analysis.summary[:60]}",
        description="Authoritative re-scan following remediation / code change.",
        changed_symbols=None,
        changed_files=raw_files,
        trace_id=trace_id,
    )

    after_risk_score = new_analysis.risk_score
    after_merge_readiness = (new_analysis.merge_readiness or {}).get("decision", "NOT_READY")
    after_sec = new_analysis.security_assessment or (new_analysis.impact_metadata or {}).get("security_assessment") or {}
    after_findings = after_sec.get("findings", [])
    after_rule_ids = {f.get("rule_id") for f in after_findings if isinstance(f, dict)}
    after_crit = after_sec.get("critical_count", 0)
    after_high = after_sec.get("high_count", 0)

    findings_resolved = list(before_rule_ids - after_rule_ids)
    findings_persisted = list(before_rule_ids & after_rule_ids)
    findings_new = list(after_rule_ids - before_rule_ids)
    risk_delta = round(before_risk_score - after_risk_score, 1)
    merge_readiness_delta = f"{before_merge_readiness} -> {after_merge_readiness}"
    is_clean = len(after_findings) == 0 and after_crit == 0 and after_high == 0

    summary = (
        f"Re-scan complete. Risk: {before_risk_score:.1f} -> {after_risk_score:.1f} (delta: -{risk_delta:.1f}). "
        f"Merge readiness: {merge_readiness_delta}. "
        f"Findings resolved: {len(findings_resolved)}, persisted: {len(findings_persisted)}, new: {len(findings_new)}."
    )

    meta = new_analysis.impact_metadata or {}
    updated_dto = AnalysisResponseDTO(
        id=new_analysis.id,
        analysis_id=new_analysis.id,
        organization_id=new_analysis.organization_id,
        entity_type=new_analysis.entity_type,
        entity_id=new_analysis.entity_id,
        provider=new_analysis.provider,
        model_version=new_analysis.model_version,
        prompt_version=new_analysis.prompt_version,
        summary=new_analysis.summary,
        risk_level=new_analysis.risk_level,
        risk_score=new_analysis.risk_score,
        confidence=new_analysis.confidence,
        risk_factors=new_analysis.risk_factors or [],
        impact_metadata=meta,
        test_strategy=new_analysis.test_strategy or {},
        test_effectiveness=new_analysis.test_effectiveness,
        merge_readiness=new_analysis.merge_readiness,
        review_findings=new_analysis.review_findings or [],
        security_assessment=new_analysis.security_assessment or meta.get("security_assessment"),
        reliability_assessment=new_analysis.reliability_assessment or meta.get("reliability_assessment"),
        unified_risk=new_analysis.unified_risk or meta.get("unified_risk"),
        changed_components=meta.get("changed_components"),
        direct_impact=meta.get("direct_impact"),
        transitive_impact=meta.get("transitive_impact"),
        engineering_actions=getattr(new_analysis, "engineering_actions", None) or meta.get("engineering_actions") or [],
        remediation_summary=getattr(new_analysis, "remediation_summary", None) or meta.get("remediation_summary"),
        affected_modules=meta.get("affected_modules"),
        affected_tests=meta.get("affected_tests"),
        historical_signals=meta.get("historical_signals"),
        architectural_concerns=meta.get("architectural_concerns"),
        fallback_used=new_analysis.fallback_used,
        retrieved_chunks=new_analysis.retrieved_chunks,
        execution_duration_ms=new_analysis.execution_duration_ms,
        trace_id=new_analysis.trace_id,
    )

    return ReScanResponseDTO(
        analysis_id=new_analysis.id,
        before_risk_score=before_risk_score,
        after_risk_score=after_risk_score,
        risk_delta=risk_delta,
        before_merge_readiness=before_merge_readiness,
        after_merge_readiness=after_merge_readiness,
        merge_readiness_delta=merge_readiness_delta,
        before_critical_count=before_crit,
        after_critical_count=after_crit,
        before_high_count=before_high,
        after_high_count=after_high,
        findings_resolved=findings_resolved,
        findings_persisted=findings_persisted,
        findings_new=findings_new,
        is_clean=is_clean,
        summary=summary,
        updated_analysis=updated_dto,
    )


@router.post("/explain-finding", response_model=ExplainFindingResponseDTO)
async def explain_finding_with_ai(
    req: ExplainFindingRequest,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Grounded AI Explanation Layer:
    Provides deep, contextual reasoning for deterministic findings without inventing findings
    or leaking raw secrets.
    """
    rule = req.rule_id
    what_happened = f"Deterministic security engine detected {req.title} violating rule {rule}."
    why_it_is_risky = f"This vulnerability introduces {req.severity} risk in category {req.category}."
    production_impact = "May lead to unauthorized access, privilege escalation, or service degradation."
    how_to_fix = "Apply security hardening, rotate credentials, or parameterize input."
    guidance = ["Inspect affected code at target line", "Ensure credentials are not stored in source"]

    if rule == "SEC_SECRET_AWS_KEY":
        what_happened = "An active AWS Access Key ID was committed to source code or configuration."
        why_it_is_risky = (
            "Static cloud credentials committed to version control can be harvested by malicious actors "
            "or exposed across environments, granting unauthorized access to AWS APIs, S3 buckets, and compute instances."
        )
        production_impact = "Full or partial cloud infrastructure compromise, unauthorized data exfiltration, or resource hijacking."
        how_to_fix = "Revoke the access key in the AWS IAM Console immediately. Migrate authentication to IAM Roles, AWS Secrets Manager, or short-lived STS tokens."
        guidance = [
            "Revoke access key in AWS IAM Console immediately",
            "Inspect AWS CloudTrail logs for unauthorized API calls using this key",
            "Replace static keys with IAM Roles / AWS IRSA",
            "Store secrets in AWS Secrets Manager or HashiCorp Vault",
        ]
    elif rule == "SEC_SECRET_GITHUB_TOKEN":
        what_happened = "A high-privilege GitHub Personal Access Token or OAuth Token was committed to source."
        why_it_is_risky = "Exposed tokens allow attackers to authenticate as the owner, read private repositories, modify workflows, or poison CI/CD supply chains."
        production_impact = "Source code exfiltration, pipeline tampering, unauthorized branch pushes."
        how_to_fix = "Revoke the token immediately in GitHub Settings > Developer settings. Use fine-grained GitHub Apps or repository secret environments."
        guidance = [
            "Revoke token in GitHub developer settings",
            "Audit GitHub security log for unauthorized API actions",
            "Rotate associated repository webhooks or access scopes",
        ]
    elif rule == "SEC_SECRET_STRIPE_KEY":
        what_happened = "A live Stripe production secret API key was detected in plaintext."
        why_it_is_risky = "Stripe secret keys allow direct access to payment intents, customer records, and transaction processing."
        production_impact = "Unauthorized charges, customer financial data exposure, merchant account fraud."
        how_to_fix = "Roll the API key in the Stripe Dashboard immediately. Restrict key capabilities to only required endpoints."
        guidance = [
            "Roll key in Stripe Developer Dashboard",
            "Inspect Stripe API logs for anomalous payment calls",
            "Restrict key capabilities to required services only",
        ]
    elif rule == "SEC_SECRET_PRIVATE_KEY":
        what_happened = "An unencrypted cryptographic private key was committed into the repository."
        why_it_is_risky = "Private keys decrypt confidential communications, sign certificates, or authenticate SSH/TLS sessions."
        production_impact = "Man-in-the-Middle attacks, identity impersonation, complete loss of encrypted session confidentiality."
        how_to_fix = "Rotate the key pair immediately. Invalidate any associated certificates or SSH authorized_keys entries."
        guidance = [
            "Rotate key pair immediately",
            "Invalidate any issued certificates or authorized_keys",
            "Inject private keys via Kubernetes secrets or HashiCorp Vault",
        ]
    elif rule == "SEC_SECRET_JWT_TOKEN":
        what_happened = "A signed JWT authorization token was committed to source."
        why_it_is_risky = "Tokens allow unauthorized session hijacking or privilege escalation if active."
        production_impact = "Impersonation of authenticated user or service accounts."
        how_to_fix = "Revoke the token or rotate the signing secret. Use short token lifespans and refresh token rotation."
        guidance = [
            "Invalidate user session or rotate signing key",
            "Ensure tokens have short expiration windows",
            "Do not commit auth tokens to code or test fixtures",
        ]
    elif rule == "SEC_CODE_SQL_INJECTION":
        what_happened = "Dynamic string concatenation was detected when constructing a SQL statement."
        why_it_is_risky = "Unsanitized user inputs concatenated into SQL statements allow attackers to alter query logic."
        production_impact = "Database exfiltration, unauthorized modification of records, administrative bypass."
        how_to_fix = "Use parameterized queries or ORM query builders (e.g. prepared statements with ? or named placeholders)."
        guidance = [
            "Replace string interpolation with prepared statements",
            "Use ORM parameter binding",
            "Never concatenate request parameters directly into SQL",
        ]
    elif rule == "SEC_CODE_COMMAND_INJECTION":
        what_happened = "System command execution was invoked with untrusted shell arguments or shell=True."
        why_it_is_risky = "Executing shell commands with untrusted arguments enables arbitrary Remote Code Execution (RCE)."
        production_impact = "Complete host compromise, lateral movement within network, server hijacking."
        how_to_fix = "Pass commands and arguments as discrete list elements without shell=True, or use native API libraries."
        guidance = [
            "Avoid shell=True in subprocess calls",
            "Pass arguments as separate array elements",
            "Use native language APIs instead of OS shell utilities",
        ]
    elif rule == "SEC_INSECURE_TLS_VERIFY_DISABLED":
        what_happened = "TLS certificate verification was explicitly disabled."
        why_it_is_risky = "Disabling TLS verification prevents certificate validation, allowing Man-in-the-Middle (MitM) attacks."
        production_impact = "Traffic interception, credential theft, payload modification on transit."
        how_to_fix = "Enable certificate verification (verify=True) and configure custom CA bundles if connecting to internal services."
        guidance = [
            "Set verify=True on all HTTP requests",
            "Install organizational root CAs in trusted system truststores",
            "Reject unauthorized TLS connections",
        ]

    return ExplainFindingResponseDTO(
        finding_id=req.finding_id,
        rule_id=req.rule_id,
        title=req.title,
        what_happened=what_happened,
        why_it_is_risky=why_it_is_risky,
        production_impact=production_impact,
        how_to_fix=how_to_fix,
        supporting_evidence=f"Deterministic rule {req.rule_id} at {req.file_path}:{req.line_number or 1} - {req.redacted_evidence}",
        remediation_guidance=guidance,
    )


@router.get("/analyses/{entity_type}/{entity_id}/history", response_model=List[AnalysisHistoryItemDTO])
async def get_analysis_history_comparison(
    entity_type: str,
    entity_id: str,
    org_id: UUID = Depends(get_tenant_org_id),
):
    """
    Retrieves chronological analysis history and differential finding comparison for an entity.
    Tracks progression of findings resolved, findings introduced, risk score, and merge readiness.
    """
    pool = await db_pool.get_pool()
    analysis_repo = AsyncpgAnalysisRepository(pool)
    norm_entity_type = "PR" if entity_type.upper() in ("PR", "PULL_REQUEST") else entity_type.upper()
    records = await analysis_repo.get_by_entity(org_id, norm_entity_type, entity_id)
    if not records and norm_entity_type == "PR" and "#" not in entity_id:
        all_pr_records = await analysis_repo.get_by_entity(org_id, "PR", "%")
        records = [r for r in all_pr_records if r.entity_id.endswith(f"#{entity_id}")]

    # Sort chronological
    sorted_records = sorted(records, key=lambda a: a.created_at)

    results = []
    prev_rule_ids = set()

    for a in sorted_records:
        sec = a.security_assessment or (a.impact_metadata or {}).get("security_assessment") or {}
        findings = sec.get("findings", [])
        current_rule_ids = {f.get("rule_id") for f in findings if isinstance(f, dict)}

        resolved = list(prev_rule_ids - current_rule_ids)
        new_findings = list(current_rule_ids - prev_rule_ids)

        decision = (a.merge_readiness or {}).get("decision", "NOT_READY")

        results.append(
            AnalysisHistoryItemDTO(
                analysis_id=a.id,
                entity_id=a.entity_id,
                created_at=a.created_at,
                risk_score=a.risk_score,
                risk_level=a.risk_level or "LOW",
                merge_readiness=decision,
                critical_findings_count=sec.get("critical_count", 0),
                high_findings_count=sec.get("high_count", 0),
                resolved_findings_from_previous=resolved,
                new_findings_from_previous=new_findings,
            )
        )
        prev_rule_ids = current_rule_ids

    return results




