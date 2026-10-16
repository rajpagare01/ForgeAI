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

    # H. Cross-Repository & Distributed Dependency Policy (v2)
    require_distributed_dependency_health: bool = True
    block_on_breaking_contract_with_dependents: bool = True
    block_on_downstream_required_test_failure: bool = True
    block_on_downstream_ci_failure: bool = True
    warn_on_potentially_breaking_contract: bool = True
    warn_on_downstream_ci_missing: bool = True
    allow_low_confidence_dependencies_without_blocking: bool = True

    # I. Score Penalty Weights (Readiness = 100 - sum(penalties))
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
    breaking_contract_penalty: float = 35.0
    potentially_breaking_contract_penalty: float = 15.0
    downstream_ci_failure_penalty: float = 30.0
    downstream_test_failure_penalty: float = 30.0

    # J. Security & Reliability Policy (v3)
    block_on_critical_security: bool = True
    block_on_high_security: bool = True
    warn_on_medium_security: bool = True
    allow_low_confidence_security_without_blocking: bool = True
    block_on_critical_reliability: bool = True
    warn_on_high_reliability: bool = True
    warn_on_reliability_evidence_unavailable: bool = False
    block_on_reliability_evidence_unavailable: bool = False
    security_blockers_block_merge: bool = True
    reliability_blockers_block_merge: bool = True
    security_critical_penalty: float = 40.0
    security_high_penalty: float = 25.0
    reliability_critical_penalty: float = 35.0
    reliability_high_penalty: float = 20.0

    @property
    def version(self) -> str:
        return self.policy_version


def get_default_merge_policy() -> MergePolicy:
    """Returns the standard production merge policy (v3)."""
    return get_merge_policy_v3()


def get_merge_policy_v1() -> MergePolicy:
    """Returns the repository-local merge policy v1."""
    return MergePolicy(
        policy_version="merge-policy-v1",
        name="ForgeAI Local Strict Policy v1",
        description="Standard deterministic merge gate ensuring repo-local synchronization, test execution, CI health, and security integrity.",
        require_distributed_dependency_health=False,
        block_on_breaking_contract_with_dependents=False,
        block_on_downstream_required_test_failure=False,
        block_on_downstream_ci_failure=False,
    )


def get_merge_policy_v2() -> MergePolicy:
    """Returns the distributed cross-repository merge policy v2."""
    return MergePolicy(
        policy_version="merge-policy-v2",
        name="ForgeAI Distributed Cross-Repository Policy v2",
        description="Advanced deterministic merge gate evaluating repo-local gates plus cross-repository dependency contracts, downstream CI health, and distributed test impact.",
        require_distributed_dependency_health=True,
        block_on_breaking_contract_with_dependents=True,
        block_on_downstream_required_test_failure=True,
        block_on_downstream_ci_failure=True,
        warn_on_potentially_breaking_contract=True,
    )


def get_merge_policy_v3() -> MergePolicy:
    """Returns the full Security, Reliability & Compliance intelligence merge policy v3."""
    return MergePolicy(
        policy_version="merge-policy-v3",
        name="ForgeAI Security, Reliability & Compliance Policy v3",
        description="Enterprise merge policy evaluating local gates, cross-repository contracts, deterministic security vulnerabilities, and reliability failure risks.",
        require_distributed_dependency_health=True,
        block_on_breaking_contract_with_dependents=True,
        block_on_downstream_required_test_failure=True,
        block_on_downstream_ci_failure=True,
        warn_on_potentially_breaking_contract=True,
        block_on_critical_security=True,
        block_on_high_security=True,
        warn_on_medium_security=True,
        allow_low_confidence_security_without_blocking=True,
        block_on_critical_reliability=True,
        warn_on_high_reliability=True,
    )


