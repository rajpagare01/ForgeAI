import os
import logging
from typing import List, Dict, Any, Optional, Tuple, Set
from uuid import UUID
from pydantic import BaseModel, Field

from app.domain.models import (
    TestEvidenceType,
    TestPriorityBucket,
    TestRecommendation,
    TestValidationFeedback,
    TestMergeRequirement,
)

logger = logging.getLogger(__name__)


class TestStrategyResult(BaseModel):
    must_run: List[TestRecommendation] = Field(default_factory=list)
    should_run: List[TestRecommendation] = Field(default_factory=list)
    optional: List[TestRecommendation] = Field(default_factory=list)
    static_evidence: List[Dict[str, Any]] = Field(default_factory=list)
    runtime_evidence: List[Dict[str, Any]] = Field(default_factory=list)
    historical_failures: List[Dict[str, Any]] = Field(default_factory=list)
    coverage_summary: Dict[str, Any] = Field(default_factory=dict)
    overall_confidence: str = "MEDIUM"
    confidence_rationale: str = ""


class TestIntelligenceEngine:
    """
    Evidence-Backed Test Intelligence & Risk-Driven Validation Engine for ForgeAI.

    Distinguishes and preserves three explicit evidence layers:
      1. STATIC TEST EVIDENCE: Inferred from Tree-sitter AST, call graph edges, and structural path pairing.
      2. RUNTIME TEST EVIDENCE: Derived from CI workflow runs, executed test cases, and JaCoCo coverage (where available).
      3. HISTORICAL TEST EVIDENCE: Derived from persistent test failure history and co-change frequency.

    Produces deterministic, explainable test priorities categorized into MUST RUN, SHOULD RUN, and OPTIONAL.
    """

    @classmethod
    def evaluate_test_strategy(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        directly_changed_files: List[str],
        directly_changed_symbols: List[str],
        direct_impact_nodes: List[Dict[str, Any]],
        transitive_impact_nodes: List[Dict[str, Any]],
        retrieved_chunks: List[Dict[str, Any]],
        historical_commits: Optional[List[Dict[str, Any]]] = None,
        runtime_test_runs: Optional[List[Dict[str, Any]]] = None,
        historical_failed_tests: Optional[List[Dict[str, Any]]] = None,
        runtime_coverage_data: Optional[Dict[str, Any]] = None,
        deterministic_risk_score: float = 0.0,
    ) -> TestStrategyResult:
        """
        Executes deterministic test intelligence derivation and prioritization.
        """
        # ----------------------------------------------------------------------
        # 1. Evidence Partitioning
        # ----------------------------------------------------------------------
        static_evidence: List[Dict[str, Any]] = []
        runtime_evidence: List[Dict[str, Any]] = []
        historical_failures: List[Dict[str, Any]] = []

        # Candidate test accumulator keyed by test identifier (e.g. TestClass#testMethod or file)
        candidates: Dict[str, Dict[str, Any]] = {}

        def get_or_create_candidate(test_name: str, test_file: str) -> Dict[str, Any]:
            key = f"{test_name}::{test_file}"
            if key not in candidates:
                candidates[key] = {
                    "test_name": test_name,
                    "test_file": test_file,
                    "test_method": None,
                    "target_symbol": None,
                    "evidence_sources": set(),
                    "evidence_types": set(),
                    "evidence_path": [],
                    "reasons": [],
                    "ast_call": False,
                    "file_ref": False,
                    "path_pairing": False,
                    "runtime_coverage": False,
                    "runtime_ci": False,
                    "blast_radius_depth": None,
                    "historical_failure_count": 0,
                    "cochange_count": 0,
                }
            return candidates[key]

        # ----------------------------------------------------------------------
        # A. STATIC TEST EVIDENCE: AST Call Graph & Symbol References
        # ----------------------------------------------------------------------
        for node in direct_impact_nodes + transitive_impact_nodes:
            src = node.get("source_symbol", "")
            target = node.get("target_symbol", "")
            depth = int(node.get("depth", 1))
            edge_type = node.get("edge_type", "CALLS")

            if any(term in src.lower() for term in ("test", "spec", "integration")):
                cand = get_or_create_candidate(test_name=src, test_file="")
                cand["target_symbol"] = target
                cand["evidence_sources"].add("STATIC")
                cand["evidence_types"].add(TestEvidenceType.AST_CALL.value)
                cand["ast_call"] = True
                cand["blast_radius_depth"] = depth
                cand["evidence_path"].append(
                    f"Changed Symbol: {target} -> Graph Edge: {edge_type} (depth {depth}) -> Test Symbol: {src}"
                )
                cand["reasons"].append(
                    f"Direct AST call graph connection: {src} calls modified {target} via {edge_type} at depth {depth}"
                )
                static_evidence.append({
                    "evidence_type": TestEvidenceType.AST_CALL.value,
                    "test_symbol": src,
                    "target_symbol": target,
                    "depth": depth,
                    "edge_type": edge_type,
                })

        # Symbol references in retrieved test chunks
        for chunk in retrieved_chunks:
            fp = chunk.get("file_path", "")
            sym = chunk.get("symbol_name", "")
            if any(p in fp.lower() for p in ("/test/", "tests/", "test_")):
                test_name = sym or os.path.basename(fp)
                cand = get_or_create_candidate(test_name=test_name, test_file=fp)
                cand["evidence_sources"].add("STATIC")
                cand["evidence_types"].add(TestEvidenceType.FILE_REFERENCE.value)
                cand["file_ref"] = True
                cand["evidence_path"].append(
                    f"Modified Symbol Concept -> Code Reference in File: {fp} -> Test: {test_name}"
                )
                cand["reasons"].append(f"Static code chunk references modified symbol concept in {fp}")
                static_evidence.append({
                    "evidence_type": TestEvidenceType.FILE_REFERENCE.value,
                    "test_name": test_name,
                    "file_path": fp,
                    "symbol_name": sym,
                })

        # Structural / Path Name Pairing
        for f in directly_changed_files:
            norm = f.replace("\\", "/")
            base_name = os.path.basename(norm)
            name_no_ext, ext = os.path.splitext(base_name)

            if ext == ".java" and not name_no_ext.endswith("Test"):
                test_name = f"{name_no_ext}Test"
                candidate_test_file = norm.replace("/src/main/java/", "/src/test/java/").replace(base_name, f"{test_name}.java")
                cand = get_or_create_candidate(test_name=test_name, test_file=candidate_test_file)
                cand["evidence_sources"].add("STATIC")
                cand["evidence_types"].add(TestEvidenceType.PATH_PAIRING.value)
                cand["path_pairing"] = True
                cand["evidence_path"].append(f"Changed File: {norm} -> Structural Naming Pattern -> Test File: {candidate_test_file}")
                cand["reasons"].append(f"Structural unit test pairing for Java component {base_name}")
                static_evidence.append({
                    "evidence_type": TestEvidenceType.PATH_PAIRING.value,
                    "source_file": norm,
                    "candidate_test": candidate_test_file,
                })
            elif ext == ".py" and not name_no_ext.startswith("test_"):
                test_name = f"test_{name_no_ext}.py"
                candidate_test_file = f"tests/{test_name}"
                cand = get_or_create_candidate(test_name=test_name, test_file=candidate_test_file)
                cand["evidence_sources"].add("STATIC")
                cand["evidence_types"].add(TestEvidenceType.PATH_PAIRING.value)
                cand["path_pairing"] = True
                cand["evidence_path"].append(f"Changed File: {norm} -> Structural Naming Pattern -> Test File: {candidate_test_file}")
                cand["reasons"].append(f"Structural unit test pairing for Python component {base_name}")
                static_evidence.append({
                    "evidence_type": TestEvidenceType.PATH_PAIRING.value,
                    "source_file": norm,
                    "candidate_test": candidate_test_file,
                })

        # ----------------------------------------------------------------------
        # B. RUNTIME TEST EVIDENCE: CI Runs & JaCoCo Coverage
        # ----------------------------------------------------------------------
        # 1. Coverage Analysis (JaCoCo or repository test coverage)
        coverage_available = False
        coverage_mechanism = "STATIC_FALLBACK"

        if runtime_coverage_data and isinstance(runtime_coverage_data, dict):
            # Format expected: { "classes": { "com.forgeai.identity.UserService": ["UserServiceTest#testAuth", ...] } }
            classes_covered = runtime_coverage_data.get("classes", {})
            if classes_covered:
                coverage_available = True
                coverage_mechanism = runtime_coverage_data.get("mechanism", "JACOCO")
                for f in directly_changed_files:
                    for cls_name, test_list in classes_covered.items():
                        if cls_name.lower() in f.lower() or os.path.splitext(os.path.basename(f))[0].lower() in cls_name.lower():
                            for t in test_list:
                                cand = get_or_create_candidate(test_name=t, test_file="")
                                cand["evidence_sources"].add("RUNTIME")
                                cand["evidence_types"].add(TestEvidenceType.RUNTIME_COVERAGE.value)
                                cand["runtime_coverage"] = True
                                cand["evidence_path"].append(
                                    f"Changed Component: {f} -> {coverage_mechanism} Coverage -> Executing Test: {t}"
                                )
                                cand["reasons"].append(
                                    f"Observed runtime coverage ({coverage_mechanism}): test executed against changed {f} in CI"
                                )
                                runtime_evidence.append({
                                    "evidence_type": TestEvidenceType.RUNTIME_COVERAGE.value,
                                    "component": f,
                                    "test": t,
                                    "mechanism": coverage_mechanism,
                                })

        # 2. Runtime CI Workflow Execution History
        if runtime_test_runs and isinstance(runtime_test_runs, list):
            for run in runtime_test_runs[:10]:
                run_status = run.get("status") or run.get("github_run_status", "UNKNOWN")
                run_conclusion = run.get("conclusion", "UNKNOWN")
                wf_name = run.get("workflow_name") or f"Run #{run.get('workflow_run_id', 'CI')}"
                runtime_evidence.append({
                    "evidence_type": TestEvidenceType.RUNTIME_CI.value,
                    "workflow": wf_name,
                    "workflow_run_id": run.get("workflow_run_id"),
                    "status": run_status,
                    "conclusion": run_conclusion,
                    "commit_sha": run.get("commit_sha"),
                    "duration_ms": run.get("duration_ms", 0),
                    "passed_tests": run.get("passed_tests", 0),
                    "failed_tests": run.get("failed_tests", 0),
                })

        # ----------------------------------------------------------------------
        # C. HISTORICAL TEST EVIDENCE: Historical Failures & Co-Changes
        # ----------------------------------------------------------------------
        # 1. Historical Failures on Affected Modules / Components
        if historical_failed_tests and isinstance(historical_failed_tests, list):
            for fail_rec in historical_failed_tests:
                failed_name = fail_rec.get("test_name") or fail_rec.get("name") or ""
                failed_class = fail_rec.get("classname") or ""
                fail_count = int(fail_rec.get("failure_count", 1) or 1)

                if failed_name:
                    # Match against directly changed files or candidate test names
                    matched_file = any(
                        os.path.splitext(os.path.basename(f))[0].lower() in (failed_name.lower() + failed_class.lower())
                        for f in directly_changed_files
                    )
                    if matched_file or any(c_key for c_key in candidates if failed_name.lower() in c_key.lower()):
                        cand = get_or_create_candidate(test_name=failed_name, test_file=failed_class)
                        cand["evidence_sources"].add("HISTORICAL")
                        cand["evidence_types"].add(TestEvidenceType.HISTORICAL_FAILURE.value)
                        cand["historical_failure_count"] += fail_count
                        cand["evidence_path"].append(
                            f"Modified Component -> Historical Test Failure Database ({fail_count} failures) -> Test: {failed_name}"
                        )
                        cand["reasons"].append(
                            f"Observed historical instability: test failed {fail_count} times in prior CI runs touching this component"
                        )
                        historical_failures.append({
                            "test_name": failed_name,
                            "classname": failed_class,
                            "failure_count": fail_count,
                            "last_error": fail_rec.get("error_message"),
                        })

        # 2. Historical Co-Change Frequency
        if historical_commits and isinstance(historical_commits, list):
            for f in directly_changed_files[:5]:
                base_f = os.path.basename(f)
                name_no_ext = os.path.splitext(base_f)[0]
                cochange_tests: Dict[str, int] = {}

                for c in historical_commits:
                    msg = c.get("message", "")
                    if name_no_ext.lower() in msg.lower():
                        # Find if any candidate tests are mentioned in the same commit
                        for c_key, cand in candidates.items():
                            t_name = cand["test_name"]
                            if t_name.lower() in msg.lower():
                                cochange_tests[t_name] = cochange_tests.get(t_name, 0) + 1

                for t_name, count in cochange_tests.items():
                    for cand in candidates.values():
                        if cand["test_name"] == t_name:
                            cand["evidence_sources"].add("HISTORICAL")
                            cand["evidence_types"].add(TestEvidenceType.HISTORICAL_COCHANGE.value)
                            cand["cochange_count"] += count
                            cand["evidence_path"].append(
                                f"Changed File: {f} -> Historical Co-Commit Coupling ({count} commits) -> Test: {t_name}"
                            )
                            cand["reasons"].append(
                                f"Historical change coupling: test committed together with modified file in {count} prior commits"
                            )

        # ----------------------------------------------------------------------
        # Module-level Heuristic Fallback (OPTIONAL bucket only)
        # ----------------------------------------------------------------------
        if not candidates:
            for f in directly_changed_files:
                norm = f.replace("\\", "/").strip("/")
                parts = norm.split("/")
                module = parts[1] if parts[0] == "services" and len(parts) > 1 else parts[0]
                cand = get_or_create_candidate(
                    test_name=f"{module.replace('-', ' ').title().replace(' ', '')}Suite",
                    test_file=f"services/{module}/src/test" if parts[0] == "services" else "tests",
                )
                cand["evidence_sources"].add("STATIC")
                cand["evidence_types"].add("HEURISTIC_MODULE")
                cand["evidence_path"].append(f"Changed Module: {module} -> Module Regression Boundary -> Test Suite")
                cand["reasons"].append(f"Module-level regression test suite for {module} (heuristic suggestion)")

        # ----------------------------------------------------------------------
        # 2. Deterministic Test Prioritization Formula
        # ----------------------------------------------------------------------
        all_recommendations: List[TestRecommendation] = []

        for cand in candidates.values():
            score, confidence, bucket = cls._calculate_test_priority(
                cand=cand,
                deterministic_risk_score=deterministic_risk_score,
            )
            merge_req = cls._calculate_merge_requirement(
                cand=cand,
                deterministic_risk_score=deterministic_risk_score,
            )
            # Combine reasons and evidence paths cleanly
            combined_reason = "; ".join(cand["reasons"]) or "Regression testing recommended for modified area."
            primary_path = cand["evidence_path"] if cand["evidence_path"] else [f"Changed Component -> Test: {cand['test_name']}"]

            rec = TestRecommendation(
                organization_id=organization_id,
                repository_id=repository_id,
                test_name=cand["test_name"],
                test_file=cand["test_file"],
                test_method=cand["test_method"],
                target_symbol=cand["target_symbol"],
                priority_score=score,
                priority_bucket=bucket,
                merge_requirement=merge_req,
                confidence=confidence,
                evidence_sources=sorted(list(cand["evidence_sources"])),
                evidence_types=sorted(list(cand["evidence_types"])),
                evidence_path=primary_path,
                reason=combined_reason,
                historical_failure_count=cand["historical_failure_count"],
                cochange_count=cand["cochange_count"],
                runtime_coverage_verified=cand["runtime_coverage"],
            )
            all_recommendations.append(rec)

        # Sort recommendations by priority score descending
        all_recommendations.sort(key=lambda r: r.priority_score, reverse=True)

        # Partition into MUST_RUN, SHOULD_RUN, OPTIONAL
        must_run = [r for r in all_recommendations if r.priority_bucket == TestPriorityBucket.MUST_RUN.value]
        should_run = [r for r in all_recommendations if r.priority_bucket == TestPriorityBucket.SHOULD_RUN.value]
        optional = [r for r in all_recommendations if r.priority_bucket == TestPriorityBucket.OPTIONAL.value]

        # ----------------------------------------------------------------------
        # 3. Overall Test Strategy Confidence
        # ----------------------------------------------------------------------
        overall_conf, conf_reason = cls._derive_overall_confidence(
            recommendations=all_recommendations,
            coverage_available=coverage_available,
            has_runtime_ci=bool(runtime_evidence),
        )

        coverage_summary = {
            "coverage_available": coverage_available,
            "coverage_mechanism": coverage_mechanism,
            "runtime_runs_ingested": len(runtime_evidence),
            "historical_failures_observed": len(historical_failures),
        }

        return TestStrategyResult(
            must_run=must_run,
            should_run=should_run,
            optional=optional,
            static_evidence=static_evidence,
            runtime_evidence=runtime_evidence,
            historical_failures=historical_failures,
            coverage_summary=coverage_summary,
            overall_confidence=overall_conf,
            confidence_rationale=conf_reason,
        )

    @classmethod
    def _calculate_test_priority(
        cls,
        cand: Dict[str, Any],
        deterministic_risk_score: float,
    ) -> Tuple[float, str, str, str]:
        """
        Calculates explainable priority score (0.0 to 100.0), confidence, bucket, and merge_requirement.

        Weight Breakdown:
          1. Direct Symbol Coverage (Max 35.0):
             - AST_CALL: 35.0
             - FILE_REFERENCE: 25.0
          2. Runtime Coverage Evidence (Max 25.0):
             - RUNTIME_COVERAGE: 25.0
             - RUNTIME_CI: 15.0
          3. Transitive Graph Blast Radius (Max 15.0):
             - Depth 1 dependent caller: 15.0
             - Depth 2-3 dependent caller: 10.0
          4. Historical Failure Frequency (Max 15.0):
             - min(15.0, failures * 5.0)
          5. Structural Pairing / Co-Change (Max 10.0):
             - PATH_PAIRING: 10.0
             - HISTORICAL_COCHANGE: min(10.0, cochanges * 2.5)
        """
        # 1. Direct Symbol Coverage (Max 35.0)
        symbol_score = 0.0
        if cand.get("ast_call"):
            symbol_score = 35.0
        elif cand.get("file_ref"):
            symbol_score = 25.0

        # 2. Runtime Coverage (Max 25.0)
        runtime_score = 0.0
        if cand.get("runtime_coverage"):
            runtime_score = 25.0
        elif cand.get("runtime_ci"):
            runtime_score = 15.0

        # 3. Graph Blast Radius (Max 15.0)
        blast_score = 0.0
        depth = cand.get("blast_radius_depth")
        if depth == 1:
            blast_score = 15.0
        elif depth and depth > 1:
            blast_score = 10.0

        # 4. Historical Failure & Co-Change Frequency (Max 15.0)
        fail_count = cand.get("historical_failure_count", 0)
        cochange_count = cand.get("cochange_count", 0)
        failure_score = min(15.0, (fail_count * 5.0) + (cochange_count * 2.5))

        # 5. Structural / Path Pairing (Max 10.0)
        structural_score = 10.0 if cand.get("path_pairing") else 0.0

        total_score = round(min(100.0, symbol_score + runtime_score + blast_score + failure_score + structural_score), 1)

        # High risk PR booster: If PR has high risk score (>=50.0) and candidate covers changed symbol directly,
        # ensure it reaches MUST_RUN threshold
        if deterministic_risk_score >= 50.0 and (cand.get("ast_call") or cand.get("runtime_coverage")):
            total_score = max(total_score, 65.0)

        # Priority Bucketing:
        # MUST_RUN: >= 60.0
        # SHOULD_RUN: 30.0 <= score < 60.0
        # OPTIONAL: < 30.0
        if total_score >= 60.0:
            bucket = TestPriorityBucket.MUST_RUN.value
        elif total_score >= 30.0:
            bucket = TestPriorityBucket.SHOULD_RUN.value
        else:
            bucket = TestPriorityBucket.OPTIONAL.value

        # Confidence Derivation:
        # HIGH: runtime coverage directly connects test, or strong AST call plus verified execution
        # MEDIUM: static graph/structural pairing or historical failure/co-change
        # LOW: heuristic suggestion
        if cand.get("runtime_coverage") or (cand.get("ast_call") and (cand.get("runtime_ci") or fail_count > 0)):
            confidence = "HIGH"
        elif cand.get("ast_call") or cand.get("path_pairing") or fail_count > 0 or cand.get("cochange_count", 0) > 0:
            confidence = "MEDIUM"
        else:
            confidence = "LOW"

        return total_score, confidence, bucket

    @classmethod
    def _calculate_merge_requirement(
        cls,
        cand: Dict[str, Any],
        deterministic_risk_score: float,
    ) -> str:
        """
        Determine Stronger Concept: MERGE-REQUIRED TEST
        Possible states: TEST_REQUIRED, TEST_RECOMMENDED, TEST_OPTIONAL
        A test can become TEST_REQUIRED when:
        - it directly covers a changed critical symbol (AST_CALL or RUNTIME_COVERAGE)
        - direct structural unit test pairing for changed component (PATH_PAIRING)
        - PR risk exceeds configured threshold (>= 50.0) and touches modified component
        - the test is historically failure-prone (fail_count >= 1)
        - blast radius depth is 1 on a medium/high risk PR
        """
        fail_count = cand.get("historical_failure_count", 0)
        if (
            cand.get("ast_call")
            or cand.get("runtime_coverage")
            or cand.get("path_pairing")
            or fail_count >= 1
            or (cand.get("blast_radius_depth") == 1 and deterministic_risk_score >= 35.0)
            or (deterministic_risk_score >= 50.0 and cand.get("file_ref"))
        ):
            return TestMergeRequirement.TEST_REQUIRED.value
        elif cand.get("file_ref") or cand.get("cochange_count", 0) > 0 or deterministic_risk_score >= 25.0:
            return TestMergeRequirement.TEST_RECOMMENDED.value
        else:
            return TestMergeRequirement.TEST_OPTIONAL.value


    @classmethod
    def _derive_overall_confidence(
        cls,
        recommendations: List[TestRecommendation],
        coverage_available: bool,
        has_runtime_ci: bool,
    ) -> Tuple[str, str]:
        """
        Formalized confidence evaluation based strictly on observed evidence:
        HIGH: runtime coverage available or multiple HIGH confidence tests backed by AST/CI.
        MEDIUM: static AST graph or structural path pairings present.
        LOW: heuristic module-level suggestions only.
        """
        if not recommendations:
            return "LOW", "No test candidates identified from repository artifacts."

        has_high_recs = any(r.confidence == "HIGH" for r in recommendations)
        has_med_recs = any(r.confidence == "MEDIUM" for r in recommendations)

        if coverage_available and has_high_recs:
            return "HIGH", "Runtime coverage evidence (JaCoCo) directly maps modified symbols to executing tests."
        elif has_high_recs or (has_med_recs and has_runtime_ci):
            return "HIGH", "Direct AST call graph connections confirmed with active CI workflow run validation."
        elif has_med_recs:
            return "MEDIUM", "Static AST graph and structural unit test pairings identified; runtime coverage data unavailable (graceful static fallback)."
        else:
            return "LOW", "Heuristic module-level test suggestions only; no direct AST invocation or runtime coverage evidence detected."

    @classmethod
    def calculate_validation_metrics(
        cls,
        recommended_tests: List[TestRecommendation],
        executed_test_results: List[Dict[str, Any]],
        workflow_run_id: Optional[int] = None,
    ) -> Dict[str, Any]:
        """
        Computes mathematically defined post-PR test validation feedback metrics:
          - recommended_count: total tests recommended
          - executed_count: recommended tests that actually executed in CI
          - passed_count: executed tests that passed
          - failed_count: executed tests that failed
          - high_priority_missed_count: tests that failed in CI but were NOT in MUST_RUN or TEST_REQUIRED
          - prediction_to_failure_correlation:
              (failed recommended tests) / (total failed tests in CI)
              Yields None if 0 failures occurred (cannot claim predictive accuracy without failure signal).
          - required_test_failure_capture:
              (failed TEST_REQUIRED tests) / (total failed tests in CI)
              Yields None if 0 failures occurred.
          - required_test_execution_rate:
              (executed TEST_REQUIRED tests) / (total TEST_REQUIRED tests)
          - false_positive_required_test_rate:
              (passed TEST_REQUIRED tests) / (total executed TEST_REQUIRED tests)
          - missed_failure_count:
              total failed tests in CI not caught by TEST_REQUIRED
          - recommendation_coverage:
              (matched_executed) / (recommended_count)
          - test_selection_efficiency:
              (executed MUST_RUN tests) / (total executed tests in CI)
        """
        rec_names = {r.test_name.lower(): r for r in recommended_tests}
        must_run_names = {r.test_name.lower(): r for r in recommended_tests if r.priority_bucket == TestPriorityBucket.MUST_RUN.value}
        required_names = {r.test_name.lower(): r for r in recommended_tests if r.merge_requirement == TestMergeRequirement.TEST_REQUIRED.value}

        total_executed = len(executed_test_results)
        matched_executed = 0
        matched_passed = 0
        matched_failed = 0
        all_failed_in_ci = 0
        missed_high_priority = 0

        required_executed_count = 0
        required_passed_count = 0
        required_failed_count = 0

        for res in executed_test_results:
            t_name = (res.get("name") or res.get("test_name") or "").lower()
            status = (res.get("status") or "").upper()
            is_failed = status in ("FAILED", "FAILURE", "FAIL")

            if is_failed:
                all_failed_in_ci += 1

            matched_rec = next((r for name, r in rec_names.items() if name in t_name or t_name in name), None)
            if matched_rec:
                matched_executed += 1
                if is_failed:
                    matched_failed += 1
                elif status in ("PASSED", "SUCCESS", "PASS"):
                    matched_passed += 1

            # Check if matching required test
            matched_req = next((r for name, r in required_names.items() if name in t_name or t_name in name), None)
            if matched_req:
                required_executed_count += 1
                if is_failed:
                    required_failed_count += 1
                elif status in ("PASSED", "SUCCESS", "PASS"):
                    required_passed_count += 1

            # Check if an unpredicted or non-MUST_RUN / non-REQUIRED test failed
            if is_failed:
                is_must_run = any(name in t_name or t_name in name for name in must_run_names)
                is_req = any(name in t_name or t_name in name for name in required_names)
                if not (is_must_run or is_req):
                    missed_high_priority += 1

        # 1. Prediction to Failure Correlation:
        # Proportion of actual CI failures anticipated by any recommended test.
        # If 0 failures occurred in CI, returns None (do not claim 1.0 artificial accuracy).
        pred_correlation = (
            round(matched_failed / all_failed_in_ci, 2)
            if all_failed_in_ci > 0
            else None
        )

        # 2. Required Test Failure Capture:
        # Proportion of actual CI failures captured specifically by TEST_REQUIRED tests.
        required_failure_capture = (
            round(required_failed_count / all_failed_in_ci, 2)
            if all_failed_in_ci > 0
            else None
        )

        # 3. Required Test Execution Rate:
        # Proportion of identified TEST_REQUIRED tests that actually ran in CI.
        total_required_tests = len(required_names)
        required_execution_rate = (
            round(required_executed_count / total_required_tests, 2)
            if total_required_tests > 0
            else 1.0
        )

        # 4. False Positive Required Test Rate:
        # Proportion of executed TEST_REQUIRED tests that passed without revealing a failure.
        false_positive_required_rate = (
            round(required_passed_count / required_executed_count, 2)
            if required_executed_count > 0
            else 0.0
        )

        # 5. Missed Failure Count:
        # Total CI failures that were NOT captured by TEST_REQUIRED tests.
        missed_failure_count = all_failed_in_ci - required_failed_count

        # 6. Recommendation Coverage:
        # Proportion of all recommended tests that executed.
        rec_count = len(recommended_tests)
        rec_coverage = round(matched_executed / rec_count, 2) if rec_count > 0 else 1.0

        # 7. Test Selection Efficiency:
        must_run_count = len(must_run_names)
        efficiency = round(must_run_count / total_executed, 2) if total_executed > 0 else 1.0

        return {
            "recommended_count": rec_count,
            "executed_count": matched_executed,
            "passed_count": matched_passed,
            "failed_count": matched_failed,
            "high_priority_missed_count": missed_high_priority,
            "prediction_to_failure_correlation": pred_correlation,
            "test_selection_efficiency": efficiency,
            "required_test_failure_capture": required_failure_capture,
            "required_test_execution_rate": required_execution_rate,
            "false_positive_required_test_rate": false_positive_required_rate,
            "missed_failure_count": missed_failure_count,
            "recommendation_coverage": rec_coverage,
            "total_ci_tests_executed": total_executed,
            "total_ci_failures": all_failed_in_ci,
            "workflow_run_id": workflow_run_id,
        }
