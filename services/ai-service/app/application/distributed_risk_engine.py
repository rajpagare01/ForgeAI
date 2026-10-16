"""
DistributedRiskEngine — Deterministic Distributed Risk Computation

Computes distributed risk from cross-repository dependencies and combines it
with repository-local risk scores.

Formula:
  - direct_downstream_repos    * 3.0   (max 15.0)
  - transitive_downstream_repos * 1.5  (max 7.5)
  - BREAKING contract (HIGH conf)       + 20.0  (max 20.0)
  - POTENTIALLY_BREAKING (HIGH conf)    + 10.0  (max 10.0)
  - downstream required test failures   * 5.0   (max 15.0)
  - downstream CI failures              * 7.5   (max 15.0)
  - total downstream components        * 0.5   (max 7.5)
  Raw cap: min(sum, 50.0)

Combined:
  combined_risk_score = min(100.0, local_risk_score + distributed_risk_score * 0.4)

Levels:
  distributed_risk_level:
    0–10  → NONE
    10–20 → LOW
    20–35 → MEDIUM
    35–50 → HIGH

  combined_risk_level:
    < 25  → LOW
    < 50  → MEDIUM
    < 75  → HIGH
    >= 75 → CRITICAL
"""
from typing import List, Dict, Any, Optional
from app.domain.models import (
    DistributedRiskAssessment,
    DownstreamRepositoryImpact,
    DependencyConfidence,
    ContractCompatibilityStatus,
    DownstreamCIStatus,
)


