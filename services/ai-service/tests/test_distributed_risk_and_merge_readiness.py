"""
Tests for Distributed Risk Engine and Distributed Merge Readiness:
- Deterministic distributed risk formula validation
- Zero points for LOW-confidence dependencies
- Combined risk score formula and categorical levels
- Merge Readiness v2 with distributed checks:
  * BREAKING contract with dependents + CI/test failure -> NOT_READY
  * POTENTIALLY_BREAKING contract -> READY_WITH_WARNINGS
  * LOW-confidence dependencies only -> Non-blocking
  * COMPATIBLE contract + healthy CI -> READY
"""
import pytest
from uuid import uuid4

from app.domain.models import (
    MergeDecision,
    RepositoryDependency,
    DownstreamRepositoryImpact,
    DependencyType,
    DependencyEvidenceType,
    DependencyConfidence,
    ContractCompatibilityStatus,
    DownstreamCIStatus,
    DistributedRiskAssessment,
)
from app.application.merge_policy import get_default_merge_policy, get_merge_policy_v2
from app.application.merge_readiness_engine import MergeReadinessEngine
from app.application.distributed_risk_engine import DistributedRiskEngine
from app.application.cross_repo_impact_engine import CrossRepositoryImpactEngine


@pytest.fixture
def base_context():
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "abc1234567890abcdef1234567890abcdef12345"
    pr_metadata = {
        "title": "Update billing contract endpoint",
        "description": "Refactors public API endpoints",
        "head_sha": commit_sha,
        "base_sha": "def0987654321fedcba0987654321fedcba09876",
        "changed_files": [{"filename": "BillingController.java", "deletions": 5, "additions": 15}],
        "deletions": 5,
    }
    sync_status = {"status": "SYNCED", "last_commit_sha": commit_sha}
    risk_factors = [{"name": "blast_radius", "score": 10.0, "weight": 25.0, "details": "Contained blast radius"}]
    impact_metadata = {
        "max_depth": 1,
        "affected_services": ["billing-service"],
        "total_impacted_nodes": 2,
    }
    test_strategy = {
        "must_run": [
            {
                "test_name": "BillingControllerTest",
                "test_file": "src/test/BillingControllerTest.java",
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
        "executed_tests": [{"name": "BillingControllerTest", "status": "PASSED"}],
    }
    return {
        "org_id": org_id,
        "repo_id": repo_id,
        "commit_sha": commit_sha,
        "pr_metadata": pr_metadata,
        "sync_status": sync_status,
        "risk_factors": risk_factors,
        "impact_metadata": impact_metadata,
        "test_strategy": test_strategy,
        "actual_ci_results": actual_ci_results,
        "security_findings": [{"severity": "INFO", "title": "No security defects"}],
        "provenance_records": [{"source": "PR_DIFF"}],
    }


def test_distributed_risk_formula_deterministic_calculation():
    """
    Verifies that distributed risk score follows the strict deterministic formula:
    - 2 direct downstream repos * 3.0 = 6.0
    - BREAKING contract = 20.0
    - 1 downstream CI failure = 7.5
    - Raw = 33.5
    - Combined with local risk 30.0: 30.0 + 33.5 * 0.4 = 43.4 (MEDIUM)
    """
    downstream_repo1 = uuid4()
    downstream_repo2 = uuid4()

    impacts = [
        DownstreamRepositoryImpact(
            repository_id=downstream_repo1,
            repository_name="frontend-app",
            dependency_type=DependencyType.HTTP_API,
            dependency_confidence=DependencyConfidence.HIGH,
            evidence_type=DependencyEvidenceType.EXPLICIT,
            contract_status=ContractCompatibilityStatus.BREAKING,
            ci_status=DownstreamCIStatus.FAIL,
            affected_symbols=[],
        ),
        DownstreamRepositoryImpact(
            repository_id=downstream_repo2,
            repository_name="notification-service",
            dependency_type=DependencyType.SHARED_LIBRARY,
            dependency_confidence=DependencyConfidence.HIGH,
            evidence_type=DependencyEvidenceType.EXPLICIT,
            contract_status=ContractCompatibilityStatus.BREAKING,
            ci_status=DownstreamCIStatus.PASS,
            affected_symbols=[],
        ),
    ]

    result = DistributedRiskEngine.compute_distributed_risk(
        local_risk_score=30.0,
        downstream_impacts=impacts,
        contract_status=ContractCompatibilityStatus.BREAKING,
        contract_rationale="Removed mandatory user endpoint",
    )

    assert result["distributed_risk_score"] == 33.5
    assert result["distributed_risk_level"] == "MEDIUM"
    assert result["combined_risk_score"] == 43.4
    assert result["combined_risk_level"] == "MEDIUM"
    assert result["direct_downstream_count"] == 2
    assert len(result["factors"]) >= 3


def test_low_confidence_dependencies_zero_risk_points():
    """
    Ensures that LOW-confidence heuristic dependencies contribute 0 points
    to the distributed risk score.
    """
    downstream_repo = uuid4()
    impacts = [
        DownstreamRepositoryImpact(
            repository_id=downstream_repo,
            repository_name="analytics-service",
            dependency_type=DependencyType.OTHER_EXPLICIT,
            dependency_confidence=DependencyConfidence.LOW,
            evidence_type=DependencyEvidenceType.DISCOVERED,
            contract_status=ContractCompatibilityStatus.BREAKING,
            ci_status=DownstreamCIStatus.FAIL,
        )
    ]

    result = DistributedRiskEngine.compute_distributed_risk(
        local_risk_score=15.0,
        downstream_impacts=impacts,
        contract_status=ContractCompatibilityStatus.BREAKING,
    )

    assert result["distributed_risk_score"] == 0.0
    assert result["distributed_risk_level"] == "NONE"
    assert result["combined_risk_score"] == 15.0
    assert result["direct_downstream_count"] == 0


def test_merge_readiness_blocked_by_breaking_contract_and_downstream_ci_fail(base_context):
    """
    Evaluates Merge Policy v2:
    BREAKING contract with active HIGH-confidence downstream dependent + downstream CI failure
    must produce NOT_READY decision with blocking reasons and failed checks.
    """
    downstream_repo = uuid4()
    dist_assessment = DistributedRiskAssessment(
        distributed_risk_score=35.0,
        distributed_risk_level="HIGH",
        combined_risk_score=50.0,
        combined_risk_level="HIGH",
        direct_downstream_count=1,
        contract_status=ContractCompatibilityStatus.BREAKING,
        downstream_impacts=[
            DownstreamRepositoryImpact(
                repository_id=downstream_repo,
                repository_name="client-service",
                dependency_type=DependencyType.HTTP_API,
                dependency_confidence=DependencyConfidence.HIGH,
                contract_status=ContractCompatibilityStatus.BREAKING,
                ci_status=DownstreamCIStatus.FAIL,
            )
        ],
    )

    decision = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=base_context["org_id"],
        repository_id=base_context["repo_id"],
        pr_number=42,
        commit_sha=base_context["commit_sha"],
        pr_metadata=base_context["pr_metadata"],
        sync_status=base_context["sync_status"],
        deterministic_risk_score=20.0,
        deterministic_risk_level="LOW",
        risk_factors=base_context["risk_factors"],
        impact_metadata=base_context["impact_metadata"],
        test_strategy=base_context["test_strategy"],
        actual_ci_results=base_context["actual_ci_results"],
        policy=get_merge_policy_v2(),
        distributed_risk_assessment=dist_assessment,
    )

    assert decision.decision == MergeDecision.NOT_READY.value
    assert "contract_compatibility" in decision.failed_checks
    assert "downstream_ci_health" in decision.failed_checks
    assert any("BREAKING contract" in r for r in decision.blocking_reasons)
    assert any("downstream" in r.lower() for r in decision.blocking_reasons)


def test_merge_readiness_ready_with_warnings_for_potentially_breaking_contract(base_context):
    """
    POTENTIALLY_BREAKING contract change where downstream CI passes
    must produce READY_WITH_WARNINGS (non-blocking advisory warning).
    """
    downstream_repo = uuid4()
    dist_assessment = DistributedRiskAssessment(
        distributed_risk_score=15.0,
        distributed_risk_level="LOW",
        combined_risk_score=25.0,
        combined_risk_level="MEDIUM",
        direct_downstream_count=1,
        contract_status=ContractCompatibilityStatus.POTENTIALLY_BREAKING,
        downstream_impacts=[
            DownstreamRepositoryImpact(
                repository_id=downstream_repo,
                repository_name="reporting-service",
                dependency_type=DependencyType.SHARED_SCHEMA,
                dependency_confidence=DependencyConfidence.HIGH,
                contract_status=ContractCompatibilityStatus.POTENTIALLY_BREAKING,
                ci_status=DownstreamCIStatus.PASS,
            )
        ],
    )

    decision = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=base_context["org_id"],
        repository_id=base_context["repo_id"],
        pr_number=43,
        commit_sha=base_context["commit_sha"],
        pr_metadata=base_context["pr_metadata"],
        sync_status=base_context["sync_status"],
        deterministic_risk_score=10.0,
        deterministic_risk_level="LOW",
        risk_factors=base_context["risk_factors"],
        impact_metadata=base_context["impact_metadata"],
        test_strategy=base_context["test_strategy"],
        actual_ci_results=base_context["actual_ci_results"],
        policy=get_merge_policy_v2(),
        distributed_risk_assessment=dist_assessment,
    )

    assert decision.decision == MergeDecision.READY_WITH_WARNINGS.value
    assert len(decision.failed_checks) == 0
    assert any("POTENTIALLY_BREAKING" in w for w in decision.warnings)


