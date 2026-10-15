import pytest
from uuid import uuid4

from app.domain.models import (
    MergeDecision,
    SecurityStatus,
    FindingSeverity,
    FindingCategory,
    FindingType,
    TestMergeRequirement,
    TestRecommendation,
)
from app.application.merge_policy import MergePolicy, get_default_merge_policy
from app.application.merge_readiness_engine import MergeReadinessEngine
from app.application.review_finding_service import ReviewFindingService
from app.application.test_intelligence_engine import TestIntelligenceEngine


@pytest.fixture
def base_context():
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "abc1234567890abcdef1234567890abcdef12345"
    pr_metadata = {
        "title": "Fix memory leak in connection pool",
        "description": "Closes pooled connection safely",
        "head_sha": commit_sha,
        "base_sha": "def0987654321fedcba0987654321fedcba09876",
        "changed_files": [{"filename": "ConnectionPool.java", "deletions": 10, "additions": 25}],
        "deletions": 10,
    }
    sync_status = {"status": "SYNCED", "last_commit_sha": commit_sha}
    risk_factors = [{"name": "blast_radius", "score": 10.0, "weight": 25.0, "details": "Contained blast radius"}]
    impact_metadata = {
        "max_depth": 1,
        "affected_services": ["core-service"],
        "total_impacted_nodes": 2,
    }
    test_strategy = {
        "must_run": [
            {
                "test_name": "ConnectionPoolTest",
                "test_file": "src/test/ConnectionPoolTest.java",
                "priority_score": 85.0,
                "priority_bucket": "MUST_RUN",
                "merge_requirement": "TEST_REQUIRED",
                "confidence": "HIGH",
            }
        ],
        "should_run": [],
        "optional": [],
    }
    actual_ci_results = {
        "status": "COMPLETED",
        "conclusion": "SUCCESS",
        "workflow_run_id": 999,
        "jobs": [{"name": "test-job", "status": "COMPLETED", "conclusion": "SUCCESS"}],
        "executed_tests": [{"name": "ConnectionPoolTest", "status": "PASSED"}],
    }
    return {
        "organization_id": org_id,
        "repository_id": repo_id,
        "pr_number": 42,
        "commit_sha": commit_sha,
        "pr_metadata": pr_metadata,
        "sync_status": sync_status,
        "deterministic_risk_score": 15.0,
        "deterministic_risk_level": "LOW",
        "risk_factors": risk_factors,
        "impact_metadata": impact_metadata,
        "test_strategy": test_strategy,
        "actual_ci_results": actual_ci_results,
        "provenance_records": [{"source": "PR_DIFF"}],
        "security_findings": [{"severity": "INFO", "title": "No security defects"}],
    }


def test_merge_readiness_ready_clean_pass(base_context):
    """Verifies that a synchronized PR with successful CI, passed required tests, and low risk yields READY."""
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.decision == MergeDecision.READY.value
    assert res.readiness_score > 90.0
    assert len(res.blocking_reasons) == 0
    assert len(res.failed_checks) == 0
    assert len(res.missing_checks) == 0


def test_merge_readiness_ready_with_warnings(base_context):
    """Verifies that advisory warnings (e.g. HIGH risk, security advisory) yield READY_WITH_WARNINGS."""
    base_context["deterministic_risk_score"] = 65.0
    base_context["deterministic_risk_level"] = "HIGH"
    base_context["security_findings"] = [{"severity": "MEDIUM", "title": "Use of deprecated cipher"}]
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)

    assert res.decision == MergeDecision.READY_WITH_WARNINGS.value
    assert len(res.warnings) > 0
    assert len(res.blocking_reasons) == 0


def test_merge_readiness_not_ready_on_failed_ci(base_context):
    """Verifies that a failing CI workflow run results in NOT_READY."""
    base_context["actual_ci_results"] = {
        "status": "COMPLETED",
        "conclusion": "FAILURE",
        "workflow_run_id": 1001,
        "jobs": [{"name": "build", "status": "COMPLETED", "conclusion": "FAILURE"}],
        "executed_tests": [],
    }
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.decision == MergeDecision.NOT_READY.value
    assert any("CI workflow failed" in r for r in res.blocking_reasons)
    assert "ci_workflow_health" in res.failed_checks


def test_merge_readiness_not_ready_on_failed_required_test(base_context):
    """Verifies that a failing merge-required test blocks merge and results in NOT_READY."""
    base_context["actual_ci_results"]["executed_tests"] = [
        {"name": "ConnectionPoolTest", "status": "FAILED"}
    ]
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.decision == MergeDecision.NOT_READY.value
    assert any("Required test failure" in r for r in res.blocking_reasons)
    assert "required_tests_validation" in res.failed_checks


