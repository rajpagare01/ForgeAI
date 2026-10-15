import pytest
from uuid import uuid4
from unittest.mock import AsyncMock, MagicMock
from app.domain.models import CodeChunk, GraphEdge, IndexJobStatus
from app.application.indexing_service import IndexingService
from app.application.retrieval_service import RetrievalService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryIndexJobRepository,
)
from app.infrastructure.llm.gemini_client import GeminiClient


@pytest.mark.asyncio
async def test_incremental_sync_added_modified_and_removed_files():
    """
    Verifies that:
    1. Added files are indexed as is_current=True
    2. Modified files deactivate older chunks and index new chunks as is_current=True
    3. Removed files deactivate all chunks without deleting historical records
    4. Content-hash matching reuses embeddings without invoking Gemini
    """
    org_id = uuid4()
    repo_id = uuid4()
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    job_repo = InMemoryIndexJobRepository()

    mock_llm = MagicMock(spec=GeminiClient)
    mock_llm.generate_embedding = AsyncMock(return_value=[0.1] * 768)

    indexing_service = IndexingService(
        code_chunk_repo=chunk_repo,
        graph_edge_repo=edge_repo,
        llm_client=mock_llm,
        job_repo=job_repo,
    )
    retrieval_service = RetrievalService(
        code_chunk_repo=chunk_repo,
        graph_edge_repo=edge_repo,
        llm_client=mock_llm,
    )

    # 1. Initial Push: Add OrderService.java and PaymentService.java
    initial_files = [
        {
            "file_path": "src/OrderService.java",
            "status": "added",
            "content": "public class OrderService { public void placeOrder() {} }",
        },
        {
            "file_path": "src/PaymentService.java",
            "status": "added",
            "content": "public class PaymentService { public void charge() {} }",
        },
    ]

    res1 = await indexing_service.sync_incremental_push(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha="commit_sha_1111",
        ref="refs/heads/main",
        files=initial_files,
    )

    assert res1["status"] == "COMPLETED"
    assert res1["files_processed"] == 2
    assert res1["chunks_indexed"] > 0
    assert mock_llm.generate_embedding.call_count > 0

    # Verify both are currently searchable
    search_results = await retrieval_service.semantic_search(
        organization_id=org_id,
        query="OrderService",
        repository_id=repo_id,
        only_current=True,
    )
    assert any("OrderService" in r["file_path"] for r in search_results)

    initial_embed_calls = mock_llm.generate_embedding.call_count

    # 2. Second Push: Modify OrderService.java with identical content (cache hit test) and Remove PaymentService.java
    second_files = [
        {
            "file_path": "src/OrderService.java",
            "status": "modified",
            "content": "public class OrderService { public void placeOrder() {} }",
        },
        {
            "file_path": "src/PaymentService.java",
            "status": "removed",
            "content": None,
        },
    ]

    res2 = await indexing_service.sync_incremental_push(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha="commit_sha_2222",
        ref="refs/heads/main",
        files=second_files,
    )

    assert res2["status"] == "COMPLETED"
    assert res2["files_processed"] == 1
    assert res2["files_deactivated"] == 1
    # Verify cache hits occurred for unchanged content hashes
    assert res2["cache_hits"] > 0
    # Gemini embedding should NOT have been called again because content hash was identical!
    assert mock_llm.generate_embedding.call_count == initial_embed_calls

    # 3. Verify PaymentService chunks are deactivated (is_current=False)
    # Retrieval with only_current=True MUST NOT return PaymentService
    current_search = await retrieval_service.semantic_search(
        organization_id=org_id,
        query="PaymentService",
        repository_id=repo_id,
        only_current=True,
    )
    for r in current_search:
        assert r["file_path"] != "src/PaymentService.java"
        assert r["is_current"] is True

    # But historical records are still preserved in chunk_repo for PR provenance audit!
    all_payment_chunks = [c for c in chunk_repo.chunks if c.file_path == "src/PaymentService.java"]
    assert len(all_payment_chunks) > 0
    assert all(c.is_current is False for c in all_payment_chunks)


@pytest.mark.asyncio
async def test_tenant_and_repo_isolation_in_sync():
    """
    Verifies that push synchronization for Org A cannot affect or leak into Org B.
    """
    org_a = uuid4()
    org_b = uuid4()
    repo_a = uuid4()
    repo_b = uuid4()

    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    job_repo = InMemoryIndexJobRepository()

    mock_llm = MagicMock(spec=GeminiClient)
    mock_llm.generate_embedding = AsyncMock(return_value=[0.2] * 768)

    indexing_service = IndexingService(
        code_chunk_repo=chunk_repo,
        graph_edge_repo=edge_repo,
        llm_client=mock_llm,
        job_repo=job_repo,
    )
    retrieval_service = RetrievalService(
        code_chunk_repo=chunk_repo,
        graph_edge_repo=edge_repo,
        llm_client=mock_llm,
    )

    # Index file in Org A
    await indexing_service.sync_incremental_push(
        organization_id=org_a,
        repository_id=repo_a,
        commit_sha="commit_sha_aaa",
        ref="refs/heads/main",
        files=[{"file_path": "secret/A.py", "status": "added", "content": "def secret_a(): pass"}],
    )

    # Search in Org B MUST return empty (Zero leakage across tenants)
    results_b = await retrieval_service.semantic_search(
        organization_id=org_b,
        query="secret",
        repository_id=repo_b,
        only_current=True,
    )
    assert len(results_b) == 0

    # Search in Org A returns secret/A.py
    results_a = await retrieval_service.semantic_search(
        organization_id=org_a,
        query="secret",
        repository_id=repo_a,
        only_current=True,
    )
    assert len(results_a) > 0
    assert results_a[0]["file_path"] == "secret/A.py"
