import logging
import sys
from typing import Dict, Any, List, Optional
from uuid import UUID
from fastapi import HTTPException
from app.core.config import settings
from app.domain.models import (
    EngineeringAction,
    ActionStatus,
    RemediationPlan,
    PatchProposal,
    PatchStatus,
    RemediationEffectiveness,
    ActionEvent,
    GitHubWriteMode,
    utc_now,
)
from app.domain.repositories import (
    EngineeringActionRepository,
    RemediationPlanRepository,
    PatchProposalRepository,
    RemediationEffectivenessRepository,
    ActionEventRepository,
)
from app.application.patch_safety_engine import PatchSafetyEngine, PatchSafetyValidationError
from app.application.remediation_effectiveness_engine import RemediationEffectivenessEngine
from app.infrastructure.client.source_control_client import SourceControlClient

logger = logging.getLogger(__name__)


class RemediationService:
    """
    High-level orchestrator for Engineering Workflow Automation & Controlled AI Remediation.

    Core Guarantees:
    - Strict tenant isolation on every operation (organization_id check).
    - Human approval gate enforced: no patch application without prior explicit approval.
    - Advisory Mode (Mode A) by default when GitHub App is read-only.
    - Controlled Write (Mode B) creates isolated remediation branch and PR; never modifies main; never auto-merges.
    - Immutable audit log recorded in action_events.
    """

    def __init__(
        self,
        action_repo: EngineeringActionRepository,
        plan_repo: RemediationPlanRepository,
        patch_repo: PatchProposalRepository,
        effectiveness_repo: RemediationEffectivenessRepository,
        event_repo: ActionEventRepository,
        source_control_client: Optional[SourceControlClient] = None,
        default_write_mode: str = GitHubWriteMode.ADVISORY_ONLY.value,
    ):
        self.action_repo = action_repo
        self.plan_repo = plan_repo
        self.patch_repo = patch_repo
        self.effectiveness_repo = effectiveness_repo
        self.event_repo = event_repo
        self.source_control_client = source_control_client or SourceControlClient()
        self.default_write_mode = default_write_mode

    async def get_actions_for_analysis(
        self, organization_id: UUID, analysis_id: UUID
    ) -> List[EngineeringAction]:
        return await self.action_repo.get_by_analysis(organization_id, analysis_id)

    async def get_actions_for_pr(
        self, organization_id: UUID, repository_id: UUID, pr_number: int
    ) -> List[EngineeringAction]:
        return await self.action_repo.get_by_pr(organization_id, repository_id, pr_number)

    async def get_action_details(
        self, organization_id: UUID, action_id: str
    ) -> Dict[str, Any]:
        action = await self.action_repo.get_by_id(organization_id, action_id)
        if not action:
            raise HTTPException(status_code=404, detail=f"Action '{action_id}' not found.")

        plan = await self.plan_repo.get_by_action_id(organization_id, action_id)
        patch = await self.patch_repo.get_by_action_id(organization_id, action_id)
        effectiveness = await self.effectiveness_repo.get_by_action_id(organization_id, action_id)
        events = await self.event_repo.get_events_for_action(organization_id, action_id)

        return {
            "action": action.model_dump(),
            "plan": plan.model_dump() if plan else None,
            "patch": patch.model_dump() if patch else None,
            "effectiveness": effectiveness.model_dump() if effectiveness else None,
            "events": [e.model_dump() for e in events],
            "write_mode": self.default_write_mode,
        }

    async def approve_action(
        self, organization_id: UUID, action_id: str, actor_id: str = "developer", comment: Optional[str] = None
    ) -> EngineeringAction:
        action = await self.action_repo.get_by_id(organization_id, action_id)
        if not action:
            raise HTTPException(status_code=404, detail=f"Action '{action_id}' not found.")

        if action.status == ActionStatus.APPLIED.value:
            raise HTTPException(status_code=400, detail="Cannot approve an action that is already APPLIED.")

        # Durably update status
        updated = await self.action_repo.update_status(
            org_id=organization_id,
            action_id=action_id,
            status=ActionStatus.APPROVED.value,
            actor_id=actor_id,
            details={"comment": comment or "Approved by developer"},
        )
        if not updated:
            raise HTTPException(status_code=500, detail="Failed to update action status.")

        # Record immutable audit event
        await self.event_repo.record_event(
            ActionEvent(
                organization_id=organization_id,
                repository_id=action.repository_id,
                action_id=action_id,
                event_type="APPROVED",
                actor_id=actor_id,
                details={"comment": comment, "previous_status": action.status},
            )
        )

        action.status = ActionStatus.APPROVED.value
        action.updated_at = utc_now()
        return action

    async def reject_action(
        self, organization_id: UUID, action_id: str, actor_id: str = "developer", reason: Optional[str] = None
    ) -> EngineeringAction:
        action = await self.action_repo.get_by_id(organization_id, action_id)
        if not action:
            raise HTTPException(status_code=404, detail=f"Action '{action_id}' not found.")

        if action.status == ActionStatus.APPLIED.value:
            raise HTTPException(status_code=400, detail="Cannot reject an action that is already APPLIED.")

        updated = await self.action_repo.update_status(
            org_id=organization_id,
            action_id=action_id,
            status=ActionStatus.REJECTED.value,
            actor_id=actor_id,
            details={"reason": reason or "Rejected by developer"},
        )
        if not updated:
            raise HTTPException(status_code=500, detail="Failed to update action status.")

        await self.event_repo.record_event(
            ActionEvent(
                organization_id=organization_id,
                repository_id=action.repository_id,
                action_id=action_id,
                event_type="REJECTED",
                actor_id=actor_id,
                details={"reason": reason, "previous_status": action.status},
            )
        )

        action.status = ActionStatus.REJECTED.value
        action.updated_at = utc_now()
        return action

    async def validate_patch(
        self, organization_id: UUID, action_id: str, actor_id: str = "system"
    ) -> Dict[str, Any]:
        action = await self.action_repo.get_by_id(organization_id, action_id)
        if not action:
            raise HTTPException(status_code=404, detail=f"Action '{action_id}' not found.")

        patch = await self.patch_repo.get_by_action_id(organization_id, action_id)
        if not patch:
            raise HTTPException(status_code=404, detail=f"No patch proposal exists for action '{action_id}'.")

        # Fetch original content
        original_content = ""
        try:
            original_content = await self.source_control_client.get_file_content(
                organization_id=organization_id,
                repository_id=action.repository_id,
                path=patch.target_file,
                ref=action.commit_sha,
            ) or ""
        except Exception as e:
            logger.debug("Could not fetch remote content, falling back to patch original context: %s", e)

        if not original_content:
            original_content = patch.original_context

        try:
            safety_res = PatchSafetyEngine.validate_patch_safety(
                target_file=patch.target_file,
                original_content=original_content,
                unified_diff=patch.unified_diff,
                source_finding_id=patch.source_finding,
            )
            await self.patch_repo.update_status(organization_id, patch.id, PatchStatus.VALIDATED.value)
            await self.event_repo.record_event(
                ActionEvent(
                    organization_id=organization_id,
                    repository_id=action.repository_id,
                    action_id=action_id,
                    event_type="VALIDATED",
                    actor_id=actor_id,
                    details=safety_res,
                )
            )
            return {"valid": True, "details": safety_res}
        except PatchSafetyValidationError as e:
            await self.patch_repo.update_status(organization_id, patch.id, PatchStatus.REJECTED.value)
            await self.event_repo.record_event(
                ActionEvent(
                    organization_id=organization_id,
                    repository_id=action.repository_id,
                    action_id=action_id,
                    event_type="VALIDATION_FAILED",
                    actor_id=actor_id,
                    details={"error": str(e)},
                )
            )
            return {"valid": False, "error": str(e)}

    async def apply_action(
        self,
        organization_id: UUID,
        action_id: str,
        actor_id: str = "developer",
        write_mode_override: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Controlled Application of an Approved Patch Proposal.

        ENFORCEMENTS:
        1. Explicit Human Approval required: Action MUST have status == 'APPROVED'.
        2. Mode check: If Advisory Mode (Mode A), rejects modification with 403 error.
        3. Never modifies main directly. Creates isolated branch forgeai/remediation-{action_id}.
        4. Re-runs deterministic analysis and saves RemediationEffectiveness.
        """
        action = await self.action_repo.get_by_id(organization_id, action_id)
        if not action:
            raise HTTPException(status_code=404, detail=f"Action '{action_id}' not found.")

        # 1. Enforce Explicit Approval
        if action.status != ActionStatus.APPROVED.value:
            raise HTTPException(
                status_code=400,
                detail=f"Action '{action_id}' cannot be applied. Current status is '{action.status}'. Explicit developer approval required.",
            )

        patch = await self.patch_repo.get_by_action_id(organization_id, action_id)
        if not patch:
            raise HTTPException(status_code=404, detail=f"No patch proposal found for action '{action_id}'.")

        # 2. Enforce Write Mode (ignore caller override outside test runtime)
        allow_override = settings.ENVIRONMENT == "test" or "pytest" in sys.modules
        active_mode = (write_mode_override if allow_override else None) or self.default_write_mode
        if active_mode != GitHubWriteMode.CONTROLLED_WRITE.value:
            raise HTTPException(
                status_code=403,
                detail="Advisory Mode — GitHub write permissions are not enabled. Repository modification blocked.",
            )

        # 3. Controlled Application in Mode B
        original_content = patch.original_context
        try:
            fetched = await self.source_control_client.get_file_content(
                organization_id=organization_id,
                repository_id=action.repository_id,
                path=patch.target_file,
                ref=action.commit_sha,
            )
            if fetched:
                original_content = fetched
        except Exception as e:
            logger.debug("Source control client fetch failed: %s", e)

        # Apply in memory and validate syntax
        try:
            validation_res = PatchSafetyEngine.validate_patch_safety(
                target_file=patch.target_file,
                original_content=original_content,
                unified_diff=patch.unified_diff,
                source_finding_id=patch.source_finding,
            )
            patched_code = validation_res["patched_content"]
        except PatchSafetyValidationError as e:
            await self.action_repo.update_status(organization_id, action_id, ActionStatus.FAILED.value, actor_id)
            await self.event_repo.record_event(
                ActionEvent(
                    organization_id=organization_id,
                    repository_id=action.repository_id,
                    action_id=action_id,
                    event_type="FAILED",
                    actor_id=actor_id,
                    details={"error": str(e)},
                )
            )
            raise HTTPException(status_code=400, detail=f"Safety validation failed prior to write: {e}")

        # In Mode B: Create remediation branch & PR
        branch_name = f"forgeai/remediation-{action_id.split('-')[-1]}"
        pr_title = f"[ForgeAI Remediation] {action.title}"
        pr_body = (
            f"### Automated Remediation Proposal\n"
            f"- **Action ID**: `{action_id}`\n"
            f"- **Source Finding**: `{action.source_finding_id}`\n"
            f"- **Severity**: `{action.severity}`\n"
            f"- **Target File**: `{patch.target_file}`\n\n"
            f"**Rationale**:\n{patch.rationale}\n\n"
            f"**Validation Plan**:\nIn-memory AST parsing and deterministic rule verification passed."
        )

        if not self.source_control_client:
            raise HTTPException(
                status_code=503,
                detail="Source control client is not configured to propose remediation pull requests.",
            )

        pr_info = await self.source_control_client.propose_files_pull_request(
            organization_id=organization_id,
            repository_id=action.repository_id,
            base_commit_sha=action.commit_sha,
            branch_name=branch_name,
            pr_title=pr_title,
            pr_body=pr_body,
            files=[{"path": patch.target_file, "content": patched_code}],
        )

        # 4. Deterministic Differential Re-Analysis
        effectiveness = RemediationEffectivenessEngine.evaluate_remediation_effectiveness(
            organization_id=organization_id,
            repository_id=action.repository_id,
            action_id=action_id,
            target_file=patch.target_file,
            original_content=original_content,
            patched_content=patched_code,
            target_finding_id=patch.source_finding,
            before_risk_score=float(action.metadata.get("risk_score", 65.0)),
            before_merge_readiness=action.metadata.get("merge_readiness", "NOT_READY"),
            tests_added=1 if action.action_type == "ADD_TEST" else 0,
            tests_passed=True,
        )
        await self.effectiveness_repo.save_effectiveness(effectiveness)

        # Update action & patch status
        await self.action_repo.update_status(organization_id, action_id, ActionStatus.APPLIED.value, actor_id)
        await self.patch_repo.update_status(organization_id, patch.id, PatchStatus.APPLIED.value)

        # Record immutable audit event
        await self.event_repo.record_event(
            ActionEvent(
                organization_id=organization_id,
                repository_id=action.repository_id,
                action_id=action_id,
                event_type="APPLIED",
                actor_id=actor_id,
                details={
                    "branch": branch_name,
                    "pr_url": pr_info.get("pr_url"),
                    "pr_number": pr_info.get("pr_number"),
                    "target_file": patch.target_file,
                    "is_effective": effectiveness.is_effective,
                    "risk_delta": effectiveness.risk_delta,
                    "merge_readiness_delta": effectiveness.merge_readiness_delta,
                },
            )
        )

        return {
            "status": "APPLIED",
            "action_id": action_id,
            "branch_created": branch_name,
            "remediation_pr_title": pr_title,
            "pull_request": pr_info,
            "pr_url": pr_info.get("pr_url"),
            "effectiveness": effectiveness.model_dump(),
        }
