import pytest
from uuid import uuid4
from fastapi.testclient import TestClient
from app.main import app
from app.domain.models import Analysis
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
)
from app.infrastructure.llm.gemini_client import GeminiClient


def test_w3c_trace_context_binding_in_http_middleware():
    """
    Asserts that the FastAPI TraceContextMiddleware extracts W3C traceparent,
    binds the trace_id, and returns correlated traceparent and X-Correlation-Id headers.
    """
    client = TestClient(app)
    test_trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"
    test_span_id = "00f067aa0ba902b7"
    incoming_traceparent = f"00-{test_trace_id}-{test_span_id}-01"

    response = client.get("/health", headers={"traceparent": incoming_traceparent})

    assert response.status_code == 200
    assert response.json()["status"] == "UP"

    # Verify W3C traceparent and correlation id in response headers
    assert "traceparent" in response.headers
    assert test_trace_id in response.headers["traceparent"]
    assert response.headers.get("x-correlation-id") == test_trace_id


@pytest.mark.asyncio
async def test_trace_id_persisted_in_analyses_audit():
    """
    Asserts that PR analysis orchestration records the exact originating trace_id
    into the analyses audit record and retrieves it seamlessly.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    llm_client = GeminiClient()

    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client)
    orchestration_service = GeminiOrchestrationService(retrieval_service, analysis_repo, llm_client)

    org_id = uuid4()
    repo_id = uuid4()
    pr_number = 101
    originating_trace_id = "4bf92f3577b34da6a3ce929d0e0e4736"

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_number,
        title="Trace Propagation PR",
        description="Verifies W3C trace context preservation across microservices",
        changed_symbols=["TokenRotationService#rotateWithGracePeriod"],
        trace_id=originating_trace_id,
    )

    assert analysis.trace_id == originating_trace_id

    # Retrieve from analysis repo
    records = await analysis_repo.get_by_entity(org_id, "PR", f"{repo_id}#{pr_number}")
    assert len(records) == 1
    assert records[0].trace_id == originating_trace_id
