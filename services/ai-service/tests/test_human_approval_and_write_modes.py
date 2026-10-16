import pytest
from uuid import uuid4
from fastapi import HTTPException
from app.domain.models import (
    EngineeringAction,
    ActionType,
    ActionStatus,
    ActionSeverity,
    PatchProposal,
    PatchStatus,
    GitHubWriteMode,
)
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryEngineeringActionRepository,
    InMemoryRemediationPlanRepository,
    InMemoryPatchProposalRepository,
    InMemoryRemediationEffectivenessRepository,
    InMemoryActionEventRepository,
)
from app.application.remediation_service import RemediationService


@pytest.fixture
def remediation_service_fixture():
    action_repo = InMemoryEngineeringActionRepository()
    plan_repo = InMemoryRemediationPlanRepository()
    patch_repo = InMemoryPatchProposalRepository()
    eff_repo = InMemoryRemediationEffectivenessRepository()
    event_repo = InMemoryActionEventRepository()

    service = RemediationService(
        action_repo=action_repo,
        plan_repo=plan_repo,
        patch_repo=patch_repo,
        effectiveness_repo=eff_repo,
        event_repo=event_repo,
        default_write_mode=GitHubWriteMode.ADVISORY_ONLY.value,
    )
    return service, action_repo, patch_repo, event_repo


@pytest.mark.asyncio
async def test_action_approval_lifecycle(remediation_service_fixture):
    """
    Verifies that a developer can approve an action, transitioning it to APPROVED
    and recording an immutable audit event in action_events.
    """
    service, action_repo, _, event_repo = remediation_service_fixture
    org_id = uuid4()
    repo_id = uuid4()

    action = EngineeringAction(
        action_id="act-sec-101",
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=42,
        commit_sha="a1b2c3d4",
        action_type=ActionType.FIX_SECURITY.value,
        severity=ActionSeverity.HIGH.value,
        title="Fix SQL Injection in AuthQuery",
        description="Replace statement concatenation with PreparedStatement",
        reason="SEC-002: SQL concatenation vulnerability",
        evidence="SELECT * FROM users WHERE id = ' + id",
        affected_files=["src/main/java/com/forgeai/auth/AuthQuery.java"],
        status=ActionStatus.PROPOSED.value,
    )
    await action_repo.save_actions([action])

    # Developer approves action
    updated = await service.approve_action(
        organization_id=org_id,
        action_id="act-sec-101",
        actor_id="developer_alice",
        comment="Looks correct, approve remediation",
    )

    assert updated.status == ActionStatus.APPROVED.value

    # Check audit events
    events = await event_repo.get_events_for_action(org_id, "act-sec-101")
    assert len(events) == 1
    assert events[0].event_type == "APPROVED"
    assert events[0].actor_id == "developer_alice"


@pytest.mark.asyncio
async def test_action_rejection_lifecycle(remediation_service_fixture):
    """
    Verifies that a developer can reject an action with a reason.
    """
    service, action_repo, _, event_repo = remediation_service_fixture
    org_id = uuid4()
    repo_id = uuid4()

    action = EngineeringAction(
        action_id="act-rel-102",
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=42,
        commit_sha="a1b2c3d4",
        action_type=ActionType.FIX_RELIABILITY.value,
        severity=ActionSeverity.MEDIUM.value,
        title="Add Circuit Breaker",
        description="Add Resilience4j circuit breaker around downstream client",
        reason="REL-004: Missing fault tolerance",
        evidence="new RestTemplate().getForObject()",
        affected_files=["src/main/java/com/forgeai/client/ApiClient.java"],
        status=ActionStatus.PROPOSED.value,
    )
    await action_repo.save_actions([action])

    updated = await service.reject_action(
        organization_id=org_id,
        action_id="act-rel-102",
        actor_id="developer_bob",
        reason="We already handle downstream errors at the gateway layer",
    )

    assert updated.status == ActionStatus.REJECTED.value

    events = await event_repo.get_events_for_action(org_id, "act-rel-102")
    assert len(events) == 1
    assert events[0].event_type == "REJECTED"
    assert events[0].actor_id == "developer_bob"


