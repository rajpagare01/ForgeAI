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
        repository_id=repo_id,
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


@pytest.mark.asyncio
async def test_repository_scoped_graph_isolation():
    """
    REGRESSION TEST (PHASE 1):
    Verifies that graph impact traversal is strictly scoped by repository_id,
    even when two repositories belong to the SAME organization.
    Edges from Repo A must NEVER affect analysis for Repo B.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    retrieval_service = RetrievalService(chunk_repo, edge_repo)

    org_id = uuid4()
    repo_a = uuid4()
    repo_b = uuid4()
    other_org = uuid4()
    repo_c = uuid4()
    sha = "a" * 40

    # Org 1 / Repo A has graph edge: ACaller -> SharedSymbol
    edge_a = GraphEdge(
        organization_id=org_id,
        repository_id=repo_a,
        source_symbol="ACaller",
        target_symbol="SharedSymbol",
        edge_type="CALLS",
        valid_at_sha=sha,
    )

    # Org 1 / Repo B has graph edge: BCaller -> SharedSymbol
    edge_b = GraphEdge(
        organization_id=org_id,
        repository_id=repo_b,
        source_symbol="BCaller",
        target_symbol="SharedSymbol",
        edge_type="CALLS",
        valid_at_sha=sha,
    )

    # Org 2 / Repo C has graph edge: CCaller -> SharedSymbol
    edge_c = GraphEdge(
        organization_id=other_org,
        repository_id=repo_c,
        source_symbol="CCaller",
        target_symbol="SharedSymbol",
        edge_type="CALLS",
        valid_at_sha=sha,
    )

    await edge_repo.batch_save_edges([edge_a, edge_b, edge_c])

    # 1. Query Repo A for SharedSymbol: MUST ONLY return ACaller
    impacted_a = await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        repository_id=repo_a,
        changed_symbols=["SharedSymbol"],
        max_depth=3,
    )
    assert len(impacted_a) == 1
    assert impacted_a[0]["source_symbol"] == "ACaller"

    # 2. Query Repo B for SharedSymbol: MUST ONLY return BCaller
    impacted_b = await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        repository_id=repo_b,
        changed_symbols=["SharedSymbol"],
        max_depth=3,
    )
    assert len(impacted_b) == 1
    assert impacted_b[0]["source_symbol"] == "BCaller"

    # 3. Query Repo C under Org 2 for SharedSymbol: MUST ONLY return CCaller
    impacted_c = await retrieval_service.get_impacted_symbols(
        organization_id=other_org,
        repository_id=repo_c,
        changed_symbols=["SharedSymbol"],
        max_depth=3,
    )
    assert len(impacted_c) == 1
    assert impacted_c[0]["source_symbol"] == "CCaller"

    # 4. Cross-tenant attempt: Requesting Repo A with other_org returns nothing
    cross_tenant = await retrieval_service.get_impacted_symbols(
        organization_id=other_org,
        repository_id=repo_a,
        changed_symbols=["SharedSymbol"],
        max_depth=3,
    )
    assert len(cross_tenant) == 0