def test_merge_readiness_blocked_by_missing_ci(base_context):
    """Verifies that an in-progress/running CI workflow results in BLOCKED_BY_MISSING_EVIDENCE."""
    base_context["actual_ci_results"] = {
        "status": "IN_PROGRESS",
        "conclusion": None,
        "workflow_run_id": 1002,
        "jobs": [{"name": "build", "status": "IN_PROGRESS"}],
        "executed_tests": [],
    }
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.decision == MergeDecision.BLOCKED_BY_MISSING_EVIDENCE.value
    assert "ci_workflow_health" in res.missing_checks


def test_merge_readiness_not_ready_on_critical_risk(base_context):
    """Verifies that a CRITICAL risk assessment blocks merge."""
    base_context["deterministic_risk_level"] = "CRITICAL"
    base_context["deterministic_risk_score"] = 92.0
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.decision == MergeDecision.NOT_READY.value
    assert any("CRITICAL" in r for r in res.blocking_reasons)
    assert "deterministic_risk_evaluation" in res.failed_checks


def test_merge_readiness_truthful_security_unavailable(base_context):
    """Verifies truthful reporting when static security analysis is not present."""
    base_context["security_findings"] = []  # No security scan evidence available
    res = MergeReadinessEngine.evaluate_merge_readiness(**base_context)
    assert res.security_status == SecurityStatus.SECURITY_EVIDENCE_UNAVAILABLE.value
    assert any("unavailable" in w.lower() for w in res.warnings)


def test_review_finding_deduplication():
    """Verifies that redundant review findings on the same file/symbol are deduplicated."""
    org_id = uuid4()
    repo_id = uuid4()
    raw = [
        {
            "severity": "HIGH",
            "category": "CORRECTNESS",
            "finding_type": "OBSERVED",
            "title": "Null pointer risk in acquireConnection",
            "description": "Connection might be null",
            "affected_file": "ConnectionPool.java",
            "affected_symbol": "acquireConnection",
            "evidence": "Lines 42-45",
            "recommendation": "Add null check",
        },
        {
            "severity": "HIGH",
            "category": "CORRECTNESS",
            "finding_type": "OBSERVED",
            "title": "Null pointer risk in acquireConnection",
            "description": "Duplicate finding with same root title",
            "affected_file": "ConnectionPool.java",
            "affected_symbol": "acquireConnection",
            "evidence": "Lines 42-45",
            "recommendation": "Add null check",
        },
        {
            "severity": "LOW",
            "category": "MAINTAINABILITY",
            "finding_type": "INFERRED",
            "title": "Missing method javadoc",
            "description": "Method should have doc comments",
            "affected_file": "ConnectionPool.java",
            "affected_symbol": "releaseConnection",
            "evidence": "Method signature line 80",
            "recommendation": "Add doc comments",
        },
    ]

    processed = ReviewFindingService.process_review_findings(
        organization_id=org_id,
        repository_id=repo_id,
        raw_findings=raw,
        directly_changed_files=["ConnectionPool.java"],
        directly_changed_symbols=["acquireConnection", "releaseConnection"],
    )

    assert len(processed) == 2
    assert processed[0].severity == "HIGH"
    assert processed[0].finding_type == "OBSERVED"
    assert processed[1].severity == "LOW"
    assert processed[1].finding_type == "INFERRED"


def test_validation_metrics_truthful_zero_failures():
    """Verifies that 0 CI failures returns None for correlation rather than claiming artificial 1.0 accuracy."""
    rec = TestRecommendation(
        organization_id=uuid4(),
        repository_id=uuid4(),
        test_name="AuthTest",
        test_file="AuthTest.java",
        priority_score=75.0,
        priority_bucket="MUST_RUN",
        merge_requirement=TestMergeRequirement.TEST_REQUIRED.value,
    )
    executed = [{"name": "AuthTest", "status": "PASSED"}]

    metrics = TestIntelligenceEngine.calculate_validation_metrics(
        recommended_tests=[rec],
        executed_test_results=executed,
        workflow_run_id=123,
    )

    assert metrics["prediction_to_failure_correlation"] is None
    assert metrics["required_test_failure_capture"] is None
    assert metrics["required_test_execution_rate"] == 1.0
    assert metrics["false_positive_required_test_rate"] == 1.0
    assert metrics["missed_failure_count"] == 0
