from typing import List, Dict, Any, Optional
from pydantic import BaseModel, Field


class MergePolicy(BaseModel):
    """
    Formal, configurable merge policy for ForgeAI.
    Governs deterministic merge-readiness evaluations across synchronization,
    risk, test intelligence, CI health, security, architecture, and evidence completeness.
    """
    policy_version: str = "merge-policy-v1"
    name: str = "Default ForgeAI Strict Policy"
    description: str = "Standard deterministic merge gate ensuring synchronization, test execution, CI health, and security integrity."

    # A. Repository Synchronization Policy
    require_repository_synced: bool = True
    require_pr_head_indexed: bool = True

    # B. Risk Thresholds
    max_acceptable_risk_score: float = 80.0
    critical_risk_blocks_merge: bool = True
    high_risk_requires_warning: bool = True

    # C. Test Validation Policy
    require_all_required_tests_pass: bool = True
    block_on_unexecuted_required_tests: bool = True
    require_must_run_tests_coverage: bool = True

    # D. CI Health Policy
    require_ci_workflow_success: bool = True
    block_on_ci_failure: bool = True
    block_on_ci_cancelled: bool = True
    block_on_ci_running: bool = True  # Running CI yields BLOCKED_BY_MISSING_EVIDENCE or NOT_READY

    # E. Evidence Completeness
    require_authoritative_diff: bool = True
    require_provenance: bool = True
    allow_missing_runtime_coverage_with_warning: bool = True

    # F. Security Policy
    block_on_security_blocker: bool = True
    warn_on_security_warning: bool = True
    block_on_security_evidence_unavailable: bool = False  # If True, blocks. If False, issues warning and sets status truthfully.

    # G. Architecture Policy
    warn_on_multi_module_blast_radius: bool = True
    warn_on_high_graph_depth: bool = True
    max_acceptable_graph_depth: int = 3
    warn_on_large_deletion_scope: bool = True
    large_deletion_threshold_lines: int = 200

    # H. Score Penalty Weights (Readiness = 100 - sum(penalties))
    risk_penalty_weight: float = 0.3  # risk_score * 0.3
    failed_check_penalty: float = 25.0
    missing_required_test_penalty: float = 20.0
    failed_required_test_penalty: float = 35.0
    ci_failure_penalty: float = 35.0
    ci_running_penalty: float = 15.0
    ci_missing_penalty: float = 20.0
    sync_gap_penalty: float = 30.0
    critical_finding_penalty: float = 25.0
    high_finding_penalty: float = 15.0
    security_warning_penalty: float = 15.0
    security_blocker_penalty: float = 50.0
    evidence_gap_penalty: float = 10.0


def get_default_merge_policy() -> MergePolicy:
    """Returns the standard production merge policy v1."""
    return MergePolicy()
