import pytest
from uuid import uuid4
from app.domain.models import GraphEdge
from app.application.retrieval_service import RetrievalService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
)


@pytest.mark.asyncio
async def test_graph_impact_cte():
    """
    Verifies that modifying a leaf dependency in graph_edges returns all callers
    up to 3 hops deep in accurate dependency order.
    Chain: Controller -> Service -> Repository -> DatabaseHelper
    Modifying: DatabaseHelper
    Expected impacted: Repository (depth 1), Service (depth 2), Controller (depth 3)
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    retrieval_service = RetrievalService(chunk_repo, edge_repo)

    org_id = uuid4()
    repo_id = uuid4()
    sha = "c" * 40

    edges = [
        # Controller calls Service
        GraphEdge(
            organization_id=org_id,
            repository_id=repo_id,
            source_symbol="UserController#createUser",
            target_symbol="UserService#register",
            edge_type="CALLS",
            valid_at_sha=sha,
        ),
        # Service calls Repository
        GraphEdge(
            organization_id=org_id,
            repository_id=repo_id,
            source_symbol="UserService#register",
            target_symbol="UserRepository#save",
            edge_type="CALLS",
            valid_at_sha=sha,
        ),
        # Repository calls DatabaseHelper
        GraphEdge(
            organization_id=org_id,
            repository_id=repo_id,
            source_symbol="UserRepository#save",
            target_symbol="DatabaseHelper#executeInsert",
            edge_type="CALLS",
            valid_at_sha=sha,
        ),
        # Unrelated edge in different org should never be returned
        GraphEdge(
            organization_id=uuid4(),
            repository_id=repo_id,
            source_symbol="OtherOrgService#doSomething",
            target_symbol="DatabaseHelper#executeInsert",
            edge_type="CALLS",
            valid_at_sha=sha,
        ),
    ]
    await edge_repo.batch_save_edges(edges)

    # Trigger impact analysis on DatabaseHelper#executeInsert
    impacted = await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        changed_symbols=["DatabaseHelper#executeInsert"],
        max_depth=3,
    )

    # Sort by depth
    impacted.sort(key=lambda x: x["depth"])

    assert len(impacted) == 3

    assert impacted[0]["source_symbol"] == "UserRepository#save"
    assert impacted[0]["depth"] == 1

    assert impacted[1]["source_symbol"] == "UserService#register"
    assert impacted[1]["depth"] == 2

    assert impacted[2]["source_symbol"] == "UserController#createUser"
    assert impacted[2]["depth"] == 3
