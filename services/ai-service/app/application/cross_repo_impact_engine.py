"""
CrossRepositoryImpactEngine — Distributed Change Impact Analysis

Propagates change impact from a single repository PR outward to all known
downstream repositories via the persisted RepositoryDependency graph.

Architecture:
  local_impact (existing ChangeImpactEngine result)
    → lookup known downstream dependents via RepositoryDependencyRepository
    → for each HIGH/MEDIUM confidence dependent:
        → fetch downstream repository state and test evidence
        → assess contract compatibility impact
        → classify downstream CI status
    → produce DistributedRiskAssessment

Depth limit: max 2 hops by default (configurable).
LOW-confidence-only deps are reported but NEVER block merge.
"""
import logging
from typing import List, Dict, Any, Optional, Set
from uuid import UUID

from app.domain.models import (
    RepositoryDependency,
    DownstreamRepositoryImpact,
    DistributedRiskAssessment,
    DependencyType,
    DependencyConfidence,
    ContractCompatibilityStatus,
    DownstreamCIStatus,
)

logger = logging.getLogger(__name__)

# Distributed risk score constants (see DistributedRiskAssessment docstring for formula)
_DIRECT_DOWNSTREAM_PTS         = 3.0
_TRANSITIVE_DOWNSTREAM_PTS     = 1.5
_BREAKING_CONTRACT_PTS         = 20.0
_POTENTIALLY_BREAKING_PTS      = 10.0
_DOWNSTREAM_TEST_FAIL_PTS      = 5.0
_DOWNSTREAM_CI_FAIL_PTS        = 7.5
_DOWNSTREAM_COMPONENT_PTS      = 0.5
_MAX_DIRECT_PTS                = 15.0
_MAX_TRANSITIVE_PTS            = 7.5
_MAX_CONTRACT_PTS              = 20.0
_MAX_TEST_FAIL_PTS             = 15.0
_MAX_CI_FAIL_PTS               = 15.0
_MAX_COMPONENT_PTS             = 7.5
_MAX_RAW_DISTRIBUTED           = 50.0
_DISTRIBUTED_WEIGHT            = 0.4


