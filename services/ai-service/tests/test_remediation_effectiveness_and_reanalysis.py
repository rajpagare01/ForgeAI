import pytest
from uuid import uuid4
from app.application.remediation_effectiveness_engine import RemediationEffectivenessEngine
from app.domain.models import MergeDecision


def test_effective_security_remediation():
    """
    Verifies that removing a hardcoded secret in patched code results in:
    - target finding marked as fixed
    - risk score reduction
    - is_effective == True
    - merge readiness progression from NOT_READY to READY / READY_WITH_WARNINGS
    """
    org_id = uuid4()
    repo_id = uuid4()
    action_id = "act-sec-201"
    target_file = "src/main/resources/application.properties"

    original_code = "aws.secret.access.key=AKIAIOSFODNN7EXAMPLE\nserver.port=8080\n"
    # Patched code removes the hardcoded secret
    patched_code = "aws.secret.access.key=${AWS_SECRET_KEY}\nserver.port=8080\n"

    eff = RemediationEffectivenessEngine.evaluate_remediation_effectiveness(
        organization_id=org_id,
        repository_id=repo_id,
        action_id=action_id,
        target_file=target_file,
        original_content=original_code,
        patched_content=patched_code,
        target_finding_id="SEC-001",
        before_risk_score=75.0,
        before_merge_readiness=MergeDecision.NOT_READY.value,
        tests_added=1,
        tests_passed=True,
    )

    assert eff.is_effective is True
    assert eff.after_risk_score < eff.before_risk_score
    assert eff.risk_delta > 0
    assert len(eff.new_findings_introduced) == 0
    assert eff.after_merge_readiness in (MergeDecision.READY.value, MergeDecision.READY_WITH_WARNINGS.value)
    assert eff.tests_added == 1
    assert eff.tests_passed is True


def test_ineffective_remediation_when_finding_persists():
    """
    Verifies that if the target finding is not resolved by the patch,
    is_effective is False and risk delta is 0.
    """
    org_id = uuid4()
    repo_id = uuid4()
    action_id = "act-sec-202"
    target_file = "src/main/resources/application.properties"

    # Both still have the hardcoded key
    original_code = "aws.secret.access.key=AKIAIOSFODNN7EXAMPLE\n"
    patched_code = "aws.secret.access.key=AKIAIOSFODNN7EXAMPLE\n# Just added a comment\n"

    eff = RemediationEffectivenessEngine.evaluate_remediation_effectiveness(
        organization_id=org_id,
        repository_id=repo_id,
        action_id=action_id,
        target_file=target_file,
        original_content=original_code,
        patched_content=patched_code,
        target_finding_id="SEC-001",
        before_risk_score=80.0,
        before_merge_readiness=MergeDecision.NOT_READY.value,
        tests_added=0,
        tests_passed=True,
    )

    assert eff.is_effective is False
    assert eff.risk_delta == 0.0
    assert eff.after_merge_readiness == MergeDecision.NOT_READY.value


def test_ineffective_remediation_when_new_finding_introduced():
    """
    Verifies that if a patch introduces a new vulnerability, is_effective is False.
    """
    org_id = uuid4()
    repo_id = uuid4()
    action_id = "act-sec-203"
    target_file = "src/main/java/com/forgeai/QueryService.java"

    # Original code had some generic issue
    original_code = "public class QueryService {\n}\n"
    # Patched code introduces SQL concatenation (SEC-002)
    patched_code = """public class QueryService {
    public void query(String user) {
        String sql = "SELECT * FROM users WHERE name = '" + user + "'";
    }
}
"""

    eff = RemediationEffectivenessEngine.evaluate_remediation_effectiveness(
        organization_id=org_id,
        repository_id=repo_id,
        action_id=action_id,
        target_file=target_file,
        original_content=original_code,
        patched_content=patched_code,
        target_finding_id="SEC-009",
        before_risk_score=30.0,
        before_merge_readiness=MergeDecision.READY_WITH_WARNINGS.value,
        tests_added=0,
        tests_passed=True,
    )

    assert eff.is_effective is False
    assert len(eff.new_findings_introduced) > 0
