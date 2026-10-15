import pytest
from uuid import uuid4
from unittest.mock import AsyncMock, MagicMock
from app.domain.models import (
    TestEvidenceType,
    TestPriorityBucket,
    TestRecommendation,
    TestValidationFeedback,
)
from app.application.test_intelligence_engine import (
    TestIntelligenceEngine,
    TestStrategyResult,
)
from app.application.risk_engine import DeterministicRiskEngine
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
    InMemoryTestRecommendationRepository,
    InMemoryTestValidationFeedbackRepository,
)
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.application.retrieval_service import RetrievalService
from app.infrastructure.llm.gemini_client import GeminiClient
from app.infrastructure.client.source_control_client import SourceControlClient
from app.infrastructure.client.testing_service_client import TestingServiceClient


def test_test_intelligence_evidence_separation():
    """
    Verifies that static, runtime, and historical evidence are partitioned explicitly
    and never merged invisibly.
    """
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "abc1234567890abcdef1234567890abcdef1234"

    directly_changed_files = [
        "services/identity-service/src/main/java/com/forgeai/identity/TokenService.java"
    ]
    directly_changed_symbols = ["TokenService#rotateSecret"]

    direct_nodes = [
        {
            "source_symbol": "TokenServiceIntegrationTest#testRotation",
            "target_symbol": "TokenService#rotateSecret",
            "depth": 1,
            "edge_type": "CALLS",
        }
    ]
    transitive_nodes = []
    retrieved_chunks = [
        {
            "file_path": "services/identity-service/src/test/java/com/forgeai/identity/TokenServiceTest.java",
            "symbol_name": "TokenServiceTest#testGenerate",
            "content": "class TokenServiceTest { void testGenerate() {} }",
        }
    ]

    historical_commits = [
        {"sha": "c1", "message": "refactor TokenService and TokenServiceTest rotation", "authorName": "Alice"}
    ]

    runtime_runs = [
        {
            "workflow_name": "Continuous Integration",
            "workflow_run_id": 998877,
            "status": "completed",
            "conclusion": "success",
            "commit_sha": commit_sha,
            "duration_ms": 12500,
            "passed_tests": 45,
            "failed_tests": 0,
        }
    ]

    historical_failures = [
        {
            "test_name": "TokenServiceIntegrationTest#testRotation",
            "classname": "com.forgeai.identity.TokenServiceIntegrationTest",
            "failure_count": 2,
            "error_message": "Token expiration mismatch",
        }
    ]

    coverage_data = {
        "mechanism": "JACOCO",
        "classes": {
            "com.forgeai.identity.TokenService": [
                "TokenServiceIntegrationTest#testRotation"
            ]
        },
    }

    result: TestStrategyResult = TestIntelligenceEngine.evaluate_test_strategy(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=42,
        commit_sha=commit_sha,
        directly_changed_files=directly_changed_files,
        directly_changed_symbols=directly_changed_symbols,
        direct_impact_nodes=direct_nodes,
        transitive_impact_nodes=transitive_nodes,
        retrieved_chunks=retrieved_chunks,
        historical_commits=historical_commits,
        runtime_test_runs=runtime_runs,
        historical_failed_tests=historical_failures,
        runtime_coverage_data=coverage_data,
        deterministic_risk_score=45.0,
    )

    # 1. Assert Evidence Partitioning
    assert len(result.static_evidence) >= 2
    static_types = {e["evidence_type"] for e in result.static_evidence}
    assert TestEvidenceType.AST_CALL.value in static_types
    assert TestEvidenceType.PATH_PAIRING.value in static_types

    assert len(result.runtime_evidence) >= 2
    runtime_types = {e["evidence_type"] for e in result.runtime_evidence}
    assert TestEvidenceType.RUNTIME_COVERAGE.value in runtime_types
    assert TestEvidenceType.RUNTIME_CI.value in runtime_types

    assert len(result.historical_failures) >= 1
    assert result.historical_failures[0]["failure_count"] == 2

    # 2. Assert Priority Bucketing
    assert len(result.must_run) >= 1
    must_run_rec = result.must_run[0]
    assert must_run_rec.priority_bucket == "MUST_RUN"
    assert must_run_rec.priority_score >= 60.0
    assert must_run_rec.confidence == "HIGH"

    # 3. Assert Change-to-Test Path
    assert len(must_run_rec.evidence_path) >= 1
    assert any("Changed Component" in p or "Changed Symbol" in p for p in must_run_rec.evidence_path)

    # 4. Assert Coverage Summary
    assert result.coverage_summary["coverage_available"] is True
    assert result.coverage_summary["coverage_mechanism"] == "JACOCO"


