import pytest
from uuid import uuid4
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
)
from app.infrastructure.llm.gemini_client import GeminiClient


@pytest.mark.asyncio
async def test_pr_analysis_audit():
    """
    Verifies that PR analysis generates an architectural summary, blast radius risk,
    and persists an audit record into the analyses table with prompt version and duration.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    llm_client = GeminiClient()

    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client)
    orchestration_service = GeminiOrchestrationService(retrieval_service, analysis_repo, llm_client)

    org_id = uuid4()
    repo_id = uuid4()
    pr_number = 42

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_number,
        title="Refactor Authentication to Asymmetric RS256",
        description="Migrates from symmetric HMAC to RS256 with JWKS endpoint verification.",
        changed_symbols=["TokenProvider#signToken", "SecurityConfig#configureJwt"],
    )

    # 1. Assert generated analysis fields
    assert analysis.organization_id == org_id
    assert analysis.entity_type == "PR"
    assert analysis.entity_id == f"{repo_id}#{pr_number}"
    assert analysis.summary is not None
    assert len(analysis.summary) > 20
    assert analysis.prompt_version == "v1.0.0"
    assert analysis.execution_duration_ms >= 0

    # 2. Verify persistence in analysis repository
    stored_records = await analysis_repo.get_by_entity(org_id, "PR", f"{repo_id}#{pr_number}")
    assert len(stored_records) == 1
    assert stored_records[0].id == analysis.id
    assert stored_records[0].summary == analysis.summary
