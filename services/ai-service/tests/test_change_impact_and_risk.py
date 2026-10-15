import pytest
from uuid import uuid4
from unittest.mock import AsyncMock, MagicMock
from app.domain.models import Analysis, GraphEdge, CodeChunk
from app.application.impact_engine import ChangeImpactEngine, ImpactAnalysisResult
from app.application.risk_engine import DeterministicRiskEngine, RiskAssessment
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.application.retrieval_service import RetrievalService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
)
from app.infrastructure.llm.gemini_client import GeminiClient
from app.infrastructure.client.source_control_client import SourceControlClient


def test_deterministic_risk_engine_scoring_and_boundaries():
    """
    Verifies that the DeterministicRiskEngine computes mathematically bounded scores,
    generates all 6 risk factors, and assigns correct risk levels.
    """
    # Case 1: Trivial single-file change with no blast radius and verified test
    changed_files = [{"filename": "src/main/java/com/forgeai/util/StringUtils.java", "additions": 10, "deletions": 2, "status": "modified"}]
    changed_symbols = ["StringUtils#isBlank"]
    direct_impact = []
    transitive_impact = []
    affected_modules = ["identity-service"]
    affected_tests = [{"test_name": "StringUtilsTest", "confidence": "HIGH", "relationship": "Direct AST call"}]
    historical_signals = []

    res_low = DeterministicRiskEngine.calculate_risk(
        changed_files=changed_files,
        changed_symbols=changed_symbols,
        direct_impact=direct_impact,
        transitive_impact=transitive_impact,
        affected_modules=affected_modules,
        affected_tests=affected_tests,
        historical_signals=historical_signals,
        sync_status="SYNCED",
    )

    assert res_low.risk_level == "LOW"
    assert res_low.risk_score < 25.0
    assert len(res_low.risk_factors) == 6
    assert res_low.confidence == "MEDIUM" or res_low.confidence == "HIGH"

    # Case 2: High blast radius, cross-module change with deletions and verification gap
    files_heavy = [
        {"filename": f"services/identity-service/file_{i}.java", "additions": 200, "deletions": 50, "status": "modified"}
        for i in range(8)
    ]
    files_heavy.append({"filename": "services/source-control-service/Deleted.java", "additions": 0, "deletions": 100, "status": "removed"})
    symbols_heavy = [f"Symbol_{i}" for i in range(10)]
    direct_heavy = [{"source_symbol": f"DirectCaller_{i}", "depth": 1, "edge_type": "CALLS"} for i in range(5)]
    transitive_heavy = [{"source_symbol": f"TransitiveCaller_{i}", "depth": 3, "edge_type": "CALLS"} for i in range(12)]
    modules_heavy = ["identity-service", "source-control-service", "ai-service"]
    tests_empty = []  # Verification gap

    res_critical = DeterministicRiskEngine.calculate_risk(
        changed_files=files_heavy,
        changed_symbols=symbols_heavy,
        direct_impact=direct_heavy,
        transitive_impact=transitive_heavy,
        affected_modules=modules_heavy,
        affected_tests=tests_empty,
        historical_signals=[],
        sync_status="STALE",
    )

    assert res_critical.risk_level in ("HIGH", "CRITICAL")
    assert res_critical.risk_score >= 50.0
    assert res_critical.risk_score <= 100.0  # Clamped

    # Verify factor breakdown
    factor_names = {f.name for f in res_critical.risk_factors}
    assert "Change Volume" in factor_names
    assert "Symbol Mutation Scope" in factor_names
    assert "Blast Radius & Graph Depth" in factor_names
    assert "Architectural Boundaries" in factor_names
    assert "Test Verification Gap" in factor_names
    assert "File Deletion / Breaking Changes" in factor_names


