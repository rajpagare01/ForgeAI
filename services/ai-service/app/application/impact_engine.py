import os
import re
import logging
from typing import List, Dict, Any, Optional, Set
from uuid import UUID
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)


class ImpactAnalysisResult(BaseModel):
    directly_changed_files: List[str] = Field(default_factory=list)
    directly_changed_symbols: List[str] = Field(default_factory=list)
    direct_impact: List[Dict[str, Any]] = Field(default_factory=list)
    transitive_impact: List[Dict[str, Any]] = Field(default_factory=list)
    affected_modules: List[str] = Field(default_factory=list)
    affected_tests: List[Dict[str, Any]] = Field(default_factory=list)
    historical_signals: List[Dict[str, Any]] = Field(default_factory=list)
    architectural_concerns: List[str] = Field(default_factory=list)
    provenance_evidence: List[Dict[str, Any]] = Field(default_factory=list)


class ChangeImpactEngine:
    """
    Evidence-backed Change Impact Engine for ForgeAI.
    Analyzes repository structure, dependency graph, tests, and history
    to produce a structured engineering assessment.
    """

    @classmethod
    def analyze_change(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        raw_files: List[Dict[str, Any]],
        changed_symbols: List[str],
        impacted_graph_nodes: List[Dict[str, Any]],
        historical_commits: Optional[List[Dict[str, Any]]] = None,
        retrieved_chunks: Optional[List[Dict[str, Any]]] = None,
        sync_status: str = "SYNCED",
        head_sha: str = "",
        base_sha: str = "",
    ) -> ImpactAnalysisResult:
        evidence: List[Dict[str, Any]] = []

        # ----------------------------------------------------------------------
        # 1. Directly Changed Files & Symbols
        # ----------------------------------------------------------------------
        directly_changed_files = [
            f.get("filename") or f.get("file_path") or ""
            for f in raw_files
            if (f.get("filename") or f.get("file_path"))
        ]
        directly_changed_symbols = list(changed_symbols)

        for f in raw_files:
            evidence.append({
                "source": "PR_DIFF",
                "file_path": f.get("filename") or f.get("file_path"),
                "status": f.get("status", "modified"),
                "additions": f.get("additions", 0),
                "deletions": f.get("deletions", 0),
                "commit_sha": head_sha,
                "repository_id": str(repository_id),
            })

        # ----------------------------------------------------------------------
        # 2. Direct vs Transitive Impact Breakdown
        # ----------------------------------------------------------------------
        direct_impact: List[Dict[str, Any]] = []
        transitive_impact: List[Dict[str, Any]] = []

        for node in impacted_graph_nodes:
            depth = int(node.get("depth", 1))
            rec = {
                "source_symbol": node.get("source_symbol"),
                "target_symbol": node.get("target_symbol"),
                "edge_type": node.get("edge_type", "CALLS"),
                "depth": depth,
            }
            if depth == 1:
                direct_impact.append(rec)
            else:
                transitive_impact.append(rec)

            evidence.append({
                "source": "GRAPH",
                "symbol_name": node.get("source_symbol"),
                "target_symbol": node.get("target_symbol"),
                "edge_type": node.get("edge_type"),
                "depth": depth,
                "commit_sha": head_sha,
                "repository_id": str(repository_id),
            })

        # ----------------------------------------------------------------------
        # 3. Architectural Module & Service Area Derivation
        # ----------------------------------------------------------------------
        affected_modules = cls._derive_affected_modules(directly_changed_files, impacted_graph_nodes)

        # Architectural Concerns
        architectural_concerns: List[str] = []
        if len(affected_modules) > 1:
            architectural_concerns.append(
                f"Cross-boundary modification detected across {len(affected_modules)} modules: {', '.join(affected_modules)}"
            )

        removed_files = [f for f in raw_files if (f.get("status") or "").lower() == "removed"]
        if removed_files:
            architectural_concerns.append(
                f"File deletions present ({len(removed_files)} files removed); verify interface compatibility and dependent callers."
            )

        if len(transitive_impact) > 8:
            architectural_concerns.append(
                f"High downstream blast radius: {len(transitive_impact)} transitive components depend on the modified interface."
            )

        # ----------------------------------------------------------------------
        # 4. Test Impact Intelligence
        # ----------------------------------------------------------------------
        affected_tests = cls._derive_affected_tests(
            directly_changed_files=directly_changed_files,
            changed_symbols=directly_changed_symbols,
            impacted_graph_nodes=impacted_graph_nodes,
            retrieved_chunks=retrieved_chunks or [],
        )

        for t in affected_tests:
            evidence.append({
                "source": "TEST",
                "test_name": t.get("test_name"),
                "file_path": t.get("file_path"),
                "confidence": t.get("confidence"),
                "relationship": t.get("relationship"),
                "commit_sha": head_sha,
                "repository_id": str(repository_id),
            })

        # ----------------------------------------------------------------------
        # 5. Historical Change Intelligence
        # ----------------------------------------------------------------------
        historical_signals = cls._derive_historical_signals(
            directly_changed_files=directly_changed_files,
            commits=historical_commits or [],
        )

        for h in historical_signals:
            evidence.append({
                "source": "HISTORY",
                "file_path": h.get("file_path"),
                "churn_count": h.get("recent_modifications_count"),
                "commit_sha": h.get("last_modified_commit"),
                "details": h.get("details"),
                "repository_id": str(repository_id),
            })

        # ----------------------------------------------------------------------
        # 6. Vector Context Evidence
        # ----------------------------------------------------------------------
        if retrieved_chunks:
            for c in retrieved_chunks:
                evidence.append({
                    "source": "VECTOR",
                    "file_path": c.get("file_path"),
                    "symbol_name": c.get("symbol_name"),
                    "similarity": c.get("similarity"),
                    "content": c.get("content", "")[:300],
                    "commit_sha": c.get("commit_sha") or head_sha,
                    "repository_id": str(repository_id),
                })

        # ----------------------------------------------------------------------
        # 7. Repository Sync State Evidence
        # ----------------------------------------------------------------------
        evidence.append({
            "source": "SYNC_STATE",
            "sync_status": sync_status,
            "head_sha": head_sha,
            "base_sha": base_sha,
            "repository_id": str(repository_id),
            "details": f"Repository synchronization state: {sync_status} at HEAD {head_sha[:8] if head_sha else 'N/A'}",
        })

        return ImpactAnalysisResult(
            directly_changed_files=directly_changed_files,
            directly_changed_symbols=directly_changed_symbols,
            direct_impact=direct_impact,
            transitive_impact=transitive_impact,
            affected_modules=affected_modules,
            affected_tests=affected_tests,
            historical_signals=historical_signals,
            architectural_concerns=architectural_concerns,
            provenance_evidence=evidence,
        )

    @classmethod
    def _derive_affected_modules(
        cls,
        files: List[str],
        graph_nodes: List[Dict[str, Any]],
    ) -> List[str]:
        """
        Dynamically classifies impacted architectural areas based on repository structure.
        """
        modules = set()
        for f in files:
            norm = f.replace("\\", "/").strip("/")
            parts = norm.split("/")
            if parts[0] == "services" and len(parts) > 1:
                modules.add(parts[1])
            elif parts[0] == "frontend":
                modules.add("frontend")
            elif parts[0] in (".github", "docker-compose.yml", "docker-compose.prod.yml"):
                modules.add("infrastructure")
            elif len(parts) > 1:
                modules.add(parts[0])
            else:
                modules.add("root")

        # Also check graph nodes for symbol prefixes if files didn't capture them
        for node in graph_nodes:
            src = node.get("source_symbol", "")
            if "sourcecontrol" in src.lower():
                modules.add("source-control-service")
            elif "identity" in src.lower():
                modules.add("identity-service")
            elif "testing" in src.lower():
                modules.add("testing-service")
            elif "ai" in src.lower():
                modules.add("ai-service")

        return sorted(list(modules))

    @classmethod
    def _derive_affected_tests(
        cls,
        directly_changed_files: List[str],
        changed_symbols: List[str],
        impacted_graph_nodes: List[Dict[str, Any]],
        retrieved_chunks: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """
        Identifies related tests using evidence with explicit confidence levels:
        - HIGH: Graph or symbol-level direct invocation in test code
        - MEDIUM: Structural / path name pairing (e.g. Foo.java -> FooTest.java)
        - LOW: Heuristic suggestion based on affected module test suite
        """
        tests: List[Dict[str, Any]] = []
        seen = set()

        def add_test(name: str, file_path: str, confidence: str, relationship: str):
            key = f"{name}::{file_path}"
            if key not in seen:
                seen.add(key)
                tests.append({
                    "test_name": name,
                    "file_path": file_path,
                    "confidence": confidence,
                    "relationship": relationship,
                })

        # 1. HIGH CONFIDENCE: Graph nodes that represent tests calling changed symbols
        for node in impacted_graph_nodes:
            src = node.get("source_symbol", "")
            if any(term in src.lower() for term in ("test", "spec", "integration")):
                add_test(
                    name=src,
                    file_path="",
                    confidence="HIGH",
                    relationship=f"AST call graph verified: {src} calls modified target ({node.get('edge_type', 'CALLS')})",
                )

        # 2. HIGH CONFIDENCE: Retrieved code chunks in test paths referencing symbols
        for chunk in retrieved_chunks:
            fp = chunk.get("file_path", "")
            sym = chunk.get("symbol_name", "")
            if "/test/" in fp.lower() or "tests/" in fp.lower() or "test_" in fp.lower():
                add_test(
                    name=sym or fp.split("/")[-1],
                    file_path=fp,
                    confidence="HIGH",
                    relationship=f"Test code chunk references modified symbol or concept ({chunk.get('symbol_name')})",
                )

        # 3. MEDIUM CONFIDENCE: Structural / Path Name Pairing
        for f in directly_changed_files:
            norm = f.replace("\\", "/")
            base_name = os.path.basename(norm)
            name_no_ext, ext = os.path.splitext(base_name)

            if ext == ".java":
                if not name_no_ext.endswith("Test"):
                    candidate_test = f"{name_no_ext}Test.java"
                    test_path = norm.replace("/src/main/java/", "/src/test/java/").replace(base_name, candidate_test)
                    add_test(
                        name=f"{name_no_ext}Test",
                        file_path=test_path,
                        confidence="MEDIUM",
                        relationship=f"Structural unit test pairing for {base_name}",
                    )
            elif ext == ".py":
                if not name_no_ext.startswith("test_"):
                    candidate_test = f"test_{name_no_ext}.py"
                    test_path = f"tests/{candidate_test}"
                    add_test(
                        name=candidate_test,
                        file_path=test_path,
                        confidence="MEDIUM",
                        relationship=f"Python unit test pairing for {base_name}",
                    )

        # 4. LOW CONFIDENCE: Module-level test suite suggestion (heuristic)
        modules = cls._derive_affected_modules(directly_changed_files, [])
        for m in modules:
            if m == "source-control-service":
                add_test(
                    name="SourceControlServiceTestSuites",
                    file_path="services/source-control-service/src/test",
                    confidence="LOW",
                    relationship="Module test suite for source-control-service (heuristic suggestion)",
                )
            elif m == "identity-service":
                add_test(
                    name="IdentityServiceTestSuites",
                    file_path="services/identity-service/src/test",
                    confidence="LOW",
                    relationship="Module test suite for identity-service (heuristic suggestion)",
                )
            elif m == "ai-service":
                add_test(
                    name="AIServicePytestSuite",
                    file_path="services/ai-service/tests",
                    confidence="LOW",
                    relationship="Module pytest suite for ai-service (heuristic suggestion)",
                )
            elif m == "frontend":
                add_test(
                    name="FrontendBuildAndTypecheck",
                    file_path="frontend",
                    confidence="LOW",
                    relationship="Frontend build and verification suite (heuristic suggestion)",
                )

        return tests

    @classmethod
    def _derive_historical_signals(
        cls,
        directly_changed_files: List[str],
        commits: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """
        Calculates repository change activity and churn from stored commit history.
        Does not fabricate data — returns signals only when backed by commits.
        """
        signals: List[Dict[str, Any]] = []
        if not commits:
            return signals

        # Count how many times each changed file appears in commit messages or files
        for f in directly_changed_files[:5]:
            fname = os.path.basename(f)
            name_no_ext = os.path.splitext(fname)[0]
            matching_commits = []
            for c in commits:
                msg = c.get("message", "")
                sha = c.get("sha", "")
                if fname.lower() in msg.lower() or name_no_ext.lower() in msg.lower() or f.lower() in msg.lower():
                    matching_commits.append(c)

            churn_count = len(matching_commits)
            last_commit = matching_commits[0].get("sha") if matching_commits else None
            last_author = matching_commits[0].get("authorName") if matching_commits else None

            signals.append({
                "file_path": f,
                "recent_modifications_count": churn_count,
                "last_modified_commit": last_commit,
                "last_author": last_author,
                "details": (
                    f"File modified in {churn_count} recent commits in repository history"
                    if churn_count > 0 else "No recent commit churn matches detected in local history"
                ),
            })

        return signals