class DistributedRiskEngine:
    """
    Computes distributed risk metrics across multi-repository topologies.
    Enforces deterministic evaluation with full factor breakdown and provenance.
    """

    DIRECT_DOWNSTREAM_WEIGHT = 3.0
    TRANSITIVE_DOWNSTREAM_WEIGHT = 1.5
    BREAKING_CONTRACT_WEIGHT = 20.0
    POTENTIALLY_BREAKING_WEIGHT = 10.0
    DOWNSTREAM_TEST_FAIL_WEIGHT = 5.0
    DOWNSTREAM_CI_FAIL_WEIGHT = 7.5
    DOWNSTREAM_COMPONENT_WEIGHT = 0.5

    MAX_DIRECT_SCORE = 15.0
    MAX_TRANSITIVE_SCORE = 7.5
    MAX_CONTRACT_SCORE = 20.0
    MAX_TEST_FAIL_SCORE = 15.0
    MAX_CI_FAIL_SCORE = 15.0
    MAX_COMPONENT_SCORE = 7.5
    MAX_DISTRIBUTED_SCORE = 50.0
    DISTRIBUTED_FACTOR = 0.4

    @classmethod
    def compute_distributed_risk(
        cls,
        local_risk_score: float,
        downstream_impacts: List[DownstreamRepositoryImpact],
        contract_status: str,
        contract_rationale: str = "",
        transitive_count: int = 0,
    ) -> Dict[str, Any]:
        """
        Evaluates risk factors from downstream impacts and contract compatibility.
        LOW-confidence dependencies contribute 0 points to risk.
        """
        factors: List[Dict[str, Any]] = []

        # Filter to authoritative dependencies (HIGH/MEDIUM)
        authoritative_impacts = [
            impact for impact in downstream_impacts
            if impact.dependency_confidence in (DependencyConfidence.HIGH, DependencyConfidence.MEDIUM)
        ]

        direct_count = len(authoritative_impacts)
        direct_score = min(cls.MAX_DIRECT_SCORE, direct_count * cls.DIRECT_DOWNSTREAM_WEIGHT)
        if direct_score > 0:
            factors.append({
                "factor": "direct_downstream_dependencies",
                "score": direct_score,
                "count": direct_count,
                "details": f"{direct_count} direct authoritative downstream repository dependencies",
            })

        transitive_score = min(cls.MAX_TRANSITIVE_SCORE, transitive_count * cls.TRANSITIVE_DOWNSTREAM_WEIGHT)
        if transitive_score > 0:
            factors.append({
                "factor": "transitive_downstream_dependencies",
                "score": transitive_score,
                "count": transitive_count,
                "details": f"{transitive_count} transitive downstream repository dependencies",
            })

        # Contract compatibility penalty applies only when there are downstream dependents
        contract_score = 0.0
        if direct_count > 0:
            if contract_status == ContractCompatibilityStatus.BREAKING:
                contract_score = min(cls.MAX_CONTRACT_SCORE, cls.BREAKING_CONTRACT_WEIGHT)
                factors.append({
                    "factor": "breaking_contract_compatibility",
                    "score": contract_score,
                    "details": contract_rationale or "Breaking contract detected on exported interface",
                })
            elif contract_status == ContractCompatibilityStatus.POTENTIALLY_BREAKING:
                contract_score = min(cls.MAX_CONTRACT_SCORE, cls.POTENTIALLY_BREAKING_WEIGHT)
                factors.append({
                    "factor": "potentially_breaking_contract",
                    "score": contract_score,
                    "details": contract_rationale or "Potentially breaking contract change detected",
                })

        # Downstream CI failures
        ci_failures = sum(
            1 for impact in authoritative_impacts
            if impact.ci_status == DownstreamCIStatus.FAIL
        )
        ci_score = min(cls.MAX_CI_FAIL_SCORE, ci_failures * cls.DOWNSTREAM_CI_FAIL_WEIGHT)
        if ci_score > 0:
            factors.append({
                "factor": "downstream_ci_failures",
                "score": ci_score,
                "count": ci_failures,
                "details": f"{ci_failures} downstream repository CI workflows failing",
            })

        # Downstream test failures
        test_failures = sum(
            1 for impact in authoritative_impacts
            for t in impact.downstream_tests
            if t.get("status") == "FAILED" or t.get("conclusion") == "failure"
        )
        test_score = min(cls.MAX_TEST_FAIL_SCORE, test_failures * cls.DOWNSTREAM_TEST_FAIL_WEIGHT)
        if test_score > 0:
            factors.append({
                "factor": "downstream_test_failures",
                "score": test_score,
                "count": test_failures,
                "details": f"{test_failures} required downstream test failures identified",
            })

        # Downstream components/symbols
        affected_symbols_count = sum(len(impact.affected_symbols) for impact in authoritative_impacts)
        component_score = min(cls.MAX_COMPONENT_SCORE, affected_symbols_count * cls.DOWNSTREAM_COMPONENT_WEIGHT)
        if component_score > 0:
            factors.append({
                "factor": "downstream_affected_components",
                "score": component_score,
                "count": affected_symbols_count,
                "details": f"{affected_symbols_count} affected downstream symbols/components",
            })

        raw_distributed_score = round(
            direct_score + transitive_score + contract_score + ci_score + test_score + component_score,
            2
        )
        distributed_risk_score = round(min(cls.MAX_DISTRIBUTED_SCORE, raw_distributed_score), 1)

        # Classify distributed risk level
        if distributed_risk_score >= 35.0:
            distributed_risk_level = "HIGH"
        elif distributed_risk_score >= 20.0:
            distributed_risk_level = "MEDIUM"
        elif distributed_risk_score >= 10.0:
            distributed_risk_level = "LOW"
        else:
            distributed_risk_level = "NONE"

        # Combined risk score
        combined_risk_score = round(
            min(100.0, float(local_risk_score) + distributed_risk_score * cls.DISTRIBUTED_FACTOR),
            1
        )

        if combined_risk_score >= 75.0:
            combined_risk_level = "CRITICAL"
        elif combined_risk_score >= 50.0:
            combined_risk_level = "HIGH"
        elif combined_risk_score >= 25.0:
            combined_risk_level = "MEDIUM"
        else:
            combined_risk_level = "LOW"

        return {
            "distributed_risk_score": distributed_risk_score,
            "distributed_risk_level": distributed_risk_level,
            "combined_risk_score": combined_risk_score,
            "combined_risk_level": combined_risk_level,
            "direct_downstream_count": direct_count,
            "transitive_downstream_count": transitive_count,
            "contract_status": contract_status,
            "factors": factors,
        }