def test_change_impact_engine_partitioning_and_modules():
    """
    Verifies that ChangeImpactEngine partitions direct vs transitive blast radius,
    classifies affected modules, and assigns confidence to test relationships.
    """
    org_id = uuid4()
    repo_id = uuid4()
    pr_number = 101

    raw_files = [
        {"filename": "services/identity-service/src/main/java/com/forgeai/identity/UserService.java", "status": "modified", "additions": 25, "deletions": 5},
        {"filename": "services/source-control-service/src/main/java/com/forgeai/sourcecontrol/PushHandler.java", "status": "modified", "additions": 40, "deletions": 10},
    ]
    changed_symbols = ["UserService#authenticate", "PushHandler#handlePush"]

    impacted_nodes = [
        {"source_symbol": "AuthController#login", "target_symbol": "UserService#authenticate", "depth": 1, "edge_type": "CALLS"},
        {"source_symbol": "AuthenticationIntegrationTest#testLogin", "target_symbol": "AuthController#login", "depth": 1, "edge_type": "CALLS"},
        {"source_symbol": "ClientSessionGateway#createSession", "target_symbol": "AuthController#login", "depth": 2, "edge_type": "CALLS"},
        {"source_symbol": "ExternalApiConsumer#invoke", "target_symbol": "ClientSessionGateway#createSession", "depth": 3, "edge_type": "CALLS"},
    ]

    commits = [
        {"sha": "abc1234", "message": "feat: update UserService login logic", "authorName": "Alice"},
        {"sha": "def5678", "message": "fix: resolve push handling", "authorName": "Bob"},
    ]

    result: ImpactAnalysisResult = ChangeImpactEngine.analyze_change(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_number,
        raw_files=raw_files,
        changed_symbols=changed_symbols,
        impacted_graph_nodes=impacted_nodes,
        historical_commits=commits,
        sync_status="SYNCED",
        head_sha="head_sha_val",
        base_sha="base_sha_val",
    )

    # 1. Assert Direct vs Transitive partitioning
    assert len(result.direct_impact) == 2
    assert all(n["depth"] == 1 for n in result.direct_impact)
    assert len(result.transitive_impact) == 2
    assert all(n["depth"] > 1 for n in result.transitive_impact)

    # 2. Assert Affected Modules
    assert "identity-service" in result.affected_modules
    assert "source-control-service" in result.affected_modules
    assert len(result.architectural_concerns) >= 1  # Cross-boundary concern flagged

    # 3. Assert Test Impact with confidence levels
    test_confidences = {t["test_name"]: t["confidence"] for t in result.affected_tests}
    # AuthenticationIntegrationTest was in graph -> HIGH
    assert test_confidences.get("AuthenticationIntegrationTest#testLogin") == "HIGH"
    # Structural pairings (UserServiceTest, PushHandlerTest) -> MEDIUM
    assert test_confidences.get("UserServiceTest") == "MEDIUM"
    assert test_confidences.get("PushHandlerTest") == "MEDIUM"

    # 4. Assert Historical Signals
    assert len(result.historical_signals) >= 1
    user_service_signal = next(h for h in result.historical_signals if "UserService" in h["file_path"])
    assert user_service_signal["recent_modifications_count"] >= 1
    assert user_service_signal["last_author"] == "Alice"

    # 5. Assert Provenance Categories
    provenance_sources = {p["source"] for p in result.provenance_evidence}
    assert "PR_DIFF" in provenance_sources
    assert "GRAPH" in provenance_sources
    assert "TEST" in provenance_sources
    assert "HISTORY" in provenance_sources
    assert "SYNC_STATE" in provenance_sources


@pytest.mark.asyncio
async def test_end_to_end_engineering_intelligence_pipeline():
    """
    Integration test: executes analyze_pull_request with real Tree-sitter derivation,
    graph traversal, deterministic risk scoring, structured grounding, and persistence.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    llm_client = GeminiClient()

    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client)
    source_control_mock = AsyncMock(spec=SourceControlClient)

    org_id = uuid4()
    repo_id = uuid4()
    head_sha = "e1e2e3e4e5e6e7e8e9e0e1e2e3e4e5e6e7e8e9e0"

    # Mock source-control responses
    source_control_mock.get_pull_request_changes.return_value = {
        "repositoryId": str(repo_id),
        "number": 55,
        "title": "feat: introduce rate limiting token bucket",
        "body": "Protects public webhook endpoints from volumetric replay attacks.",
        "headSha": head_sha,
        "baseSha": "base123",
        "repositoryFullName": "forgeai/security-core",
        "files": [
            {
                "filename": "services/identity-service/src/main/java/com/forgeai/identity/RateLimiter.java",
                "status": "modified",
                "additions": 45,
                "deletions": 10,
                "content": """
package com.forgeai.identity;

public class RateLimiter {
    public boolean checkLimit(String tenantKey) {
        return true;
    }
}
""",
            }
        ],
    }
    source_control_mock.get_repository_sync_status.return_value = {
        "syncStatus": "SYNCED",
        "latestObservedSha": head_sha,
        "latestIndexedSha": head_sha,
    }
    source_control_mock.get_repository_commits.return_value = [
        {"sha": "c1", "message": "feat: RateLimiter initial stub", "authorName": "Bob"}
    ]

    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        llm_client=llm_client,
        source_control_client=source_control_mock,
    )

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=55,
    )

    # 1. Assert returned Analysis object
    assert analysis is not None
    assert analysis.organization_id == org_id
    assert analysis.entity_id == f"{repo_id}#55"
    assert analysis.risk_score >= 0.0
    assert analysis.risk_level in ("LOW", "MEDIUM", "HIGH", "CRITICAL")
    assert analysis.confidence in ("HIGH", "MEDIUM", "LOW")
    assert len(analysis.risk_factors) == 6

    # 2. Assert Impact Metadata
    meta = analysis.impact_metadata
    assert "changed_components" in meta
    assert "affected_modules" in meta
    assert "identity-service" in meta["affected_modules"]
    assert "affected_tests" in meta
    assert any("RateLimiterTest" in t["test_name"] for t in meta["affected_tests"])

    # 3. Assert Provenance Coverage
    provenance_sources = {c["source"] for c in analysis.retrieved_chunks}
    assert "PR_DIFF" in provenance_sources
    assert "TEST" in provenance_sources
    assert "SYNC_STATE" in provenance_sources