class CrossRepositoryImpactEngine:
    """
    Propagates change impact across the cross-repository dependency graph.
    All computation is deterministic and evidence-backed.
    """

    @classmethod
    def analyze_distributed_impact(
        cls,
        organization_id: UUID,
        source_repository_id: UUID,
        source_repository_name: str,
        changed_files: List[Dict[str, Any]],
        changed_symbols: List[str],
        contract_status: str,                              # from DependencyDiscoveryEngine
        contract_rationale: str,
        local_risk_score: float,
        direct_downstream_deps: List[RepositoryDependency],    # HIGH + MEDIUM confidence deps
        downstream_ci_evidence: Dict[str, Dict[str, Any]],    # repo_id_str → CI run dict
        downstream_test_evidence: Dict[str, List[Dict[str, Any]]],  # repo_id_str → test runs
        all_org_repositories: List[Dict[str, Any]],
        pr_number: int = 0,
        commit_sha: str = "",
        max_depth: int = 2,
    ) -> DistributedRiskAssessment:
        """
        Produces a DistributedRiskAssessment for a PR.

        Parameters:
            direct_downstream_deps   — deps where target_repository_id == source_repository_id
                                       (i.e. repos that depend ON the changed repo)
            downstream_ci_evidence   — optional dict: {str(repo_id): {"status":..., "conclusion":...}}
            downstream_test_evidence — optional dict: {str(repo_id): [{"status":..., ...}]}
        """
        provenance: List[Dict[str, Any]] = []
        downstream_impacts: List[DownstreamRepositoryImpact] = []
        risk_factors: List[Dict[str, Any]] = []

        # Build repo name lookup
        id_to_name: Dict[str, str] = {}
        for repo in all_org_repositories:
            rid = str(repo.get("id") or repo.get("repositoryId") or "")
            rname = repo.get("name") or repo.get("fullName") or rid
            if rid:
                id_to_name[rid] = rname

        # ----------------------------------------------------------------
        # 1. Classify each direct downstream repo
        # ----------------------------------------------------------------
        seen_repos: Set[str] = set()
        high_confidence_deps = [
            d for d in direct_downstream_deps
            if d.confidence in (DependencyConfidence.HIGH, DependencyConfidence.MEDIUM)
        ]
        low_confidence_only_deps = [
            d for d in direct_downstream_deps
            if d.confidence == DependencyConfidence.LOW
            and str(d.source_repository_id) not in {str(d2.source_repository_id) for d2 in high_confidence_deps}
        ]

        for dep in direct_downstream_deps:
            downstream_repo_id = str(dep.source_repository_id)  # the repo that depends ON us
            if downstream_repo_id in seen_repos:
                continue
            seen_repos.add(downstream_repo_id)

            repo_name = id_to_name.get(downstream_repo_id, downstream_repo_id[:8])

            # CI status for this downstream repo
            ci_data = downstream_ci_evidence.get(downstream_repo_id) or {}
            ci_status = cls._classify_ci_status(ci_data)
            ci_run_id = str(ci_data.get("id") or ci_data.get("workflowRunId") or "")
            ci_conclusion = ci_data.get("conclusion") or ci_data.get("status") or ""

            # Downstream test data
            test_data = downstream_test_evidence.get(downstream_repo_id) or []

            # Affected symbols: which locally changed APIs are referenced downstream
            affected_symbols = cls._derive_affected_downstream_symbols(
                changed_symbols=changed_symbols,
                dep=dep,
            )

            downstream_impacts.append(DownstreamRepositoryImpact(
                repository_id=dep.source_repository_id,
                repository_name=repo_name,
                dependency_type=dep.dependency_type,
                dependency_confidence=dep.confidence,
                evidence_type=dep.evidence_type,
                source_reference=dep.source_reference,
                target_reference=dep.target_reference,
                contract_status=contract_status,
                affected_symbols=affected_symbols,
                downstream_tests=test_data[:10],
                ci_status=ci_status,
                ci_run_id=ci_run_id or None,
                ci_conclusion=ci_conclusion or None,
                depth=1,
                provenance_path=[
                    f"{source_repository_name} → {dep.dependency_type} → {repo_name}",
                ],
            ))

            provenance.append({
                "source": "CROSS_REPO",
                "relationship": f"{repo_name} depends on {source_repository_name} via {dep.dependency_type}",
                "dependency_id": str(dep.id),
                "confidence": dep.confidence,
                "source_reference": dep.source_reference,
                "ci_status": ci_status,
                "contract_status": contract_status,
            })

        # Low-confidence: report but never add risk score
        for dep in low_confidence_only_deps:
            if str(dep.source_repository_id) in seen_repos:
                continue
            seen_repos.add(str(dep.source_repository_id))
            repo_name = id_to_name.get(str(dep.source_repository_id), str(dep.source_repository_id)[:8])
            downstream_impacts.append(DownstreamRepositoryImpact(
                repository_id=dep.source_repository_id,
                repository_name=repo_name,
                dependency_type=dep.dependency_type,
                dependency_confidence=dep.confidence,
                evidence_type=dep.evidence_type,
                source_reference=dep.source_reference,
                target_reference=dep.target_reference,
                contract_status=ContractCompatibilityStatus.UNKNOWN,
                ci_status=DownstreamCIStatus.UNKNOWN,
                depth=1,
                provenance_path=[f"{source_repository_name} → {dep.dependency_type} → {repo_name} (LOW confidence)"],
            ))
            provenance.append({
                "source": "CROSS_REPO",
                "relationship": f"{repo_name} low-confidence dep on {source_repository_name}",
                "confidence": dep.confidence,
                "note": "LOW confidence: not counted toward distributed risk score",
            })

        # ----------------------------------------------------------------
        # 2. Distributed Risk Score Calculation
        # ----------------------------------------------------------------
        direct_count = len(high_confidence_deps)  # only HIGH/MEDIUM count
        transitive_count = 0  # transitive hop-2 (future extension point)

        direct_pts    = min(_MAX_DIRECT_PTS, direct_count * _DIRECT_DOWNSTREAM_PTS)
        transitive_pts = min(_MAX_TRANSITIVE_PTS, transitive_count * _TRANSITIVE_DOWNSTREAM_PTS)

        # Contract compatibility contribution
        contract_pts = 0.0
        if (
            contract_status == ContractCompatibilityStatus.BREAKING
            and direct_count > 0
        ):
            contract_pts = min(_MAX_CONTRACT_PTS, _BREAKING_CONTRACT_PTS)
            risk_factors.append({
                "name": "Breaking Contract with Downstream Dependencies",
                "score": contract_pts,
                "details": contract_rationale,
                "evidence": "CROSS_REPO + CONTRACT_ANALYSIS",
            })
        elif (
            contract_status == ContractCompatibilityStatus.POTENTIALLY_BREAKING
            and direct_count > 0
        ):
            contract_pts = min(_MAX_CONTRACT_PTS, _POTENTIALLY_BREAKING_PTS)
            risk_factors.append({
                "name": "Potentially Breaking Contract",
                "score": contract_pts,
                "details": contract_rationale,
                "evidence": "CROSS_REPO + CONTRACT_ANALYSIS",
            })

        # Downstream CI failures
        ci_fail_count = sum(
            1 for di in downstream_impacts
            if di.ci_status in (DownstreamCIStatus.FAIL,)
            and di.dependency_confidence in (DependencyConfidence.HIGH, DependencyConfidence.MEDIUM)
        )
        ci_fail_pts = min(_MAX_CI_FAIL_PTS, ci_fail_count * _DOWNSTREAM_CI_FAIL_PTS)

        # Downstream test failures
        test_fail_count = sum(
            1 for di in downstream_impacts
            for t in di.downstream_tests
            if t.get("status") == "FAILED" or t.get("conclusion") == "failure"
        )
        test_fail_pts = min(_MAX_TEST_FAIL_PTS, test_fail_count * _DOWNSTREAM_TEST_FAIL_PTS)

        # Downstream component count
        total_downstream_symbols = sum(len(di.affected_symbols) for di in downstream_impacts)
        component_pts = min(_MAX_COMPONENT_PTS, total_downstream_symbols * _DOWNSTREAM_COMPONENT_PTS)

        raw_distributed = round(
            direct_pts + transitive_pts + contract_pts + ci_fail_pts + test_fail_pts + component_pts,
            2,
        )
        distributed_score = round(min(_MAX_RAW_DISTRIBUTED, raw_distributed), 1)

        # Risk level boundaries
        if distributed_score < 10.0:
            distributed_level = "NONE"
        elif distributed_score < 20.0:
            distributed_level = "LOW"
        elif distributed_score < 35.0:
            distributed_level = "MEDIUM"
        else:
            distributed_level = "HIGH"

        # Combined score
        combined_score = round(
            min(100.0, local_risk_score + distributed_score * _DISTRIBUTED_WEIGHT),
            1,
        )
        if combined_score < 25.0:
            combined_level = "LOW"
        elif combined_score < 50.0:
            combined_level = "MEDIUM"
        elif combined_score < 75.0:
            combined_level = "HIGH"
        else:
            combined_level = "CRITICAL"

        # Risk factor entries
        if direct_pts > 0:
            risk_factors.append({
                "name": "Direct Downstream Dependents",
                "score": direct_pts,
                "details": f"{direct_count} HIGH/MEDIUM confidence downstream repos depend on this service",
                "evidence": "CROSS_REPO",
            })
        if ci_fail_pts > 0:
            risk_factors.append({
                "name": "Downstream CI Failures",
                "score": ci_fail_pts,
                "details": f"{ci_fail_count} downstream repos have failing CI pipelines",
                "evidence": "DOWNSTREAM_CI",
            })
        if test_fail_pts > 0:
            risk_factors.append({
                "name": "Downstream Test Failures",
                "score": test_fail_pts,
                "details": f"{test_fail_count} failing tests in downstream repos",
                "evidence": "DOWNSTREAM_CI",
            })

        logger.info(
            "CrossRepoImpact PR#%d: %d direct deps, distributed_score=%.1f, combined_score=%.1f (%s)",
            pr_number, direct_count, distributed_score, combined_score, combined_level,
        )

        local_level = "LOW"
        if local_risk_score >= 75.0:
            local_level = "CRITICAL"
        elif local_risk_score >= 50.0:
            local_level = "HIGH"
        elif local_risk_score >= 25.0:
            local_level = "MEDIUM"

        return DistributedRiskAssessment(
            local_risk_score=local_risk_score,
            local_risk_level=local_level,
            distributed_risk_score=distributed_score,
            distributed_risk_level=distributed_level,
            combined_risk_score=combined_score,
            combined_risk_level=combined_level,
            direct_downstream_count=direct_count,
            transitive_downstream_count=transitive_count,
            max_cross_repo_depth=min(1, direct_count),
            contract_status=contract_status,
            downstream_impacts=downstream_impacts,
            distributed_risk_factors=risk_factors,
            provenance=provenance,
        )

    # ---------------------------------------------------------------------------
    # Helpers
    # ---------------------------------------------------------------------------

    @staticmethod
    def _classify_ci_status(ci_data: Dict[str, Any]) -> str:
        """Maps raw CI data to DownstreamCIStatus. Never treats empty as success."""
        if not ci_data:
            return DownstreamCIStatus.NOT_FOUND
        status = (ci_data.get("status") or "").lower()
        conclusion = (ci_data.get("conclusion") or "").lower()
        if conclusion == "success" or status == "success":
            return DownstreamCIStatus.PASS
        if conclusion in ("failure", "failed") or status in ("failure", "failed"):
            return DownstreamCIStatus.FAIL
        if status in ("queued", "in_progress", "running"):
            return DownstreamCIStatus.RUNNING
        if conclusion == "cancelled" or status == "cancelled":
            return DownstreamCIStatus.CANCELLED
        return DownstreamCIStatus.UNKNOWN

    @staticmethod
    def _derive_affected_downstream_symbols(
        changed_symbols: List[str],
        dep: RepositoryDependency,
    ) -> List[str]:
        """
        Returns locally changed symbols that are plausibly relevant to this downstream
        repo based on the dependency type and target_reference.
        Strictly static — no embedding similarity.
        """
        if not changed_symbols:
            return []

        target_ref = (dep.target_reference or "").lower()
        dep_type = dep.dependency_type

        affected: List[str] = []

        for sym in changed_symbols:
            sym_lower = sym.lower()
            # HTTP_API: symbols with API-related naming or path matching
            if dep_type == DependencyType.HTTP_API:
                if any(kw in sym_lower for kw in ("api", "controller", "endpoint", "handler", "resource")):
                    affected.append(sym)
                elif target_ref and any(part in sym_lower for part in target_ref.split("/") if len(part) > 3):
                    affected.append(sym)
            # SHARED_LIBRARY: all changed symbols are potentially impacting
            elif dep_type == DependencyType.SHARED_LIBRARY:
                if not any(kw in sym_lower for kw in ("test", "spec", "mock")):
                    affected.append(sym)
            # EVENT_CONTRACT / SHARED_SCHEMA
            elif dep_type in (DependencyType.EVENT_CONTRACT, DependencyType.SHARED_SCHEMA):
                if any(kw in sym_lower for kw in ("event", "message", "schema", "dto", "payload", "proto")):
                    affected.append(sym)

        return affected[:10]  # Cap at 10 for readability