def test_graceful_fallback_when_coverage_unavailable():
    """
    Verifies that when coverage is unavailable, the engine gracefully falls back to static evidence
    and explicitly reports coverage_available=False without fabricating claims.
    """
    org_id = uuid4()
    repo_id = uuid4()

    directly_changed_files = ["services/source-control-service/src/main/java/com/forgeai/sourcecontrol/RepoSync.java"]
    directly_changed_symbols = ["RepoSync#sync"]

    result: TestStrategyResult = TestIntelligenceEngine.evaluate_test_strategy(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=10,
        commit_sha="sha999",
        directly_changed_files=directly_changed_files,
        directly_changed_symbols=directly_changed_symbols,
        direct_impact_nodes=[],
        transitive_impact_nodes=[],
        retrieved_chunks=[],
        historical_commits=[],
        runtime_test_runs=[],
        historical_failed_tests=[],
        runtime_coverage_data=None,  # No coverage
        deterministic_risk_score=20.0,
    )

    # Coverage summary must truthfully report unavailable
    assert result.coverage_summary["coverage_available"] is False
    assert result.coverage_summary["coverage_mechanism"] == "STATIC_FALLBACK"

    # Structural pairing should be present
    all_recs = result.must_run + result.should_run + result.optional
    assert any(r.test_name == "RepoSyncTest" for r in all_recs)
    sync_test = next(r for r in all_recs if r.test_name == "RepoSyncTest")
    assert "STATIC" in sync_test.evidence_sources
    assert "RUNTIME" not in sync_test.evidence_sources
    assert sync_test.runtime_coverage_verified is False


def test_deterministic_prioritization_and_confidence_boundaries():
    """
    Verifies priority score math, selection thresholds, and confidence rules:
    - Score >= 60.0 -> MUST_RUN
    - 30.0 <= Score < 60.0 -> SHOULD_RUN
    - Score < 30.0 -> OPTIONAL
    """
    # Candidate 1: Direct AST call + Runtime coverage + Historical failure -> MUST RUN (HIGH confidence)
    cand_high = {
        "test_name": "AuthIntegrationTest",
        "test_file": "AuthIntegrationTest.java",
        "ast_call": True,
        "runtime_coverage": True,
        "blast_radius_depth": 1,
        "historical_failure_count": 3,
        "path_pairing": True,
        "cochange_count": 2,
    }
    score_h, conf_h, bucket_h = TestIntelligenceEngine._calculate_test_priority(cand_high, deterministic_risk_score=30.0)
    assert score_h >= 60.0
    assert bucket_h == TestPriorityBucket.MUST_RUN.value
    assert conf_h == "HIGH"

    # Candidate 2: Structural pairing only -> SHOULD RUN (MEDIUM confidence)
    cand_med = {
        "test_name": "UserServiceTest",
        "test_file": "UserServiceTest.java",
        "ast_call": False,
        "runtime_coverage": False,
        "blast_radius_depth": None,
        "historical_failure_count": 0,
        "path_pairing": True,
        "cochange_count": 0,
    }
    # Structural path score = 10.0 (below 30.0), so alone it's OPTIONAL; with blast depth 2 (+10.0) + cochange (+10.0) -> 30.0 (SHOULD_RUN)
    cand_med["blast_radius_depth"] = 2
    cand_med["cochange_count"] = 4
    score_m, conf_m, bucket_m = TestIntelligenceEngine._calculate_test_priority(cand_med, deterministic_risk_score=20.0)
    assert 30.0 <= score_m < 60.0
    assert bucket_m == TestPriorityBucket.SHOULD_RUN.value
    assert conf_m == "MEDIUM"

    # Candidate 3: Heuristic module suggestion -> OPTIONAL (LOW confidence)
    cand_low = {
        "test_name": "HeuristicSuite",
        "test_file": "",
        "ast_call": False,
        "runtime_coverage": False,
        "blast_radius_depth": None,
        "historical_failure_count": 0,
        "path_pairing": False,
        "cochange_count": 0,
    }
    score_l, conf_l, bucket_l = TestIntelligenceEngine._calculate_test_priority(cand_low, deterministic_risk_score=10.0)
    assert score_l < 30.0
    assert bucket_l == TestPriorityBucket.OPTIONAL.value
    assert conf_l == "LOW"