def test_merge_readiness_low_confidence_dependencies_never_block(base_context):
    """
    Ensures that when only LOW-confidence dependencies are present,
    they NEVER cause a merge block, even if CI status is unknown.
    """
    downstream_repo = uuid4()
    dist_assessment = DistributedRiskAssessment(
        distributed_risk_score=0.0,
        distributed_risk_level="NONE",
        combined_risk_score=10.0,
        combined_risk_level="LOW",
        direct_downstream_count=0,
        contract_status=ContractCompatibilityStatus.COMPATIBLE,
        downstream_impacts=[
            DownstreamRepositoryImpact(
                repository_id=downstream_repo,
                repository_name="legacy-scripts",
                dependency_type=DependencyType.OTHER_EXPLICIT,
                dependency_confidence=DependencyConfidence.LOW,
                contract_status=ContractCompatibilityStatus.UNKNOWN,
                ci_status=DownstreamCIStatus.UNKNOWN,
            )
        ],
    )

    decision = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=base_context["org_id"],
        repository_id=base_context["repo_id"],
        pr_number=44,
        commit_sha=base_context["commit_sha"],
        pr_metadata=base_context["pr_metadata"],
        sync_status=base_context["sync_status"],
        deterministic_risk_score=10.0,
        deterministic_risk_level="LOW",
        risk_factors=base_context["risk_factors"],
        impact_metadata=base_context["impact_metadata"],
        test_strategy=base_context["test_strategy"],
        actual_ci_results=base_context["actual_ci_results"],
        security_findings=base_context["security_findings"],
        provenance_records=base_context["provenance_records"],
        policy=get_merge_policy_v2(),
        distributed_risk_assessment=dist_assessment,
    )

    assert decision.decision == MergeDecision.READY.value
    assert len(decision.failed_checks) == 0
    assert len(decision.blocking_reasons) == 0


