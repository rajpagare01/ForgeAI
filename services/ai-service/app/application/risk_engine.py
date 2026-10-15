import logging
from typing import List, Dict, Any, Tuple
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)


class RiskFactor(BaseModel):
    name: str
    weight: float
    score: float
    details: str
    evidence_keys: List[str] = Field(default_factory=list)


class RiskAssessment(BaseModel):
    risk_level: str  # "LOW", "MEDIUM", "HIGH", "CRITICAL"
    risk_score: float  # 0.0 to 100.0
    confidence: str  # "HIGH", "MEDIUM", "LOW"
    confidence_reason: str
    risk_factors: List[RiskFactor] = Field(default_factory=list)
    evidence: List[str] = Field(default_factory=list)


class DeterministicRiskEngine:
    """
    Deterministic Risk Scoring Layer for ForgeAI.
    Calculates evidence-backed risk scores BEFORE LLM reasoning.
    The LLM explains the evidence rather than inventing the score.

    ======================================================================
    DETERMINISTIC SCORING MODEL (Total Scale: 0.0 to 100.0):
    ======================================================================
    1. Change Volume (Weight: 20% / Max: 20.0 pts):
       - File count contribution: min(10.0, file_count * 2.0)
       - Line change churn (+ additions + deletions): min(10.0, total_lines / 50.0)

    2. Symbol Mutation Scope (Weight: 20% / Max: 20.0 pts):
       - Directly changed AST symbols: min(20.0, symbol_count * 3.0)

    3. Blast Radius & Graph Depth (Weight: 25% / Max: 25.0 pts):
       - Graph depth reached (0-3 hops): min(12.0, max_depth * 4.0)
       - Transitive downstream dependents: min(13.0, transitive_count * 2.5)

    4. Architectural Boundary Crossings (Weight: 15% / Max: 15.0 pts):
       - Cross-module/service impact: min(15.0, max(0, module_count - 1) * 7.5)

    5. Test Coverage & Verification Gap (Weight: 10% / Max: 10.0 pts):
       - High confidence tests verified: 0.0 pts (no verification penalty)
       - Medium confidence tests verified: 3.0 pts
       - Low confidence tests (heuristic only): 6.0 pts
       - No tests identified: 10.0 pts (maximum verification gap penalty)

    6. File Deletion / Interface Shift (Weight: 10% / Max: 10.0 pts):
       - Removed files: min(10.0, removed_files_count * 5.0)

    ======================================================================
    RISK LEVEL BOUNDARIES:
    ======================================================================
    - 0.0  <= score < 25.0  -> LOW
    - 25.0 <= score < 50.0  -> MEDIUM
    - 50.0 <= score < 75.0  -> HIGH
    - 75.0 <= score <= 100.0 -> CRITICAL

    ======================================================================
    CONFIDENCE LEVEL MODEL:
    ======================================================================
    - HIGH: AST symbols verified, graph edges traversed, test relationships grounded
    - MEDIUM: AST symbols or graph traversed, but test relationships missing or heuristic
    - LOW: Insufficient context or diff-only fallback
    """

    @classmethod
    def calculate_risk(
        cls,
        changed_files: List[Dict[str, Any]],
        changed_symbols: List[str],
        direct_impact: List[Dict[str, Any]],
        transitive_impact: List[Dict[str, Any]],
        affected_modules: List[str],
        affected_tests: List[Dict[str, Any]],
        historical_signals: List[Dict[str, Any]],
        sync_status: str = "SYNCED",
    ) -> RiskAssessment:
        factors: List[RiskFactor] = []
        evidence_list: List[str] = []

        # ----------------------------------------------------------------------
        # 1. Change Volume (Max 20.0)
        # ----------------------------------------------------------------------
        file_count = len(changed_files)
        total_additions = sum(int(f.get("additions", 0) or 0) for f in changed_files)
        total_deletions = sum(int(f.get("deletions", 0) or 0) for f in changed_files)
        total_lines = total_additions + total_deletions

        file_score = min(10.0, file_count * 2.0)
        line_score = min(10.0, total_lines / 50.0)
        vol_score = round(file_score + line_score, 2)

        vol_details = f"{file_count} changed files (+{total_additions}, -{total_deletions} lines)"
        factors.append(RiskFactor(
            name="Change Volume",
            weight=20.0,
            score=vol_score,
            details=vol_details,
            evidence_keys=[f.get("filename") or f.get("file_path", "") for f in changed_files[:5]],
        ))
        evidence_list.append(f"Volume: {vol_details} contributed {vol_score}/20.0 pts")

        # ----------------------------------------------------------------------
        # 2. Symbol Mutation Scope (Max 20.0)
        # ----------------------------------------------------------------------
        sym_count = len(changed_symbols)
        sym_score = round(min(20.0, sym_count * 3.0), 2)
        sym_details = f"{sym_count} AST symbols directly modified"
        factors.append(RiskFactor(
            name="Symbol Mutation Scope",
            weight=20.0,
            score=sym_score,
            details=sym_details,
            evidence_keys=changed_symbols[:5],
        ))
        evidence_list.append(f"Symbols: {sym_details} contributed {sym_score}/20.0 pts")

        # ----------------------------------------------------------------------
        # 3. Blast Radius & Graph Depth (Max 25.0)
        # ----------------------------------------------------------------------
        max_depth = 0
        all_nodes = direct_impact + transitive_impact
        if all_nodes:
            max_depth = max(int(n.get("depth", 1)) for n in all_nodes)

        transitive_count = len(transitive_impact)
        depth_score = min(12.0, max_depth * 4.0) if max_depth > 0 else 0.0
        dependents_score = min(13.0, transitive_count * 2.5)
        blast_score = round(depth_score + dependents_score, 2)

        blast_details = (
            f"Graph depth {max_depth} hops with {len(direct_impact)} direct and "
            f"{transitive_count} transitive dependent symbols"
        )
        factors.append(RiskFactor(
            name="Blast Radius & Graph Depth",
            weight=25.0,
            score=blast_score,
            details=blast_details,
            evidence_keys=[n.get("source_symbol", "") for n in all_nodes[:5]],
        ))
        evidence_list.append(f"Blast Radius: {blast_details} contributed {blast_score}/25.0 pts")

        # ----------------------------------------------------------------------
        # 4. Architectural Boundary Crossings (Max 15.0)
        # ----------------------------------------------------------------------
        module_count = len(affected_modules)
        boundary_score = round(min(15.0, max(0, module_count - 1) * 7.5), 2)
        boundary_details = (
            f"{module_count} distinct architectural areas affected: {', '.join(affected_modules)}"
            if module_count > 0 else "Single architectural area"
        )
        factors.append(RiskFactor(
            name="Architectural Boundaries",
            weight=15.0,
            score=boundary_score,
            details=boundary_details,
            evidence_keys=affected_modules,
        ))
        evidence_list.append(f"Boundaries: {boundary_details} contributed {boundary_score}/15.0 pts")

        # ----------------------------------------------------------------------
        # 5. Test Coverage & Verification Gap (Max 10.0)
        # ----------------------------------------------------------------------
        has_high_conf = any(t.get("confidence") == "HIGH" for t in affected_tests)
        has_med_conf = any(t.get("confidence") == "MEDIUM" for t in affected_tests)
        has_low_conf = any(t.get("confidence") == "LOW" for t in affected_tests)

        if has_high_conf:
            test_score = 0.0
            test_details = f"{len(affected_tests)} tests identified with HIGH confidence symbol references"
        elif has_med_conf:
            test_score = 3.0
            test_details = f"{len(affected_tests)} tests identified with MEDIUM confidence structural relationships"
        elif has_low_conf:
            test_score = 6.0
            test_details = f"{len(affected_tests)} tests identified via heuristic module-level suggestions (LOW confidence)"
        else:
            test_score = 10.0
            test_details = "No verified unit or integration tests identified for modified components"

        factors.append(RiskFactor(
            name="Test Verification Gap",
            weight=10.0,
            score=test_score,
            details=test_details,
            evidence_keys=[t.get("test_name", "") for t in affected_tests[:5]],
        ))
        evidence_list.append(f"Tests: {test_details} contributed {test_score}/10.0 pts")

        # ----------------------------------------------------------------------
        # 6. File Deletion / Interface Shift (Max 10.0)
        # ----------------------------------------------------------------------
        removed_count = sum(1 for f in changed_files if (f.get("status") or "").lower() == "removed")
        delete_score = round(min(10.0, removed_count * 5.0), 2)
        delete_details = f"{removed_count} files removed from repository" if removed_count > 0 else "No file deletions detected"

        factors.append(RiskFactor(
            name="File Deletion / Breaking Changes",
            weight=10.0,
            score=delete_score,
            details=delete_details,
            evidence_keys=[f.get("filename") or f.get("file_path", "") for f in changed_files if (f.get("status") or "").lower() == "removed"],
        ))
        if removed_count > 0:
            evidence_list.append(f"Deletions: {delete_details} contributed {delete_score}/10.0 pts")

        # ----------------------------------------------------------------------
        # Total Deterministic Score Calculation
        # ----------------------------------------------------------------------
        raw_total = sum(f.score for f in factors)
        total_score = round(max(0.0, min(100.0, raw_total)), 1)

        # Map to Risk Level
        if total_score < 25.0:
            risk_level = "LOW"
        elif total_score < 50.0:
            risk_level = "MEDIUM"
        elif total_score < 75.0:
            risk_level = "HIGH"
        else:
            risk_level = "CRITICAL"

        # ----------------------------------------------------------------------
        # Confidence Derivation
        # ----------------------------------------------------------------------
        confidence, confidence_reason = cls._derive_confidence(
            changed_symbols=changed_symbols,
            all_graph_nodes=all_nodes,
            affected_tests=affected_tests,
            sync_status=sync_status,
        )

        return RiskAssessment(
            risk_level=risk_level,
            risk_score=total_score,
            confidence=confidence,
            confidence_reason=confidence_reason,
            risk_factors=factors,
            evidence=evidence_list,
        )

    @staticmethod
    def _derive_confidence(
        changed_symbols: List[str],
        all_graph_nodes: List[Dict[str, Any]],
        affected_tests: List[Dict[str, Any]],
        sync_status: str,
    ) -> Tuple[str, str]:
        """
        Calculates explicit confidence based strictly on evidence availability.
        """
        has_symbols = len(changed_symbols) > 0
        has_graph = len(all_graph_nodes) > 0
        has_high_test = any(t.get("confidence") == "HIGH" for t in affected_tests)
        has_med_test = any(t.get("confidence") == "MEDIUM" for t in affected_tests)
        is_synced = sync_status.upper() == "SYNCED"

        if has_symbols and (has_graph or has_high_test) and is_synced and (has_high_test or has_med_test):
            return "HIGH", "Authoritative AST symbols, dependency evidence, verified test evidence, and synchronous repository state are all confirmed."
        elif has_symbols and (has_graph or has_high_test or has_med_test):
            return "MEDIUM", "Static AST symbols and dependency or test evidence exist; however, full multi-hop verification or synchronization state requires attention."
        else:
            return "LOW", "Limited static analysis context available; relying primarily on unified diff file metrics or heuristic suggestions."
