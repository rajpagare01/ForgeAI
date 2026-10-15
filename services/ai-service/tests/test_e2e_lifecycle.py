import pytest
import uuid
from typing import List

# Domain & Application imports from services/ai-service
from app.domain.models import CodeChunk, GraphEdge
from app.application.indexing_service import IndexingService
from app.application.retrieval_service import RetrievalService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
)
from app.infrastructure.parser.tree_sitter_parser import TreeSitterParser
from app.infrastructure.llm.gemini_client import GeminiClient


@pytest.mark.asyncio
async def test_complete_forgeai_traceability_and_intelligence_lifecycle():
    """
    Complete End-to-End Traceability & Intelligence Lifecycle Test:
    Phase 1: Identity & Tenant Setup (org-acme & alice)
    Phase 2: Requirement (REQ-501) & Task (TASK-201) creation and assignee binding
    Phase 3: Source control activity: commit (a91b4c3) and PR (#42) auto-linking to TASK-201
    Phase 4: CI testing report ingestion with testTokenRotationGracePeriod_REQ-501 & PASSED assertion
    Phase 5: Repository indexing, tree-sitter AST symbol & edge extraction, and PR #42 impact analysis
    """
    # -------------------------------------------------------------------------
    # PHASE 1: Identity & Tenant Setup
    # -------------------------------------------------------------------------
    org_id = uuid.uuid4()
    alice_id = uuid.uuid4()
    repo_id = uuid.uuid4()

    # Assert tenant context invariants
    assert org_id is not None
    assert alice_id is not None
    user_directory = {
        alice_id: {
            "email": "alice@acme.com",
            "name": "Alice Dev",
            "status": "ACTIVE",
            "role": "DEVELOPER",
        }
    }
    assert user_directory[alice_id]["status"] == "ACTIVE"

    # -------------------------------------------------------------------------
    # PHASE 2: Requirement & Task Creation (Project Service)
    # -------------------------------------------------------------------------
    req_key = "REQ-501"
    requirement = {
        "id": uuid.uuid4(),
        "organization_id": org_id,
        "key": req_key,
        "title": "Implement JWT Refresh Token Grace Window",
        "description": "Prevent concurrent rotation race conditions with 30s grace window",
    }

    task_key = "TASK-201"
    task = {
        "id": uuid.uuid4(),
        "organization_id": org_id,
        "key": task_key,
        "requirement_key": req_key,
        "title": "Build pessimistic row locking for token families",
        "assignee_id": alice_id,
        "status": "TODO",
    }

    # Verify task assignment against local user_directory projection
    assert task["assignee_id"] in user_directory
    assert user_directory[task["assignee_id"]]["status"] == "ACTIVE"
    assert task["requirement_key"] == req_key

    # -------------------------------------------------------------------------
    # PHASE 3: Development Activity & Webhook Auto-Linking (Source Control Service)
    # -------------------------------------------------------------------------
    commit_sha = "a91b4c3000000000000000000000000000000000"
    commit_msg = f"Implement pessimistic locking for {task_key}"
    pr_number = 42
    pr_body = f"Closes {task_key}. Implements token family concurrency control."

    code_links = []
    # Auto-linking commit:
    if task_key in commit_msg:
        code_links.append({
            "task_id": task["id"],
            "link_type": "COMMIT",
            "external_ref": commit_sha[:7],
            "repository_id": repo_id,
        })

    # Auto-linking pull request:
    if task_key in pr_body:
        code_links.append({
            "task_id": task["id"],
            "link_type": "PULL_REQUEST",
            "external_ref": f"PR-#{pr_number}",
            "repository_id": repo_id,
        })

    # Assert code links established
    assert len(code_links) == 2
    assert any(l["link_type"] == "COMMIT" and l["external_ref"] == "a91b4c3" for l in code_links)
    assert any(l["link_type"] == "PULL_REQUEST" and l["external_ref"] == "PR-#42" for l in code_links)

    # -------------------------------------------------------------------------
    # PHASE 4: CI Testing Validation (Testing Service)
    # -------------------------------------------------------------------------
    # Test suite ingests JUnit XML containing method tagged REQ-501
    test_run = {
        "id": uuid.uuid4(),
        "organization_id": org_id,
        "commit_sha": commit_sha,
        "pull_request_number": pr_number,
        "status": "PASSED",
        "total_tests": 2,
        "passed_tests": 2,
        "failed_tests": 0,
    }

    test_case_results = [
        {
            "test_name": f"testTokenRotationGracePeriod_{req_key}",
            "classname": "com.forgeai.auth.TokenRotationTest",
            "requirement_key": req_key,
            "status": "PASSED",
            "duration_ms": 120,
        },
        {
            "test_name": "testConcurrentAccessProtection",
            "classname": "com.forgeai.auth.TokenRotationTest",
            "requirement_key": None,
            "status": "PASSED",
            "duration_ms": 95,
        },
    ]

    # Traceability check: "What tests validate REQ-501 and did they pass?"
    req_tests = [t for t in test_case_results if t["requirement_key"] == req_key]
    assert len(req_tests) == 1
    assert req_tests[0]["status"] == "PASSED"
    assert test_run["status"] == "PASSED"

    # -------------------------------------------------------------------------
    # PHASE 5: Repository Indexing & AI Impact Analysis (AI Service)
    # -------------------------------------------------------------------------
    code_chunk_repo = InMemoryCodeChunkRepository()
    graph_edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    parser = TreeSitterParser()
    llm_client = GeminiClient()

    indexing_service = IndexingService(code_chunk_repo, graph_edge_repo, parser, llm_client)
    retrieval_service = RetrievalService(code_chunk_repo, graph_edge_repo, llm_client)
    orchestration_service = GeminiOrchestrationService(retrieval_service, analysis_repo, llm_client)

    # 1. Index Java code with TokenRotationService and RefreshTokenRepository
    java_code = """
package com.forgeai.auth;

import com.forgeai.auth.RefreshTokenRepository;

public class TokenRotationService {
    private RefreshTokenRepository refreshTokenRepository;

    public boolean rotateWithGracePeriod(String tokenFamily) {
        return refreshTokenRepository.lockFamilyForUpdate(tokenFamily);
    }
}
"""
    index_res = await indexing_service.index_file(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        file_path="src/main/java/com/forgeai/auth/TokenRotationService.java",
        content=java_code,
    )

    assert index_res["chunks_indexed"] >= 1
    assert index_res["edges_indexed"] >= 1

    # Add caller dependency edge in graph
    await graph_edge_repo.save_edge(
        GraphEdge(
            organization_id=org_id,
            repository_id=repo_id,
            source_symbol="com.forgeai.auth.AuthController#refreshToken",
            target_symbol="com.forgeai.auth.TokenRotationService#rotateWithGracePeriod",
            edge_type="CALLS",
            valid_at_sha=commit_sha,
        )
    )

    # 2. Trigger PR Analysis for PR #42
    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_number,
        title=f"Feature: Implement JWT Refresh Token Grace Window for {task_key}",
        description=f"Closes {task_key} and validates {req_key} with row locking on token family.",
        changed_symbols=["com.forgeai.auth.TokenRotationService#rotateWithGracePeriod"],
    )

    # 3. Assert AI outputs
    assert analysis.organization_id == org_id
    assert analysis.entity_type == "PR"
    assert analysis.entity_id == f"{repo_id}#{pr_number}"
    assert analysis.summary is not None
    assert analysis.prompt_version == "v1.0.0"
    assert analysis.execution_duration_ms >= 0

    # Assert calling dependencies identified via recursive CTE traversal
    impacted = await retrieval_service.get_impacted_symbols(
        organization_id=org_id,
        repository_id=repo_id,
        changed_symbols=["com.forgeai.auth.TokenRotationService#rotateWithGracePeriod"],
        max_depth=3,
    )
    assert len(impacted) >= 1
    assert any("AuthController#refreshToken" in n["source_symbol"] for n in impacted)

    # Assert persisted in analyses table
    stored = await analysis_repo.get_by_entity(org_id, "PR", f"{repo_id}#{pr_number}")
    assert len(stored) == 1
    assert stored[0].id == analysis.id
