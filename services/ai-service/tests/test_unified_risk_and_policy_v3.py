import pytest
from uuid import uuid4
from app.domain.models import (
    SecurityStatus,
    ReliabilityStatus,
    FindingSeverity,
    SecurityAssessment,
    ReliabilityAssessment,
    SecurityFinding,
    ReliabilityFinding,
)
from app.application.unified_risk_engine import UnifiedRiskEngine
from app.application.merge_policy import get_merge_policy_v3
from app.application.merge_readiness_engine import MergeReadinessEngine


def test_unified_risk_formula_calculation():
    # local=40, distributed=20, security=60, reliability=30
    # Expected base = 0.35*40 + 0.15*20 + 0.35*60 + 0.15*30
    #               = 14.0 + 3.0 + 21.0 + 4.5 = 42.5 -> MEDIUM
    sec_assessment = SecurityAssessment(
        security_status=SecurityStatus.SECURITY_WARNING.value,
        security_risk_score=60.0,
    )
    rel_assessment = ReliabilityAssessment(
        reliability_status=ReliabilityStatus.WARNING.value,
        reliability_risk_score=30.0,
    )

    unified = UnifiedRiskEngine.calculate_unified_risk(
        local_risk=40.0,
        distributed_risk=20.0,
        security_assessment=sec_assessment,
        reliability_assessment=rel_assessment,
    )

    assert pytest.approx(unified.final_risk, 0.01) == 42.5
    assert unified.final_risk_level == "MEDIUM"
    assert pytest.approx(unified.dimension_contributions["local"], 0.01) == 14.0
    assert pytest.approx(unified.dimension_contributions["distributed"], 0.01) == 3.0
    assert pytest.approx(unified.dimension_contributions["security"], 0.01) == 21.0
    assert pytest.approx(unified.dimension_contributions["reliability"], 0.01) == 4.5


def test_unified_risk_critical_security_floor_elevation():
    # local=10 (low), distributed=0, security has CRITICAL blocker (score=40), reliability=0
    # base = 0.35*10 + 0 + 0.35*40 + 0 = 3.5 + 14.0 = 17.5 (would be LOW)
    # BUT with CRITICAL blocker, floor elevates to >= 75.0 (CRITICAL)
    critical_finding = SecurityFinding(
        rule_id="SEC_SECRET_PRIVATE_KEY",
        category="SECRET_EXPOSURE",
        severity=FindingSeverity.CRITICAL.value,
        confidence="HIGH",
        title="Exposed Key",
        description="Private key exposed",
        file_path="key.pem",
        redacted_evidence="[REDACTED]",
        remediation="Revoke key",
        is_blocker=True,
    )
    sec_assessment = SecurityAssessment(
        security_status=SecurityStatus.SECURITY_BLOCKER.value,
        security_risk_score=40.0,
        critical_count=1,
        findings=[critical_finding],
    )

    unified = UnifiedRiskEngine.calculate_unified_risk(
        local_risk=10.0,
        distributed_risk=0.0,
        security_assessment=sec_assessment,
        reliability_assessment=None,
    )

    assert unified.final_risk >= 75.0
    assert unified.final_risk_level == "CRITICAL"


def test_merge_policy_v3_blocks_on_security_blocker():
    policy = get_merge_policy_v3()
    assert policy.version == "merge-policy-v3"
    assert policy.security_blockers_block_merge is True
    assert policy.reliability_blockers_block_merge is True

    sec_finding = SecurityFinding(
        rule_id="SEC_SECRET_AWS_KEY",
        category="SECRET_EXPOSURE",
        severity=FindingSeverity.CRITICAL.value,
        confidence="HIGH",
        title="Exposed AWS Key",
        description="AWS Key in config",
        file_path="config.py",
        redacted_evidence="AKIA***",
        remediation="Revoke key",
        is_blocker=True,
    )
    sec_assessment = SecurityAssessment(
        security_status=SecurityStatus.SECURITY_BLOCKER.value,
        critical_count=1,
        findings=[sec_finding],
    )

    result = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=uuid4(),
        repository_id=uuid4(),
        pr_number=1,
        commit_sha="abcdef1234567890abcdef1234567890abcdef12",
        pr_metadata={"changed_files": [{"filename": "config.py", "status": "modified"}]},
        sync_status={"status": "SYNCED", "last_commit_sha": "abcdef1234567890abcdef1234567890abcdef12"},
        deterministic_risk_score=10.0,
        deterministic_risk_level="LOW",
        risk_factors=[],
        impact_metadata={},
        test_strategy={"must_run": [], "should_run": [], "optional": []},
        actual_ci_results={"status": "completed", "conclusion": "success", "jobs": [], "executed_tests": []},
        provenance_records=[{"source": "PR_DIFF"}],
        security_assessment=sec_assessment,
        policy=policy,
    )

    assert result.decision == "NOT_READY"
    assert any("Security blocker detected" in r for r in result.blocking_reasons)
    assert result.security_status == SecurityStatus.SECURITY_BLOCKER.value


def test_merge_policy_v3_low_confidence_does_not_block():
    policy = get_merge_policy_v3()

    # Finding with LOW confidence
    low_conf_finding = SecurityFinding(
        rule_id="SEC_POSSIBLE_SECRET",
        category="SECRET_EXPOSURE",
        severity=FindingSeverity.CRITICAL.value,
        confidence="LOW",  # LOW confidence!
        title="Heuristic Secret Finding",
        description="May be a false positive",
        file_path="test.txt",
        redacted_evidence="***",
        remediation="Verify if this is real",
        is_blocker=True,
    )
    sec_assessment = SecurityAssessment(
        security_status=SecurityStatus.SECURITY_WARNING.value,
        critical_count=0,
        findings=[low_conf_finding],
    )

    result = MergeReadinessEngine.evaluate_merge_readiness(
        organization_id=uuid4(),
        repository_id=uuid4(),
        pr_number=2,
        commit_sha="1111111111111111111111111111111111111111",
        pr_metadata={"changed_files": [{"filename": "test.txt", "status": "modified"}]},
        sync_status={"status": "SYNCED", "last_commit_sha": "1111111111111111111111111111111111111111"},
        deterministic_risk_score=5.0,
        deterministic_risk_level="LOW",
        risk_factors=[],
        impact_metadata={},
        test_strategy={"must_run": [], "should_run": [], "optional": []},
        actual_ci_results={"status": "completed", "conclusion": "success", "jobs": [], "executed_tests": []},
        provenance_records=[{"source": "PR_DIFF"}],
        security_assessment=sec_assessment,
        policy=policy,
    )

    # Invariant: LOW confidence findings must NEVER block merge
    assert result.decision in ("READY", "READY_WITH_WARNINGS")
    assert not any("Security blocker detected" in r for r in result.blocking_reasons)
    assert any("Security advisory" in w for w in result.warnings)
