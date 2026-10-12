import pytest
from uuid import uuid4
from app.domain.models import CodeChunk
from app.application.retrieval_service import RetrievalService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
)
from app.infrastructure.llm.gemini_client import GeminiClient


@pytest.mark.asyncio
async def test_tenant_isolation_vectors():
    """
    Verifies that querying embeddings for Org A NEVER returns chunks belonging to Org B,
    even with identical content and cosine similarity.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    llm_client = GeminiClient()
    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client)

    org_a = uuid4()
    org_b = uuid4()
    repo_id = uuid4()

    identical_code = "public void authenticateUser() { checkCredentials(); }"
    identical_vector = await llm_client.generate_embedding(identical_code)

    # Insert identical chunk for Org A
    chunk_a = CodeChunk(
        organization_id=org_a,
        repository_id=repo_id,
        commit_sha="a" * 40,
        file_path="src/main/AuthServiceA.java",
        symbol_name="AuthServiceA.authenticateUser",
        content=identical_code,
        content_hash="hash_a",
        embedding=identical_vector,
    )
    await chunk_repo.save_chunk(chunk_a)

    # Insert identical chunk for Org B
    chunk_b = CodeChunk(
        organization_id=org_b,
        repository_id=repo_id,
        commit_sha="b" * 40,
        file_path="src/main/AuthServiceB.java",
        symbol_name="AuthServiceB.authenticateUser",
        content=identical_code,
        content_hash="hash_b",
        embedding=identical_vector,
    )
    await chunk_repo.save_chunk(chunk_b)

    # Query with Org A tenant context
    results_for_a = await retrieval_service.semantic_search(
        organization_id=org_a,
        query="authenticate user credentials",
        limit=10,
    )

    # Query with Org B tenant context
    results_for_b = await retrieval_service.semantic_search(
        organization_id=org_b,
        query="authenticate user credentials",
        limit=10,
    )

    # Assertions
    assert len(results_for_a) == 1
    assert results_for_a[0]["organization_id"] == str(org_a)
    assert results_for_a[0]["file_path"] == "src/main/AuthServiceA.java"

    assert len(results_for_b) == 1
    assert results_for_b[0]["organization_id"] == str(org_b)
    assert results_for_b[0]["file_path"] == "src/main/AuthServiceB.java"
