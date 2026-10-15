import logging
from typing import List, Dict, Any, Optional, Tuple, Set
from uuid import UUID

from app.domain.models import (
    MergeDecision,
    SecurityStatus,
    FindingSeverity,
    TestMergeRequirement,
    RequiredCheck,
    MergeReadiness,
)
from app.application.merge_policy import MergePolicy, get_default_merge_policy

logger = logging.getLogger(__name__)


class MergeReadinessEngine:
    """
    Deterministic Merge Readiness Engine for ForgeAI.

    Evaluates PR mergeability based strictly on deterministic evidence:
      1. Repository synchronization & PR head indexing
      2. Deterministic risk score and level
      3. Merge-required test validation & execution
      4. Actual CI workflow results & test execution
      5. Security posture & evidence availability
      6. Architectural blast radius & boundary crossings
      7. Provenance completeness & evidence availability
      8. Structured code review findings

    Produces one of four categorical decisions:
      - READY: All required checks and tests passed with 0 blocking issues or warnings.
      - READY_WITH_WARNINGS: All required gates passed, but advisory warnings exist.
      - NOT_READY: One or more required checks, CI runs, or required tests failed.
      - BLOCKED_BY_MISSING_EVIDENCE: Required test or CI evidence is missing, unexecuted, or running.
    """

    @classmethod
    def evaluate_merge_readiness(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        pr_metadata: Dict[str, Any],
        sync_status: Optional[Dict[str, Any]],
        deterministic_risk_score: float,
        deterministic_risk_level: str,
        risk_factors: List[Dict[str, Any]],
        impact_metadata: Dict[str, Any],
        test_strategy: Dict[str, Any],
        actual_ci_results: Optional[Dict[str, Any]],
        review_findings: Optional[List[Dict[str, Any]]] = None,
        security_findings: Optional[List[Dict[str, Any]]] = None,
        provenance_records: Optional[List[Dict[str, Any]]] = None,
        policy: Optional[MergePolicy] = None,
    ) -> MergeReadiness:
        """
        Executes deterministic merge-readiness evaluation.
        """
        policy = policy or get_default_merge_policy()
        review_findings = review_findings or []
        security_findings = security_findings or []
        provenance_records = provenance_records or []

        blocking_reasons: List[str] = []
        warnings: List[str] = []
        required_actions: List[str] = []
        evidence_chain: List[Dict[str, Any]] = []

        all_checks: List[RequiredCheck] = []

        # ----------------------------------------------------------------------
        # 1. Repository Synchronization Check
        # ----------------------------------------------------------------------
        sync_state = (sync_status.get("status") if sync_status else "UNKNOWN") or "UNKNOWN"
        sync_head = sync_status.get("last_commit_sha") if sync_status else None
        is_synced = sync_state.upper() in ("SYNCED", "SUCCESS", "COMPLETED")
        head_matches = (sync_head is None) or (sync_head.lower() == commit_sha.lower())

        if not is_synced:
            sync_check_status = "FAILED"
            sync_conf = "HIGH"
            sync_ev = f"Repository sync status is '{sync_state}' (expected 'SYNCED')."
            blocking_reasons.append(f"Repository synchronization is not complete (current state: {sync_state}).")
            required_actions.append("Trigger repository synchronization before merging.")
        elif not head_matches:
            sync_check_status = "WARNING"
            sync_conf = "MEDIUM"
            sync_ev = f"Indexed commit ({sync_head[:7] if sync_head else 'none'}) differs from PR head ({commit_sha[:7]})."
            warnings.append("PR head commit differs from repository indexed head; incremental reconciliation advised.")
        else:
            sync_check_status = "PASSED"
            sync_conf = "HIGH"
            sync_ev = f"Repository is synchronized at commit {commit_sha[:7]}."

        all_checks.append(RequiredCheck(
            name="repository_synchronization",
            source="SYNCHRONIZATION",
            status=sync_check_status,
            confidence=sync_conf,
            evidence=sync_ev,
        ))
        evidence_chain.append({
            "dimension": "SYNCHRONIZATION",
            "check": "repository_synchronization",
            "status": sync_check_status,
            "evidence": sync_ev,
        })

        # ----------------------------------------------------------------------
        # 2. Deterministic Risk Evaluation Check
        # ----------------------------------------------------------------------
        risk_lvl = (deterministic_risk_level or "LOW").upper()
        if risk_lvl == "CRITICAL" and policy.critical_risk_blocks_merge:
            risk_check_status = "FAILED"
            risk_conf = "HIGH"
            risk_ev = f"Deterministic risk level is CRITICAL with score {deterministic_risk_score:.1f} (exceeds threshold {policy.max_acceptable_risk_score:.1f})."
            blocking_reasons.append(f"PR risk score ({deterministic_risk_score:.1f}) is CRITICAL, exceeding acceptable threshold.")
            required_actions.append("Address critical risk factors and obtain senior engineering sign-off.")
        elif deterministic_risk_score > policy.max_acceptable_risk_score:
            risk_check_status = "FAILED"
            risk_conf = "HIGH"
            risk_ev = f"Deterministic risk score {deterministic_risk_score:.1f} exceeds threshold {policy.max_acceptable_risk_score:.1f}."
            blocking_reasons.append(f"PR risk score {deterministic_risk_score:.1f} exceeds maximum allowed policy threshold ({policy.max_acceptable_risk_score:.1f}).")
            required_actions.append("Reduce change scope or split PR to lower deterministic risk.")
        elif risk_lvl in ("HIGH", "MEDIUM"):
            risk_check_status = "PASSED" if risk_lvl == "MEDIUM" else "WARNING"
            risk_conf = "HIGH"
            risk_ev = f"Deterministic risk score {deterministic_risk_score:.1f} ({risk_lvl})."
            if risk_lvl == "HIGH" and policy.high_risk_requires_warning:
                warnings.append(f"High risk change detected (score: {deterministic_risk_score:.1f}). Enhanced review recommended.")
        else:
            risk_check_status = "PASSED"
            risk_conf = "HIGH"
            risk_ev = f"Deterministic risk score {deterministic_risk_score:.1f} (LOW)."

        all_checks.append(RequiredCheck(
            name="deterministic_risk_evaluation",
            source="RISK_ENGINE",
            status=risk_check_status,
            confidence=risk_conf,
            evidence=risk_ev,
        ))
        evidence_chain.append({
            "dimension": "RISK_ENGINE",
            "check": "deterministic_risk_evaluation",
            "status": risk_check_status,
            "score": deterministic_risk_score,
            "level": risk_lvl,
            "evidence": risk_ev,
        })

        # ----------------------------------------------------------------------
        # 3. CI Workflow Health Check
        # ----------------------------------------------------------------------
        ci_status = "NOT_FOUND"
        ci_conclusion = "NOT_FOUND"
        ci_jobs: List[Dict[str, Any]] = []
        ci_test_cases: List[Dict[str, Any]] = []
        workflow_id = None

        if actual_ci_results and isinstance(actual_ci_results, dict):
            ci_status = (actual_ci_results.get("status") or "NOT_FOUND").upper()
            ci_conclusion = (actual_ci_results.get("conclusion") or actual_ci_results.get("status") or "NOT_FOUND").upper()
            ci_jobs = actual_ci_results.get("jobs", [])
            ci_test_cases = actual_ci_results.get("executed_tests", []) or actual_ci_results.get("test_cases", [])
            workflow_id = actual_ci_results.get("workflow_run_id") or actual_ci_results.get("id")

        ci_failed_jobs = [j.get("name") for j in ci_jobs if (j.get("conclusion") or j.get("status") or "").upper() in ("FAILURE", "FAILED")]
        
        if ci_conclusion in ("SUCCESS", "COMPLETED", "PASSED") and not ci_failed_jobs:
            ci_check_status = "PASSED"
            ci_conf = "HIGH"
            ci_ev = f"CI workflow run #{workflow_id or 'latest'} completed successfully."
        elif ci_conclusion in ("FAILURE", "FAILED") or ci_failed_jobs:
            ci_check_status = "FAILED"
            ci_conf = "HIGH"
            failed_jobs_str = ", ".join(ci_failed_jobs) if ci_failed_jobs else "failed jobs detected"
            ci_ev = f"CI workflow run #{workflow_id or 'latest'} failed ({failed_jobs_str})."
            blocking_reasons.append(f"CI workflow failed: {failed_jobs_str}.")
            required_actions.append("Fix failing CI workflow steps and re-run pipeline.")
        elif ci_status in ("IN_PROGRESS", "QUEUED", "RUNNING"):
            ci_check_status = "MISSING"
            ci_conf = "HIGH"
            ci_ev = f"CI workflow run #{workflow_id or 'latest'} is currently running ({ci_status})."
            blocking_reasons.append("CI workflow run is still in progress.")
            required_actions.append("Wait for CI workflow execution to finish.")
        elif ci_conclusion in ("CANCELLED", "ABORTED"):
            ci_check_status = "FAILED"
            ci_conf = "HIGH"
            ci_ev = f"CI workflow run #{workflow_id or 'latest'} was cancelled."
            blocking_reasons.append("CI workflow run was cancelled before completion.")
            required_actions.append("Trigger a clean re-run of the CI workflow.")
        else:
            # CI results not found or unknown
            ci_check_status = "MISSING"
            ci_conf = "MEDIUM"
            ci_ev = "No CI workflow run or testing service result found for this commit."
            if policy.require_ci_workflow_success:
                blocking_reasons.append("No active CI workflow execution found for PR commit SHA.")
                required_actions.append("Trigger GitHub Actions or local CI pipeline on PR head.")

        all_checks.append(RequiredCheck(
            name="ci_workflow_health",
            source="CI",
            status=ci_check_status,
            confidence=ci_conf,
            evidence=ci_ev,
        ))
        evidence_chain.append({
            "dimension": "CI",
            "check": "ci_workflow_health",
            "status": ci_check_status,
            "workflow_id": workflow_id,
            "evidence": ci_ev,
        })

        # ----------------------------------------------------------------------
        # 4. Merge-Required Test Intelligence Validation
        # ----------------------------------------------------------------------
        # Extract all recommendations across buckets
        all_recs: List[Dict[str, Any]] = []
        for bucket_key in ("must_run", "should_run", "optional"):
            recs = test_strategy.get(bucket_key, [])
            if isinstance(recs, list):
                all_recs.extend(recs)

        # Identify TEST_REQUIRED tests
        required_tests: List[Dict[str, Any]] = []
        for r in all_recs:
            t_req = r.get("merge_requirement") or TestMergeRequirement.TEST_RECOMMENDED.value
            if t_req == TestMergeRequirement.TEST_REQUIRED.value:
                required_tests.append(r)

        # If no explicit TEST_REQUIRED tests were flagged, elevate must_run tests as required
        if not required_tests:
            must_run_list = test_strategy.get("must_run", [])
            for r in must_run_list:
                r_copy = dict(r)
                r_copy["merge_requirement"] = TestMergeRequirement.TEST_REQUIRED.value
                required_tests.append(r_copy)

        # Cross-reference with executed CI test cases
        executed_test_map = {}
        for tc in ci_test_cases:
            tc_name = (tc.get("name") or tc.get("test_name") or "").lower()
            tc_status = (tc.get("status") or "").upper()
            executed_test_map[tc_name] = tc_status

        failed_required_tests: List[str] = []
        missing_required_tests: List[str] = []
        passed_required_tests: List[str] = []

        for req in required_tests:
            req_name = (req.get("test_name") or "").lower()
            # Find matching executed test
            matched_status = None
            for tc_name, tc_status in executed_test_map.items():
                if req_name in tc_name or tc_name in req_name:
                    matched_status = tc_status
                    break

            if matched_status in ("FAILED", "FAILURE", "FAIL"):
                failed_required_tests.append(req.get("test_name"))
            elif matched_status in ("PASSED", "SUCCESS", "PASS"):
                passed_required_tests.append(req.get("test_name"))
            else:
                # Required test has not executed or no granular CI report
                missing_required_tests.append(req.get("test_name"))

        if failed_required_tests:
            test_check_status = "FAILED"
            test_conf = "HIGH"
            test_ev = f"{len(failed_required_tests)} merge-required test(s) failed in CI: {', '.join(failed_required_tests[:3])}."
            blocking_reasons.append(f"Required test failure: {', '.join(failed_required_tests[:3])}.")
            required_actions.append(f"Fix failing required tests: {', '.join(failed_required_tests)}.")
        elif missing_required_tests and ci_check_status == "PASSED":
            # CI passed at workflow level, but granular test evidence didn't explicitly confirm required test execution
            test_check_status = "WARNING"
            test_conf = "MEDIUM"
            test_ev = f"CI workflow succeeded, but granular execution report for {len(missing_required_tests)} required test(s) was not captured."
            warnings.append(f"Merge-required test execution not individually captured in CI summary ({', '.join(missing_required_tests[:2])}).")
        elif missing_required_tests:
            test_check_status = "MISSING"
            test_conf = "HIGH"
            test_ev = f"{len(missing_required_tests)} merge-required test(s) have not executed: {', '.join(missing_required_tests[:3])}."
            blocking_reasons.append(f"Required tests have not executed: {', '.join(missing_required_tests[:3])}.")
            required_actions.append("Execute merge-required test suite in CI pipeline.")
        else:
            test_check_status = "PASSED"
            test_conf = "HIGH"
            test_ev = f"All {len(required_tests)} merge-required test(s) passed successfully."

        all_checks.append(RequiredCheck(
            name="required_tests_validation",
            source="TEST_INTELLIGENCE",
            status=test_check_status,
            confidence=test_conf,
            evidence=test_ev,
        ))
        evidence_chain.append({
            "dimension": "TEST_INTELLIGENCE",
            "check": "required_tests_validation",
            "status": test_check_status,
            "required_count": len(required_tests),
            "passed": passed_required_tests,
            "failed": failed_required_tests,
            "missing": missing_required_tests,
            "evidence": test_ev,
        })

        # ----------------------------------------------------------------------
        # 5. Security Posture Evaluation
        # ----------------------------------------------------------------------
        sec_blockers = [f for f in security_findings if f.get("severity") in ("CRITICAL", "HIGH") or f.get("is_blocker")]
        sec_warnings = [f for f in security_findings if f.get("severity") in ("MEDIUM", "LOW")]

        if sec_blockers:
            sec_status = SecurityStatus.SECURITY_BLOCKER.value
            sec_check_status = "FAILED"
            sec_conf = "HIGH"
            sec_ev = f"Security blocker detected: {sec_blockers[0].get('title', 'Critical vulnerability')}."
            blocking_reasons.append(f"Security blocker detected: {sec_blockers[0].get('title', 'Vulnerability')}.")
            required_actions.append("Remediate identified security vulnerability before merge.")
        elif sec_warnings:
            sec_status = SecurityStatus.SECURITY_WARNING.value
            sec_check_status = "WARNING"
            sec_conf = "MEDIUM"
            sec_ev = f"Security warning: {sec_warnings[0].get('title', 'Potential security concern')}."
            warnings.append(f"Security advisory: {sec_warnings[0].get('title', 'Security concern')}.")
        elif not security_findings:
            # Truthful reporting when static security analysis is not configured in pipeline
            sec_status = SecurityStatus.SECURITY_EVIDENCE_UNAVAILABLE.value
            sec_check_status = "WARNING" if not policy.block_on_security_evidence_unavailable else "FAILED"
            sec_conf = "LOW"
            sec_ev = "Dedicated static security scanning evidence is unavailable for this repository."
            if policy.block_on_security_evidence_unavailable:
                blocking_reasons.append("Security scanning evidence is mandatory under current merge policy but unavailable.")
            else:
                warnings.append("Security analysis evidence unavailable; manual security review recommended.")
        else:
            sec_status = SecurityStatus.NO_SECURITY_FINDINGS.value
            sec_check_status = "PASSED"
            sec_conf = "HIGH"
            sec_ev = "Security review completed with zero identified concerns."

        all_checks.append(RequiredCheck(
            name="security_posture_evaluation",
            source="SECURITY_ANALYSIS",
            status=sec_check_status,
            confidence=sec_conf,
            evidence=sec_ev,
        ))
        evidence_chain.append({
            "dimension": "SECURITY_ANALYSIS",
            "check": "security_posture_evaluation",
            "status": sec_check_status,
            "security_status": sec_status,
            "evidence": sec_ev,
        })

        # ----------------------------------------------------------------------
        # 6. Architectural Blast Radius & Boundary Crossings
        # ----------------------------------------------------------------------
        max_depth = impact_metadata.get("max_depth", 0)
        affected_services = impact_metadata.get("affected_services", [])
        total_impacted_nodes = impact_metadata.get("total_impacted_nodes", 0)
        deletions = pr_metadata.get("deletions", 0)

        arch_warnings: List[str] = []
        if len(affected_services) > 1 and policy.warn_on_multi_module_blast_radius:
            arch_warnings.append(f"Cross-service boundary impact: touches {len(affected_services)} services ({', '.join(affected_services[:3])}).")
        if max_depth > policy.max_acceptable_graph_depth and policy.warn_on_high_graph_depth:
            arch_warnings.append(f"Deep AST blast radius: maximum dependency depth is {max_depth} (exceeds {policy.max_acceptable_graph_depth}).")
        if deletions > policy.large_deletion_threshold_lines and policy.warn_on_large_deletion_scope:
            arch_warnings.append(f"Large deletion scope: {deletions} lines deleted.")

        if arch_warnings:
            arch_check_status = "WARNING"
            arch_conf = "HIGH"
            arch_ev = "; ".join(arch_warnings)
            warnings.extend(arch_warnings)
        else:
            arch_check_status = "PASSED"
            arch_conf = "HIGH"
            arch_ev = f"Architectural blast radius is contained (depth {max_depth}, {total_impacted_nodes} impacted nodes)."

        all_checks.append(RequiredCheck(
            name="architectural_blast_radius",
            source="RISK_ENGINE",
            status=arch_check_status,
            confidence=arch_conf,
            evidence=arch_ev,
        ))
        evidence_chain.append({
            "dimension": "ARCHITECTURE",
            "check": "architectural_blast_radius",
            "status": arch_check_status,
            "evidence": arch_ev,
        })

        # ----------------------------------------------------------------------
        # 7. Provenance Integrity Check
        # ----------------------------------------------------------------------
        has_authoritative_diff = bool(pr_metadata.get("diff") or pr_metadata.get("changed_files"))
        has_provenance = len(provenance_records) > 0 or len(evidence_chain) > 0

        if not has_authoritative_diff and policy.require_authoritative_diff:
            prov_status = "FAILED"
            prov_conf = "HIGH"
            prov_ev = "Authoritative PR diff evidence is missing."
            blocking_reasons.append("Authoritative PR diff could not be verified.")
            required_actions.append("Ensure PR diff is accessible from source control provider.")
        elif not has_provenance and policy.require_provenance:
            prov_status = "WARNING"
            prov_conf = "MEDIUM"
            prov_ev = "Provenance audit trail is incomplete."
            warnings.append("Incomplete provenance evidence chain for PR intelligence derivation.")
        else:
            prov_status = "PASSED"
            prov_conf = "HIGH"
            prov_ev = "Authoritative PR diff and multi-layer provenance chain verified."

        all_checks.append(RequiredCheck(
            name="provenance_integrity_check",
            source="PROVENANCE",
            status=prov_status,
            confidence=prov_conf,
            evidence=prov_ev,
        ))
        evidence_chain.append({
            "dimension": "PROVENANCE",
            "check": "provenance_integrity_check",
            "status": prov_status,
            "evidence": prov_ev,
        })

        # ----------------------------------------------------------------------
        # 8. Structured Review Finding Penalty & Check
        # ----------------------------------------------------------------------
        critical_findings = [f for f in review_findings if f.get("severity") == FindingSeverity.CRITICAL.value]
        high_findings = [f for f in review_findings if f.get("severity") == FindingSeverity.HIGH.value]

        if critical_findings:
            blocking_reasons.append(f"Critical review finding: {critical_findings[0].get('title', 'Critical defect identified')}.")
            required_actions.append("Resolve all CRITICAL review findings prior to merge.")

        # ----------------------------------------------------------------------
        # 9. Deterministic Readiness Score Formula
        # ----------------------------------------------------------------------
        # Base score starts at 100.0
        # Penalties:
        # - risk penalty = risk_score * policy.risk_penalty_weight
        # - failed check penalty
        # - missing required test penalty
        # - failed required test penalty
        # - CI failure penalty
        # - security blocker / warning penalty
        # - critical / high finding penalties
        completed_checks = [c.name for c in all_checks if c.status == "PASSED"]
        failed_checks = [c.name for c in all_checks if c.status == "FAILED"]
        missing_checks = [c.name for c in all_checks if c.status == "MISSING"]

        risk_pen = min(30.0, deterministic_risk_score * policy.risk_penalty_weight)
        failed_chk_pen = len(failed_checks) * policy.failed_check_penalty
        missing_chk_pen = len(missing_checks) * policy.missing_required_test_penalty
        failed_test_pen = len(failed_required_tests) * policy.failed_required_test_penalty
        ci_fail_pen = policy.ci_failure_penalty if ci_check_status == "FAILED" else 0.0
        sec_pen = (
            policy.security_blocker_penalty if sec_status == SecurityStatus.SECURITY_BLOCKER.value
            else (policy.security_warning_penalty if sec_status == SecurityStatus.SECURITY_WARNING.value else 0.0)
        )
        finding_pen = (len(critical_findings) * policy.critical_finding_penalty) + (len(high_findings) * policy.high_finding_penalty)
        sync_pen = policy.sync_gap_penalty if not is_synced else 0.0

        total_penalty = risk_pen + failed_chk_pen + missing_chk_pen + failed_test_pen + ci_fail_pen + sec_pen + finding_pen + sync_pen
        readiness_score = round(max(0.0, min(100.0, 100.0 - total_penalty)), 1)

        # ----------------------------------------------------------------------
        # 10. Categorical Decision Determination
        # ----------------------------------------------------------------------
        # Priority rules:
        # 1. Any FAILED required check or critical finding -> NOT_READY
        # 2. Any MISSING required check (e.g. CI running or missing, required test not run) -> BLOCKED_BY_MISSING_EVIDENCE
        # 3. All required checks PASSED (or non-blocking warning) but advisory warnings exist -> READY_WITH_WARNINGS
        # 4. All PASSED and 0 warnings -> READY
        if failed_checks or critical_findings:
            decision = MergeDecision.NOT_READY.value
            overall_confidence = "HIGH"
        elif missing_checks:
            decision = MergeDecision.BLOCKED_BY_MISSING_EVIDENCE.value
            overall_confidence = "HIGH"
        elif warnings:
            decision = MergeDecision.READY_WITH_WARNINGS.value
            overall_confidence = "HIGH" if test_check_status == "PASSED" else "MEDIUM"
        else:
            decision = MergeDecision.READY.value
            overall_confidence = "HIGH"

        logger.info(
            f"Merge readiness evaluated for PR #{pr_number} @ {commit_sha[:7]}: "
            f"decision={decision}, score={readiness_score}, failed={failed_checks}, missing={missing_checks}"
        )

        return MergeReadiness(
            decision=decision,
            readiness_score=readiness_score,
            confidence=overall_confidence,
            policy_version=policy.policy_version,
            blocking_reasons=blocking_reasons,
            warnings=warnings,
            required_actions=required_actions,
            required_checks=all_checks,
            completed_checks=completed_checks,
            failed_checks=failed_checks,
            missing_checks=missing_checks,
            required_tests=required_tests,
            security_status=sec_status,
            evidence=evidence_chain,
            github_mode="MODE_A_ADVISORY",
        )