def test_post_pr_validation_metrics_calculation():
    """
    Verifies mathematically defined feedback metrics:
      - prediction_to_failure_correlation
      - test_selection_efficiency
      - high_priority_missed_count
    """
    rec_must = TestRecommendation(
        test_name="AuthServiceTest",
        test_file="AuthServiceTest.java",
        priority_score=75.0,
        priority_bucket="MUST_RUN",
        confidence="HIGH",
        reason="Direct AST",
    )
    rec_should = TestRecommendation(
        test_name="BillingServiceTest",
        test_file="BillingServiceTest.java",
        priority_score=45.0,
        priority_bucket="SHOULD_RUN",
        confidence="MEDIUM",
        reason="Transitive dependent",
    )
    rec_optional = TestRecommendation(
        test_name="NotificationTest",
        test_file="NotificationTest.java",
        priority_score=20.0,
        priority_bucket="OPTIONAL",
        confidence="LOW",
        reason="Module suite",
    )

    recommendations = [rec_must, rec_should, rec_optional]

    # CI run where:
    # 1. AuthServiceTest ran and FAILED (predicted high-priority)
    # 2. BillingServiceTest ran and PASSED
    # 3. UnrelatedTest ran and FAILED (missed high-priority)
    executed_results = [
        {"test_name": "AuthServiceTest#testLogin", "status": "FAILED"},
        {"test_name": "BillingServiceTest#testInvoice", "status": "PASSED"},
        {"test_name": "UnrelatedTest#testSomething", "status": "FAILED"},
    ]

    metrics = TestIntelligenceEngine.calculate_validation_metrics(
        recommended_tests=recommendations,
        executed_test_results=executed_results,
        workflow_run_id=123456,
    )

    assert metrics["recommended_count"] == 3
    assert metrics["executed_count"] == 2
    assert metrics["passed_count"] == 1
    assert metrics["failed_count"] == 1
    assert metrics["total_ci_failures"] == 2
    assert metrics["high_priority_missed_count"] == 1  # UnrelatedTest was not in MUST_RUN
    # prediction_to_failure_correlation = matched_failed (1) / all_failed (2) = 0.5
    assert metrics["prediction_to_failure_correlation"] == 0.5
    # test_selection_efficiency = 1 (must_run) / 3 (total executed) = 0.33
    assert metrics["test_selection_efficiency"] == 0.33


@pytest.mark.asyncio
async def test_test_intelligence_end_to_end_orchestration():
    """
    Integration test: executes PR analysis with mocked source control,
    evaluates test strategy, populates test_recommendations repository,
    and returns verified test_strategy payload in Analysis.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    test_rec_repo = InMemoryTestRecommendationRepository()
    llm_client = GeminiClient()

    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client)
    source_control_mock = AsyncMock(spec=SourceControlClient)
    testing_mock = AsyncMock(spec=TestingServiceClient)

    org_id = uuid4()
    repo_id = uuid4()
    head_sha = "f1f2f3f4f5f6f7f8f9f0f1f2f3f4f5f6f7f8f9f0"

    source_control_mock.get_pull_request_changes.return_value = {
        "repositoryId": str(repo_id),
        "number": 77,
        "title": "feat: security token encryption",
        "body": "Implements AES-256 GCM token encryption.",
        "headSha": head_sha,
        "baseSha": "base77",
        "repositoryFullName": "forgeai/crypto-core",
        "files": [
            {
                "filename": "services/identity-service/src/main/java/com/forgeai/identity/CryptoKeyService.java",
                "status": "modified",
                "additions": 30,
                "deletions": 5,
                "content": "package com.forgeai.identity; public class CryptoKeyService { public byte[] getKey() { return new byte[32]; } }",
            }
        ],
    }
    source_control_mock.get_repository_sync_status.return_value = {
        "syncStatus": "SYNCED",
        "latestObservedSha": head_sha,
        "latestIndexedSha": head_sha,
    }
    source_control_mock.get_repository_commits.return_value = [
        {"sha": "c1", "message": "feat: initial CryptoKeyService", "authorName": "Alice"}
    ]

    testing_mock.get_recent_workflow_runs.return_value = [
        {
            "workflow_name": "CI Build",
            "workflow_run_id": 112233,
            "status": "completed",
            "conclusion": "success",
            "commit_sha": head_sha,
            "duration_ms": 15000,
            "passed_tests": 12,
            "failed_tests": 0,
        }
    ]
    testing_mock.get_historical_failures.return_value = []

    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        llm_client=llm_client,
        source_control_client=source_control_mock,
        testing_client=testing_mock,
        test_rec_repo=test_rec_repo,
    )

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=77,
    )

    # Assert Analysis has test_strategy
    assert analysis is not None
    assert "test_strategy" in analysis.model_dump()
    strat = analysis.test_strategy
    assert "must_run" in strat
    assert "should_run" in strat
    assert "optional" in strat
    assert "confidence" in strat
    assert "coverage_summary" in strat

    # Assert test recommendations persisted in test_rec_repo
    persisted_recs = await test_rec_repo.get_by_analysis(org_id, analysis.id)
    assert len(persisted_recs) >= 1
    assert any("CryptoKeyServiceTest" in r.test_name for r in persisted_recs)