def test_merge_readiness_compatible_contract_ready(base_context):
    """
    Backward-compatible contract change with healthy downstream CI
    must produce READY decision with 100.0 or near-100.0 readiness score.
    """
    downstream_repo = uuid4()
    dist_assessment = DistributedRiskAssessment(
        distributed_risk_score=3.0,
        distributed_risk_level="NONE",
        combined_risk_score=11.2,
        combined_risk_level="LOW",
        direct_downstream_count=1,
        contract_status=ContractCompatibilityStatus.COMPATIBLE,
        downstream_impacts=[
            DownstreamRepositoryImpact(
                repository_id=downstream_repo,
                repository_name="mobile-backend",
                dependency_type=DependencyType.HTTP_API,
                dependency_confidence=DependencyConfidence.HIGH,
                contract_status=ContractCompatibilityStatus.COMPATIBLE,
                ci_status=DownstreamCIStatus.PASS,
            )
        ],
    )

    decision = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=base_context["org_id"],
        repository_id=base_context["repo_id"],
        pr_number=45,
        commit_sha=base_context["commit_sha"],
        pr_metadata=base_context["pr_metadata"],
        sync_status=base_context["sync_status"],
        deterministic_risk_score=10.0,
        deterministic_risk_level="LOW",
        risk_factors=base_context["risk_factors"],
        impact_metadata=base_context["impact_metadata"],
        test_strategy=base_context["test_strategy"],
        actual_ci_results=base_context["actual_ci_results"],
        security_findings=base_context["security_findings"],
        provenance_records=base_context["provenance_records"],
        policy=get_merge_policy_v2(),
        distributed_risk_assessment=dist_assessment,
    )

    assert decision.decision == MergeDecision.READY.value
    assert "contract_compatibility" in decision.completed_checks
    assert "downstream_ci_health" in decision.completed_checks
    assert "distributed_dependency_health" in decision.completed_checks
    assert len(decision.failed_checks) == 0
