import hashlib
import logging
from typing import List, Dict, Any, Optional, Tuple
from uuid import UUID
from app.domain.models import (
    EngineeringAction,
    ActionType,
    ActionStatus,
    ActionSeverity,
    RemediationPlan,
    RemediationStep,
    PlanStepStatus,
    SecurityFinding,
    ReliabilityFinding,
    SecurityCategory,
    FindingSeverity,
    ContractCompatibilityStatus,
)

logger = logging.getLogger(__name__)


class ActionDerivationEngine:
    """
    Deterministic Action & Remediation Plan Derivation Engine.
    Transforms authoritative findings (Security, Reliability, Tests, Contracts, Review)
    into structured, reviewable, auditable EngineeringActions and RemediationPlans.

    ======================================================================
    CORE PRINCIPLE:
    - Actions are derived from deterministic evidence ONLY.
    - Zero actions generated without supporting evidence.
    - Deterministic action priority (CRITICAL, HIGH, MEDIUM, LOW) — NOT LLM decided.
    - Zero raw secrets in action evidence or recommendations.
    ======================================================================
    """

    @classmethod
    def derive_actions_and_plans(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        security_findings: List[SecurityFinding],
        reliability_findings: List[ReliabilityFinding],
        test_strategy_dict: Dict[str, Any],
        distributed_impact: Optional[Dict[str, Any]] = None,
        merge_readiness: Optional[Dict[str, Any]] = None,
        review_findings: Optional[List[Dict[str, Any]]] = None,
        changed_files: Optional[List[Dict[str, Any]]] = None,
        analysis_id: Optional[UUID] = None,
    ) -> Tuple[List[EngineeringAction], List[RemediationPlan]]:
        actions: List[EngineeringAction] = []
        plans: List[RemediationPlan] = []

        # ----------------------------------------------------------------------
        # 1. Derive Security Actions (FIX_SECURITY, UPDATE_CONFIGURATION)
        # ----------------------------------------------------------------------
        for f in security_findings:
            action, plan = cls._derive_security_action(
                organization_id=organization_id,
                repository_id=repository_id,
                pr_number=pr_number,
                commit_sha=commit_sha,
                finding=f,
                analysis_id=analysis_id,
            )
            actions.append(action)
            plans.append(plan)

        # ----------------------------------------------------------------------
        # 2. Derive Reliability Actions (FIX_RELIABILITY)
        # ----------------------------------------------------------------------
        for rf in reliability_findings:
            action, plan = cls._derive_reliability_action(
                organization_id=organization_id,
                repository_id=repository_id,
                pr_number=pr_number,
                commit_sha=commit_sha,
                finding=rf,
                analysis_id=analysis_id,
            )
            actions.append(action)
            plans.append(plan)

        # ----------------------------------------------------------------------
        # 3. Derive Test Actions (RUN_TEST, ADD_TEST)
        # ----------------------------------------------------------------------
        must_run_tests = test_strategy_dict.get("must_run", [])
        should_run_tests = test_strategy_dict.get("should_run", [])
        
        # If there are must_run tests identified, derive RUN_TEST action
        if must_run_tests:
            run_action, run_plan = cls._derive_run_tests_action(
                organization_id=organization_id,
                repository_id=repository_id,
                pr_number=pr_number,
                commit_sha=commit_sha,
                must_run_tests=must_run_tests,
                analysis_id=analysis_id,
            )
            actions.append(run_action)
            plans.append(run_plan)

        # If merge readiness flagged missing test coverage or test gap
        if merge_readiness:
            required_tests = merge_readiness.get("required_tests", [])
            blocking_reasons = merge_readiness.get("blocking_reasons", [])
            for reason in blocking_reasons:
                if "test" in reason.lower() and "missing" in reason.lower():
                    add_test_action, add_test_plan = cls._derive_add_test_action(
                        organization_id=organization_id,
                        repository_id=repository_id,
                        pr_number=pr_number,
                        commit_sha=commit_sha,
                        reason=reason,
                        changed_files=changed_files or [],
                        analysis_id=analysis_id,
                    )
                    actions.append(add_test_action)
                    plans.append(add_test_plan)
                    break

        # ----------------------------------------------------------------------
        # 4. Derive Cross-Repository Contract Actions (UPDATE_API_CONTRACT)
        # ----------------------------------------------------------------------
        if distributed_impact:
            contract_status = distributed_impact.get("contract_status", "COMPATIBLE")
            downstream_impacts = distributed_impact.get("downstream_impacts", [])
            if contract_status in (ContractCompatibilityStatus.BREAKING.value, ContractCompatibilityStatus.POTENTIALLY_BREAKING.value):
                contract_action, contract_plan = cls._derive_contract_action(
                    organization_id=organization_id,
                    repository_id=repository_id,
                    pr_number=pr_number,
                    commit_sha=commit_sha,
                    contract_status=contract_status,
                    downstream_impacts=downstream_impacts,
                    analysis_id=analysis_id,
                )
                actions.append(contract_action)
                plans.append(contract_plan)

        # ----------------------------------------------------------------------
        # 5. Fallback NO_ACTION if codebase is perfectly clean and ready
        # ----------------------------------------------------------------------
        if not actions:
            no_action = EngineeringAction(
                action_id=f"act-clean-{repository_id.hex[:8]}-{pr_number}",
                analysis_id=analysis_id,
                organization_id=organization_id,
                repository_id=repository_id,
                pr_number=pr_number,
                commit_sha=commit_sha,
                action_type=ActionType.NO_ACTION.value,
                title="All Quality and Security Checks Passed",
                description="Deterministic analysis verified no security vulnerabilities, reliability risks, or missing test requirements.",
                reason="Clean evaluation across security, reliability, test intelligence, and merge readiness.",
                severity=ActionSeverity.LOW.value,
                confidence="HIGH",
                evidence="Full deterministic evaluation passed with 0 blocking findings.",
                affected_files=[],
                affected_symbols=[],
                status=ActionStatus.PROPOSED.value,
                provenance={"source": "DeterministicAnalysisEngine"},
            )
            actions.append(no_action)

        return actions, plans

    @classmethod
    def _derive_security_action(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        finding: SecurityFinding,
        analysis_id: Optional[UUID],
    ) -> Tuple[EngineeringAction, RemediationPlan]:
        action_type = ActionType.FIX_SECURITY.value
        if finding.category == SecurityCategory.INSECURE_CONFIGURATION.value:
            action_type = ActionType.UPDATE_CONFIGURATION.value

        # Deterministic severity mapping
        severity = cls._map_severity(finding.severity, is_blocker=finding.is_blocker)

        # Deterministic stable action_id
        action_seed = f"sec:{organization_id}:{repository_id}:{pr_number}:{finding.rule_id}:{finding.file_path}:{finding.line_number}:{commit_sha}"
        action_id = f"act-sec-{hashlib.sha256(action_seed.encode()).hexdigest()[:12]}"

        # Grounded description and reason citing finding
        title = f"Remediate Security Issue: {finding.title}"
        description = (
            f"Address {finding.severity} finding '{finding.rule_id}' in {finding.file_path}: {finding.description}"
        )
        reason = f"Security rule violation [{finding.rule_id}] threatens repository security posture: {finding.remediation}"

        action = EngineeringAction(
            action_id=action_id,
            analysis_id=analysis_id,
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=pr_number,
            commit_sha=commit_sha,
            action_type=action_type,
            title=title,
            description=description,
            reason=reason,
            severity=severity,
            confidence=finding.confidence,
            evidence=finding.redacted_evidence,
            affected_files=[finding.file_path],
            affected_symbols=[finding.metadata.get("symbol_name")] if finding.metadata.get("symbol_name") else [],
            status=ActionStatus.PROPOSED.value,
            source_finding_id=finding.rule_id,
            source_finding_type="SECURITY",
            provenance={
                "finding_id": str(finding.id),
                "rule_id": finding.rule_id,
                "category": finding.category,
                "is_blocker": finding.is_blocker,
                "line_number": finding.line_number,
            },
        )

        # Structured remediation plan
        target_symbol = finding.metadata.get("symbol_name") or finding.file_path
        if finding.category == SecurityCategory.SECRET_EXPOSURE.value:
            steps = [
                RemediationStep(
                    step_number=1,
                    target_file=finding.file_path,
                    target_symbol=target_symbol,
                    change_description="Remove exposed credential from source code and replace with environment variable reference.",
                    validation_strategy="Static secret scanner regex validation over file content.",
                    status=PlanStepStatus.PENDING.value,
                ),
                RemediationStep(
                    step_number=2,
                    target_file="Configuration / Secret Store",
                    target_symbol="Secret Management",
                    change_description="Revoke and rotate the exposed credential in the target identity/credential provider.",
                    validation_strategy="Verify new credential functions and previous credential is deactivated.",
                    status=PlanStepStatus.PENDING.value,
                ),
                RemediationStep(
                    step_number=3,
                    target_file=finding.file_path,
                    target_symbol=target_symbol,
                    change_description="Verify secret redaction check passes on commit diff.",
                    validation_strategy="Re-run SecurityIntelligenceEngine to verify zero findings persist.",
                    status=PlanStepStatus.PENDING.value,
                ),
            ]
            expected_impact = "Eliminates critical secret exposure vulnerability and prevents unauthorized access."
        else:
            steps = [
                RemediationStep(
                    step_number=1,
                    target_file=finding.file_path,
                    target_symbol=target_symbol,
                    change_description=f"Apply secure configuration: {finding.remediation}",
                    validation_strategy="AST parsing & static security rule validation.",
                    status=PlanStepStatus.PENDING.value,
                ),
                RemediationStep(
                    step_number=2,
                    target_file=finding.file_path,
                    target_symbol=target_symbol,
                    change_description="Run affected unit test suite to verify no functional regression.",
                    validation_strategy="Execute existing tests associated with target file.",
                    status=PlanStepStatus.PENDING.value,
                ),
            ]
            expected_impact = f"Resolves {finding.rule_id} security violation and reduces repository risk score."

        plan = RemediationPlan(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            title=f"Remediation Plan for {finding.rule_id}",
            summary=f"Plan to resolve {finding.title} in {finding.file_path}",
            steps=steps,
            is_multi_repo=False,
            coordinated_repos=[],
            expected_impact=expected_impact,
            provenance={"source_finding": finding.rule_id, "category": finding.category},
        )
        return action, plan

    @classmethod
    def _derive_reliability_action(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        finding: ReliabilityFinding,
        analysis_id: Optional[UUID],
    ) -> Tuple[EngineeringAction, RemediationPlan]:
        severity = cls._map_severity(finding.severity, is_blocker=finding.is_blocker)
        action_seed = f"rel:{organization_id}:{repository_id}:{pr_number}:{finding.rule_id}:{finding.file_path}:{finding.line_number}:{commit_sha}"
        action_id = f"act-rel-{hashlib.sha256(action_seed.encode()).hexdigest()[:12]}"

        title = f"Remediate Reliability Issue: {finding.title}"
        description = (
            f"Fix {finding.severity} reliability defect '{finding.rule_id}' in {finding.file_path}: {finding.description}"
        )
        reason = f"Reliability rule violation [{finding.rule_id}] degrades service resilience: {finding.remediation}"

        action = EngineeringAction(
            action_id=action_id,
            analysis_id=analysis_id,
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=pr_number,
            commit_sha=commit_sha,
            action_type=ActionType.FIX_RELIABILITY.value,
            title=title,
            description=description,
            reason=reason,
            severity=severity,
            confidence=finding.confidence,
            evidence=finding.evidence,
            affected_files=[finding.file_path],
            affected_symbols=[finding.metadata.get("symbol_name")] if finding.metadata.get("symbol_name") else [],
            status=ActionStatus.PROPOSED.value,
            source_finding_id=finding.rule_id,
            source_finding_type="RELIABILITY",
            provenance={
                "finding_id": str(finding.id),
                "rule_id": finding.rule_id,
                "category": finding.category,
                "is_blocker": finding.is_blocker,
                "line_number": finding.line_number,
            },
        )

        target_symbol = finding.metadata.get("symbol_name") or finding.file_path
        steps = [
            RemediationStep(
                step_number=1,
                target_file=finding.file_path,
                target_symbol=target_symbol,
                change_description=f"Harden code implementation: {finding.remediation}",
                validation_strategy="AST parsing & deterministic reliability rule validation.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=2,
                target_file=finding.file_path,
                target_symbol=target_symbol,
                change_description="Execute test suite to verify bounded resilience under error conditions.",
                validation_strategy="Run relevant tests with timeout/exception scenarios.",
                status=PlanStepStatus.PENDING.value,
            ),
        ]

        plan = RemediationPlan(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            title=f"Remediation Plan for {finding.rule_id}",
            summary=f"Plan to resolve {finding.title} in {finding.file_path}",
            steps=steps,
            is_multi_repo=False,
            coordinated_repos=[],
            expected_impact=f"Eliminates {finding.rule_id} and improves service reliability and uptime resilience.",
            provenance={"source_finding": finding.rule_id, "category": finding.category},
        )
        return action, plan

    @classmethod
    def _derive_run_tests_action(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        must_run_tests: List[Dict[str, Any]],
        analysis_id: Optional[UUID],
    ) -> Tuple[EngineeringAction, RemediationPlan]:
        test_names = [t.get("test_name") for t in must_run_tests[:5]]
        action_seed = f"run_tests:{organization_id}:{repository_id}:{pr_number}:{','.join(test_names)}:{commit_sha}"
        action_id = f"act-test-{hashlib.sha256(action_seed.encode()).hexdigest()[:12]}"

        affected_files = list({t.get("test_file") for t in must_run_tests if t.get("test_file")})
        title = f"Execute Required Test Suite ({len(must_run_tests)} Tests)"
        description = (
            f"Test Intelligence identified {len(must_run_tests)} MUST_RUN tests directly impacted by PR changes: "
            f"{', '.join(test_names)}"
        )
        evidence = f"AST and graph analysis linked changed symbols directly to test suites: {', '.join(test_names)}"

        action = EngineeringAction(
            action_id=action_id,
            analysis_id=analysis_id,
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=pr_number,
            commit_sha=commit_sha,
            action_type=ActionType.RUN_TEST.value,
            title=title,
            description=description,
            reason="Critical path tests must execute and pass before merge evaluation can be satisfied.",
            severity=ActionSeverity.HIGH.value,
            confidence="HIGH",
            evidence=evidence,
            affected_files=affected_files,
            affected_symbols=test_names,
            status=ActionStatus.PROPOSED.value,
            source_finding_id="TEST_INTEL_MUST_RUN",
            source_finding_type="TEST_INTELLIGENCE",
            provenance={"must_run_count": len(must_run_tests)},
        )

        steps = [
            RemediationStep(
                step_number=i + 1,
                target_file=t.get("test_file", "Test Suite"),
                target_symbol=t.get("test_name", "test"),
                change_description=f"Execute test '{t.get('test_name')}' via configured CI/testing pipeline.",
                validation_strategy="Assert 0 exit code and all assertions passing.",
                status=PlanStepStatus.PENDING.value,
            )
            for i, t in enumerate(must_run_tests[:3])
        ]

        plan = RemediationPlan(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            title="Execute Impacted Test Strategy",
            summary=f"Run prioritized test suites ({len(must_run_tests)} must-run tests)",
            steps=steps,
            is_multi_repo=False,
            coordinated_repos=[],
            expected_impact="Validates absence of functional regression on directly impacted components.",
            provenance={"test_count": len(must_run_tests)},
        )
        return action, plan

    @classmethod
    def _derive_add_test_action(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        reason: str,
        changed_files: List[Dict[str, Any]],
        analysis_id: Optional[UUID],
    ) -> Tuple[EngineeringAction, RemediationPlan]:
        first_file = (changed_files[0].get("filename") or changed_files[0].get("file_path") or "src/") if changed_files else "src/"
        action_seed = f"add_test:{organization_id}:{repository_id}:{pr_number}:{first_file}:{commit_sha}"
        action_id = f"act-addtest-{hashlib.sha256(action_seed.encode()).hexdigest()[:12]}"

        title = f"Add Missing Test Coverage for {first_file}"
        description = f"Merge readiness evaluation blocked: {reason}. Changes require dedicated test coverage."
        
        action = EngineeringAction(
            action_id=action_id,
            analysis_id=analysis_id,
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=pr_number,
            commit_sha=commit_sha,
            action_type=ActionType.ADD_TEST.value,
            title=title,
            description=description,
            reason="High risk or uncovered modified code requires unit/integration test before merge readiness is granted.",
            severity=ActionSeverity.HIGH.value,
            confidence="HIGH",
            evidence=f"Deterministic test requirement unsatisfied: {reason}",
            affected_files=[first_file],
            affected_symbols=[],
            status=ActionStatus.PROPOSED.value,
            source_finding_id="MERGE_READINESS_TEST_GAP",
            source_finding_type="TEST_GAP",
            provenance={"reason": reason},
        )

        steps = [
            RemediationStep(
                step_number=1,
                target_file=f"tests/test_{first_file.split('/')[-1]}",
                target_symbol="test_modified_behavior",
                change_description=f"Create targeted test suite covering modified logic in {first_file}.",
                validation_strategy="Run test runner and verify passing assertions.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=2,
                target_file=first_file,
                target_symbol="Target Component",
                change_description="Re-run merge readiness evaluation to confirm test requirement is fulfilled.",
                validation_strategy="MergeReadinessEngine re-evaluation.",
                status=PlanStepStatus.PENDING.value,
            ),
        ]

        plan = RemediationPlan(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            title=f"Add Test Coverage Plan: {first_file}",
            summary="Author and integrate missing unit/integration tests to satisfy merge policy",
            steps=steps,
            is_multi_repo=False,
            coordinated_repos=[],
            expected_impact="Satisfies merge readiness test requirement and guards against regressions.",
            provenance={"reason": reason},
        )
        return action, plan

    @classmethod
    def _derive_contract_action(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        contract_status: str,
        downstream_impacts: List[Dict[str, Any]],
        analysis_id: Optional[UUID],
    ) -> Tuple[EngineeringAction, RemediationPlan]:
        action_seed = f"contract:{organization_id}:{repository_id}:{pr_number}:{contract_status}:{commit_sha}"
        action_id = f"act-contract-{hashlib.sha256(action_seed.encode()).hexdigest()[:12]}"

        downstream_names = [imp.get("repository_name", "downstream") for imp in downstream_impacts]
        title = f"Update API Contract & Coordinate Downstream Consumers ({len(downstream_names)} Repos)"
        description = (
            f"Distributed change analysis classified PR as {contract_status}. "
            f"Downstream dependent repositories impacted: {', '.join(downstream_names) if downstream_names else 'Identified consumers'}."
        )

        action = EngineeringAction(
            action_id=action_id,
            analysis_id=analysis_id,
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=pr_number,
            commit_sha=commit_sha,
            action_type=ActionType.UPDATE_API_CONTRACT.value,
            title=title,
            description=description,
            reason="Breaking or potentially breaking API contract changes require coordinated remediation across repositories.",
            severity=ActionSeverity.CRITICAL.value if contract_status == ContractCompatibilityStatus.BREAKING.value else ActionSeverity.HIGH.value,
            confidence="HIGH",
            evidence=f"Cross-repo impact analysis identified downstream contract compatibility status: {contract_status}",
            affected_files=[],
            affected_symbols=[],
            status=ActionStatus.PROPOSED.value,
            source_finding_id=f"CROSS_REPO_CONTRACT_{contract_status}",
            source_finding_type="DISTRIBUTED_CONTRACT",
            provenance={"downstream_count": len(downstream_impacts), "contract_status": contract_status},
        )

        # Multi-Repository Coordinated Remediation Plan (Section 17 & 18 of prompt)
        steps = [
            RemediationStep(
                step_number=1,
                target_file="API Specification / Schema",
                target_symbol="Exported Contract",
                change_description="Update source repository API and verify backward compatibility / versioning.",
                validation_strategy="Run source CI and OpenAPI / schema validation.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=2,
                target_file="Consumer Client / Dependency",
                target_symbol="Client Adapter",
                change_description=f"Update consumer clients in downstream repositories ({', '.join(downstream_names)}).",
                validation_strategy="Compile and build downstream clients.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=3,
                target_file="Integration Test Suite",
                target_symbol="Contract Tests",
                change_description="Update and execute downstream integration and contract tests.",
                validation_strategy="Assert contract compliance in downstream test suite.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=4,
                target_file="CI Workflows",
                target_symbol="Source CI Pipeline",
                change_description="Run source repository CI workflows.",
                validation_strategy="Verify green CI conclusion on source branch.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=5,
                target_file="Downstream CI Workflows",
                target_symbol="Downstream CI Pipeline",
                change_description="Run downstream repositories CI workflows.",
                validation_strategy="Verify green CI conclusion on downstream consumer branch.",
                status=PlanStepStatus.PENDING.value,
            ),
            RemediationStep(
                step_number=6,
                target_file="Distributed Merge Readiness",
                target_symbol="Cross-Repo Gate",
                change_description="Recalculate distributed merge readiness across all coordinated repositories.",
                validation_strategy="Confirm 0 downstream breaking contract blockers.",
                status=PlanStepStatus.PENDING.value,
            ),
        ]

        plan = RemediationPlan(
            action_id=action_id,
            organization_id=organization_id,
            repository_id=repository_id,
            title="Coordinated Multi-Repository Remediation Plan",
            summary=f"Coordinated 6-step plan to resolve {contract_status} contract impact across {len(downstream_names)} repositories",
            steps=steps,
            is_multi_repo=True,
            coordinated_repos=downstream_names,
            expected_impact="Prevents downstream runtime contract breakages across distributed microservices.",
            provenance={"contract_status": contract_status, "downstream_repos": downstream_names},
        )
        return action, plan

    @classmethod
    def _map_severity(cls, finding_severity: str, is_blocker: bool = False) -> str:
        if is_blocker or finding_severity == FindingSeverity.CRITICAL.value:
            return ActionSeverity.CRITICAL.value
        elif finding_severity == FindingSeverity.HIGH.value:
            return ActionSeverity.HIGH.value
        elif finding_severity == FindingSeverity.MEDIUM.value:
            return ActionSeverity.MEDIUM.value
        return ActionSeverity.LOW.value