@pytest.mark.asyncio
async def test_cannot_apply_unapproved_action(remediation_service_fixture):
    """
    Verifies that applying a PROPOSED (unapproved) action raises HTTPException 400.
    """
    service, action_repo, patch_repo, _ = remediation_service_fixture
    org_id = uuid4()
    repo_id = uuid4()

    action = EngineeringAction(
        action_id="act-sec-103",
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=42,
        commit_sha="a1b2c3d4",
        action_type=ActionType.FIX_SECURITY.value,
        severity=ActionSeverity.CRITICAL.value,
        title="Rotate Insecure Key",
        description="Update symmetric key length",
        reason="SEC-003: Weak crypto",
        evidence="DES.getInstance()",
        affected_files=["src/key.py"],
        status=ActionStatus.PROPOSED.value,
    )
    await action_repo.save_actions([action])

    with pytest.raises(HTTPException) as exc_info:
        await service.apply_action(org_id, "act-sec-103", actor_id="developer_alice")
    assert exc_info.value.status_code == 400
    assert "Explicit developer approval required" in exc_info.value.detail


@pytest.mark.asyncio
async def test_advisory_mode_blocks_write_with_403(remediation_service_fixture):
    """
    Verifies that when GitHub App is in Advisory Mode (Mode A), attempting to apply
    an action is blocked with HTTP 403 and an informative message.
    """
    service, action_repo, patch_repo, _ = remediation_service_fixture
    org_id = uuid4()
    repo_id = uuid4()

    action = EngineeringAction(
        action_id="act-sec-104",
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=42,
        commit_sha="a1b2c3d4",
        action_type=ActionType.FIX_SECURITY.value,
        severity=ActionSeverity.HIGH.value,
        title="Remove AWS Key",
        description="Move to env variable",
        reason="SEC-001: Secret key in code",
        evidence="AWS_SECRET_KEY = '...'",
        affected_files=["src/config.py"],
        status=ActionStatus.APPROVED.value,
    )
    await action_repo.save_actions([action])

    patch = PatchProposal(
        action_id="act-sec-104",
        organization_id=org_id,
        repository_id=repo_id,
        target_file="src/config.py",
        target_symbol="AWS_KEY",
        original_context='KEY = "AKIAIOSFODNN7EXAMPLE"',
        proposed_replacement="KEY = os.getenv('KEY')",
        unified_diff="--- a/src/config.py\n+++ b/src/config.py\n@@ -1,1 +1,1 @@\n-KEY = 'secret'\n+KEY = os.getenv('KEY')",
        rationale="Load key from environment",
        source_finding="SEC-001",
        status=PatchStatus.PROPOSED.value,
    )
    await patch_repo.save_patch(patch)

    with pytest.raises(HTTPException) as exc_info:
        await service.apply_action(org_id, "act-sec-104", actor_id="developer_alice")
    assert exc_info.value.status_code == 403
    assert "Advisory Mode — GitHub write permissions are not enabled" in exc_info.value.detail


@pytest.mark.asyncio
async def test_tenant_isolation_on_action_operations(remediation_service_fixture):
    """
    Verifies that accessing or approving an action with a different organization ID
    fails with 404 (preventing cross-tenant leakage).
    """
    service, action_repo, _, _ = remediation_service_fixture
    org_a = uuid4()
    org_b = uuid4()
    repo_id = uuid4()

    action = EngineeringAction(
        action_id="act-sec-105",
        organization_id=org_a,
        repository_id=repo_id,
        pr_number=42,
        commit_sha="a1b2c3d4",
        action_type=ActionType.FIX_SECURITY.value,
        severity=ActionSeverity.HIGH.value,
        title="Sanitize Input",
        description="Sanitize user input",
        reason="SEC-005: XSS",
        evidence="innerHTML = userInput",
        affected_files=["src/input.py"],
        status=ActionStatus.PROPOSED.value,
    )
    await action_repo.save_actions([action])

    # Attempt to approve using org_b
    with pytest.raises(HTTPException) as exc_info:
        await service.approve_action(organization_id=org_b, action_id="act-sec-105")
    assert exc_info.value.status_code == 404
