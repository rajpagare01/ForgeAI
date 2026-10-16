import pytest
from uuid import uuid4
from app.application.action_derivation_engine import ActionDerivationEngine
from app.domain.models import (
    SecurityFinding,
    ReliabilityFinding,
    SecurityCategory,
    ReliabilityCategory,
    FindingSeverity,
    ActionType,
    ActionSeverity,
    ActionStatus,
    PlanStepStatus,
    ContractCompatibilityStatus,
)


@pytest.mark.asyncio
async def test_action_derivation_security_finding():
    """
    Verifies that SecurityFinding (e.g. SECRET_EXPOSURE)
    deterministically derives a FIX_SECURITY EngineeringAction and RemediationPlan.
    """
    org_id = uuid4()
    repo_id = uuid4()
    pr_num = 101
    commit_sha = "a1b2c3d4e5f6"

    sec_finding = SecurityFinding(
        rule_id="SEC-001",
        category=SecurityCategory.SECRET_EXPOSURE.value,
        severity=FindingSeverity.CRITICAL.value,
        title="Hardcoded AWS secret access key",
        file_path="src/main/resources/application.properties",
        line_number=14,
        description="Hardcoded AWS secret access key detected",
        redacted_evidence="aws.secret.access.key=[REDACTED]",
        remediation="Move credentials to environment variables or KMS",
    )

    actions, plans = ActionDerivationEngine.derive_actions_and_plans(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_num,
        commit_sha=commit_sha,
        security_findings=[sec_finding],
        reliability_findings=[],
        test_strategy_dict={"must_run": []},
        changed_files=[{"filename": "src/main/resources/application.properties", "patch": "..."}],
    )

    assert len(actions) >= 1
    sec_action = next(a for a in actions if a.action_type == ActionType.FIX_SECURITY.value)
    assert sec_action.severity == ActionSeverity.CRITICAL.value
    assert sec_action.status == ActionStatus.PROPOSED.value
    assert any("application.properties" in f for f in sec_action.affected_files)
    assert sec_action.source_finding_id == "SEC-001"

    # Verify corresponding remediation plan exists
    assert len(plans) >= 1
    plan = plans[0]
    assert plan.action_id == sec_action.action_id
    assert len(plan.steps) >= 1
    assert any("secret" in step.change_description.lower() or "environment" in step.change_description.lower() for step in plan.steps)


@pytest.mark.asyncio
async def test_action_derivation_reliability_finding():
    """
    Verifies that ReliabilityFinding
    deterministically derives a FIX_RELIABILITY action and step-by-step remediation plan.
    """
    org_id = uuid4()
    repo_id = uuid4()
    pr_num = 102
    commit_sha = "f6e5d4c3b2a1"

    rel_finding = ReliabilityFinding(
        rule_id="REL-002",
        category=ReliabilityCategory.RESOURCE_LEAK.value,
        severity=FindingSeverity.HIGH.value,
        title="Unbounded thread pool executor",
        file_path="src/main/java/com/forgeai/pool/ConnectionManager.java",
        line_number=45,
        description="Unbounded thread pool executor instantiated without queue limit",
        evidence="Executors.newCachedThreadPool()",
        remediation="Configure ThreadPoolExecutor with bounded ArrayBlockingQueue and rejection policy",
    )

    actions, plans = ActionDerivationEngine.derive_actions_and_plans(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_num,
        commit_sha=commit_sha,
        security_findings=[],
        reliability_findings=[rel_finding],
        test_strategy_dict={"must_run": []},
        changed_files=[{"filename": "src/main/java/com/forgeai/pool/ConnectionManager.java", "patch": "..."}],
    )

    assert len(actions) >= 1
    rel_action = next(a for a in actions if a.action_type == ActionType.FIX_RELIABILITY.value)
    assert rel_action.severity == ActionSeverity.HIGH.value
    assert rel_action.status == ActionStatus.PROPOSED.value
    assert any("ConnectionManager.java" in f for f in rel_action.affected_files)
    assert rel_action.source_finding_id == "REL-002"

    matching_plan = next(p for p in plans if p.action_id == rel_action.action_id)
    assert len(matching_plan.steps) >= 1
    assert matching_plan.steps[0].step_number == 1
    assert matching_plan.steps[0].status == PlanStepStatus.PENDING.value


@pytest.mark.asyncio
async def test_action_derivation_test_gaps():
    """
    Verifies that test strategy gaps derive RUN_TEST and ADD_TEST actions.
    """
    org_id = uuid4()
    repo_id = uuid4()
    pr_num = 103
    commit_sha = "c3d4e5f6a1b2"

    test_strategy = {
        "must_run": [
            {
                "test_name": "testTokenRotationGracePeriod",
                "test_file": "src/test/java/com/forgeai/auth/TokenRotationTest.java",
                "reason": "Direct caller of modified AuthTokenService",
            }
        ],
    }

    merge_readiness = {
        "blocking_reasons": ["Missing required test coverage for AuthTokenService"]
    }

    actions, plans = ActionDerivationEngine.derive_actions_and_plans(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_num,
        commit_sha=commit_sha,
        security_findings=[],
        reliability_findings=[],
        test_strategy_dict=test_strategy,
        merge_readiness=merge_readiness,
        changed_files=[{"filename": "src/main/java/com/forgeai/auth/AuthTokenService.java", "patch": "..."}],
    )

    test_actions = [a for a in actions if a.action_type in (ActionType.RUN_TEST.value, ActionType.ADD_TEST.value)]
    assert len(test_actions) >= 1
    assert any(a.action_type == ActionType.RUN_TEST.value for a in test_actions)
    assert any(a.action_type == ActionType.ADD_TEST.value for a in test_actions)


@pytest.mark.asyncio
async def test_action_derivation_cross_repo_contract():
    """
    Verifies that breaking cross-repository contract changes derive UPDATE_API_CONTRACT
    with multi-repo coordinated remediation steps.
    """
    org_id = uuid4()
    repo_id = uuid4()
    pr_num = 104
    commit_sha = "789abc456def"

    distributed_impact = {
        "contract_status": ContractCompatibilityStatus.BREAKING.value,
        "downstream_impacts": [
            {
                "repository_id": str(uuid4()),
                "repository_name": "rajpagare01/order-service",
                "impacted_symbol": "PaymentClient#processPayment",
                "reason": "Removed mandatory parameter 'currency' in provider API",
            }
        ]
    }

    actions, plans = ActionDerivationEngine.derive_actions_and_plans(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=pr_num,
        commit_sha=commit_sha,
        security_findings=[],
        reliability_findings=[],
        test_strategy_dict={"must_run": []},
        distributed_impact=distributed_impact,
    )

    contract_actions = [a for a in actions if a.action_type == ActionType.UPDATE_API_CONTRACT.value]
    assert len(contract_actions) >= 1
    ca = contract_actions[0]
    assert ca.severity in (ActionSeverity.CRITICAL.value, ActionSeverity.HIGH.value)
    assert ca.status == ActionStatus.PROPOSED.value

    plan = next(p for p in plans if p.action_id == ca.action_id)
    assert plan.is_multi_repo is True
    assert len(plan.steps) >= 2
    assert any("order-service" in s.change_description or "PaymentClient" in s.change_description for s in plan.steps)
