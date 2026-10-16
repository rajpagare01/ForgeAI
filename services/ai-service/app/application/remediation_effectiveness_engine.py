import logging
from typing import Dict, Any, List, Optional
from uuid import UUID
from app.domain.models import RemediationEffectiveness, MergeDecision
from app.application.patch_safety_engine import PatchSafetyEngine
from app.application.security_intelligence_engine import SecurityIntelligenceEngine
from app.application.reliability_intelligence_engine import ReliabilityIntelligenceEngine
from app.application.unified_risk_engine import UnifiedRiskEngine

logger = logging.getLogger(__name__)


class RemediationEffectivenessEngine:
    """
    Measures actual remediation effectiveness via deterministic before/after differential analysis.

    NON-NEGOTIABLE:
    - Zero claims of 'fixed' based on LLM assertion.
    - True effectiveness is granted ONLY when deterministic re-analysis confirms finding removal,
      zero new findings introduced, and risk reduction / merge readiness improvement.
    """

    @classmethod
    def evaluate_remediation_effectiveness(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        action_id: str,
        target_file: str,
        original_content: str,
        patched_content: str,
        target_finding_id: str,
        before_risk_score: float,
        before_merge_readiness: str,
        tests_added: int = 0,
        tests_passed: bool = True,
    ) -> RemediationEffectiveness:
        """
        Executes before/after differential analysis on patched code.
        """
        # 1. Deterministic Security Rules Scan
        sec_before = SecurityIntelligenceEngine.scan_content_for_security_rules(original_content, target_file)
        sec_after = SecurityIntelligenceEngine.scan_content_for_security_rules(patched_content, target_file)

        # 2. Deterministic Reliability Rules Scan
        rel_before = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(original_content, target_file)
        rel_after = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(patched_content, target_file)

        rules_before = {f.rule_id for f in sec_before + rel_before}
        rules_after = {f.rule_id for f in sec_after + rel_after}

        findings_fixed = list(rules_before - rules_after)
        findings_persisted = list(rules_before & rules_after)
        new_findings_introduced = list(rules_after - rules_before)

        # 3. Deterministic Post-Patch Risk Calculation
        # Base recalculation: remove points contributed by fixed findings
        pts_reduced = 0.0
        for f in sec_before:
            if f.rule_id in findings_fixed:
                pts_reduced += 25.0 if f.severity in ("CRITICAL", "HIGH") else 10.0
        for f in rel_before:
            if f.rule_id in findings_fixed:
                pts_reduced += 20.0 if f.severity in ("CRITICAL", "HIGH") else 10.0

        # Penalize for any new findings
        pts_added = 0.0
        for f in sec_after:
            if f.rule_id in new_findings_introduced:
                pts_added += 25.0
        for f in rel_after:
            if f.rule_id in new_findings_introduced:
                pts_added += 20.0

        after_risk_score = max(0.0, min(100.0, before_risk_score - pts_reduced + pts_added))
        risk_delta = round(before_risk_score - after_risk_score, 1)

        # 4. Merge Readiness Differential Evaluation
        # If all blocking findings removed and tests passed, status improves
        has_blocker_remaining = any(f.is_blocker for f in sec_after)
        after_merge_readiness = before_merge_readiness

        if before_merge_readiness == MergeDecision.NOT_READY.value:
            if not has_blocker_remaining and not new_findings_introduced and tests_passed:
                if after_risk_score < 40.0:
                    after_merge_readiness = MergeDecision.READY.value
                else:
                    after_merge_readiness = MergeDecision.READY_WITH_WARNINGS.value
        elif before_merge_readiness == MergeDecision.READY_WITH_WARNINGS.value:
            if not rules_after and after_risk_score < 25.0 and tests_passed:
                after_merge_readiness = MergeDecision.READY.value

        merge_readiness_delta = f"{before_merge_readiness} -> {after_merge_readiness}"

        # 5. Truthful Effectiveness Boolean
        is_effective = (
            (target_finding_id in findings_fixed or not rules_after)
            and len(new_findings_introduced) == 0
            and after_risk_score <= before_risk_score
            and tests_passed
        )

        summary = (
            f"Remediation effectiveness evaluated: {'SUCCESS' if is_effective else 'PARTIAL/FAILED'}. "
            f"Risk score changed from {before_risk_score:.1f} to {after_risk_score:.1f} (delta: -{risk_delta:.1f}). "
            f"Findings fixed: {len(findings_fixed)}, persisted: {len(findings_persisted)}, "
            f"new: {len(new_findings_introduced)}. Merge readiness: {merge_readiness_delta}."
        )

        return RemediationEffectiveness(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            before_risk_score=before_risk_score,
            after_risk_score=after_risk_score,
            risk_delta=risk_delta,
            before_merge_readiness=before_merge_readiness,
            after_merge_readiness=after_merge_readiness,
            merge_readiness_delta=merge_readiness_delta,
            findings_fixed=findings_fixed,
            findings_persisted=findings_persisted,
            new_findings_introduced=new_findings_introduced,
            tests_added=tests_added,
            tests_passed=tests_passed,
            is_effective=is_effective,
            remediation_summary=summary,
            metadata={
                "target_finding_id": target_finding_id,
                "points_reduced": pts_reduced,
                "points_added": pts_added,
            },
        )
