import re
import logging
from typing import List, Dict, Any, Optional
from uuid import UUID

from app.domain.models import (
    ReliabilityStatus,
    EvidenceStatus,
    ReliabilityCategory,
    FindingSeverity,
    ReliabilityFinding,
    ReliabilityAssessment,
)

logger = logging.getLogger(__name__)


class ReliabilityIntelligenceEngine:
    """
    Deterministic static reliability analysis engine for pull requests.
    Identifies concrete failure risks, unhandled exceptions, missing timeouts,
    resource leaks, and historical instability patterns.
    """

    _MULTILINE_RULES = {
        "REL_SWALLOWED_EXCEPTION_PY",
        "REL_UNBOUNDED_RETRY",
        "REL_RESOURCE_LEAK_OPEN",
    }

    _RELIABILITY_RULES = [
        {
            "rule_id": "REL_SWALLOWED_EXCEPTION_PY",
            "category": ReliabilityCategory.ERROR_HANDLING.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Swallowed Exception Without Logging or Propagation",
            "pattern": re.compile(r"""(?i)except(?:\s+[A-Za-z0-9_.]+|\s*)\s*:\s*(?:\n\s*)?(?:pass|\.\.\.)"""),
            "description": "Silent catch-all exception block drops exceptions without logging or rethrowing, hiding system failures.",
            "remediation": "Log the exception with traceback or re-raise/wrap into a domain exception.",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_SWALLOWED_EXCEPTION_JAVA_JS",
            "category": ReliabilityCategory.ERROR_HANDLING.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Empty Catch Block Swallowing Error",
            "pattern": re.compile(r"""catch\s*\([^)]+\)\s*\{\s*(?://[^\n]*\n\s*)?\}"""),
            "description": "Empty catch block silently ignores runtime failures, making debugging and error propagation impossible.",
            "remediation": "Log error at ERROR/WARN level or rethrow appropriately.",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_MISSING_HTTP_TIMEOUT_PY",
            "category": ReliabilityCategory.TIMEOUT.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Outbound HTTP Call Missing Explicit Timeout",
            "pattern": re.compile(r"""\b(?:requests|http|httpx|urllib3)\.(?:get|post|put|delete|patch)\((?![^)]*timeout\s*=)[^)]*\)"""),
            "description": "Outbound HTTP request without explicit timeout can hang indefinitely on network stalls, exhausting worker threads.",
            "remediation": "Specify a bounded timeout parameter (e.g. timeout=5.0 or (connect=3.0, read=10.0)).",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_UNBOUNDED_RETRY",
            "category": ReliabilityCategory.RETRY.value,
            "severity": FindingSeverity.MEDIUM.value,
            "confidence": "MEDIUM",
            "title": "Unbounded Retry Loop Without Backoff",
            "pattern": re.compile(r"""while\s+True\s*:\s*(?![\s\S]{1,300}?\b(?:break|return)\b)[\s\S]{1,300}?(?:try|requests|connect)[\s\S]{1,300}?(?:except|catch)[\s\S]{1,200}?(?:continue|pass)"""),
            "description": "Infinite retry loop without jitter/backoff or maximum retry limit can cause cascading failure and self-inflicted DoS.",
            "remediation": "Implement exponential backoff with jitter and a strict maximum attempt count (e.g. max_retries=3).",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_CONCURRENCY_UNSAFE_DATE_FORMAT",
            "category": ReliabilityCategory.CONCURRENCY.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Thread-Unsafe Mutable Static Instance",
            "pattern": re.compile(r"""(?:private\s+|public\s+|protected\s+)?static\s+(?:final\s+)?SimpleDateFormat\b"""),
            "description": "SimpleDateFormat is not thread-safe; sharing it across threads causes silent date corruption under concurrency.",
            "remediation": "Use thread-safe java.time.format.DateTimeFormatter or ThreadLocal instances.",
            "is_blocker": True,
        },
        {
            "rule_id": "REL_RESOURCE_LEAK_OPEN",
            "category": ReliabilityCategory.RESOURCE_LEAK.value,
            "severity": FindingSeverity.MEDIUM.value,
            "confidence": "MEDIUM",
            "detection_status": "SUSPICIOUS",
            "title": "File Resource Opened Without Context Manager",
            "pattern": re.compile(r"""(?<!with\s)[a-zA-Z0-9_]+\s*=\s*open\([^)]+\)(?![\s\S]*?\bclose\(\))"""),
            "description": "Opening file handles without 'with' context manager or guaranteed close risks file descriptor leakage.",
            "remediation": "Use 'with open(...) as f:' context manager to guarantee deterministic closure.",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_UNSAFE_OPTIONAL_DEREF",
            "category": ReliabilityCategory.NULLABILITY.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "detection_status": "LIKELY",
            "title": "Unchecked Optional Dereference Pattern",
            "pattern": re.compile(r"""(?<!queue\.)(?<!_cv\.)(?<!isPresent\(\)\s&&\s)(?<!isPresent\(\)\s\?\s)\b(?:opt|optional|[a-zA-Z0-9_]*Opt|[a-zA-Z0-9_]*Optional)\.get\(\)"""),
            "description": "Calling .get() directly on an Optional or container without verifying presence triggers runtime NoSuchElementException if value is absent.",
            "remediation": "Use .orElse(...), .orElseGet(...), or check .isPresent() before calling .get().",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_SWALLOWED_PROMISE_REJECTION",
            "category": ReliabilityCategory.ERROR_HANDLING.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "detection_status": "DETECTED",
            "title": "Swallowed Promise Rejection Handler",
            "pattern": re.compile(r"""\.catch\(\s*(?:\([^)]*\)|[a-zA-Z0-9_]+)?\s*=>\s*\{\s*\}\)"""),
            "description": "Empty promise .catch() callback silently swallows asynchronous exceptions and rejects error propagation.",
            "remediation": "Log the rejected error or rethrow/handle it gracefully.",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_GENERIC_EXCEPTION_THROW",
            "category": ReliabilityCategory.ERROR_HANDLING.value,
            "severity": FindingSeverity.MEDIUM.value,
            "confidence": "HIGH",
            "detection_status": "REQUIRES REVIEW",
            "title": "Broad Generic Exception Thrown",
            "pattern": re.compile(r"""(?:throw\s+new\s+(?:Exception|Throwable)\s*\(|raise\s+Exception\s*\()"""),
            "description": "Throwing raw generic Exception obscures specific failure modes and forces callers into overly broad catch clauses.",
            "remediation": "Throw a domain-specific or standard typed exception (e.g. IllegalArgumentException, ValueError).",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_MISSING_HTTP_TIMEOUT_JAVA",
            "category": ReliabilityCategory.TIMEOUT.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "detection_status": "DETECTED",
            "title": "Outbound Java HTTP Connection Without Explicit Timeout",
            "pattern": re.compile(r"""(?:HttpURLConnection\s+[a-zA-Z0-9_]+\s*=\s*\(HttpURLConnection\)|new\s+RestTemplate\(\)|HttpClient\.newHttpClient\(\))"""),
            "description": "Default HTTP clients in Java lack connect/read timeouts, which can stall threads indefinitely on slow responses.",
            "remediation": "Configure explicit connectTimeout and readTimeout on client instances or RequestConfig.",
            "is_blocker": False,
        },
        {
            "rule_id": "REL_DANGEROUS_INPUT_DESERIALIZATION",
            "category": ReliabilityCategory.FAILURE_RISK.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "detection_status": "DETECTED",
            "title": "Dangerous Unvalidated Object Deserialization",
            "pattern": re.compile(r"""(?:new\s+ObjectInputStream\s*\(|pickle\.loads?\s*\()"""),
            "description": "Direct deserialization of raw byte streams without type filtering can trigger remote code execution or process crashes.",
            "remediation": "Use safe serialization formats (JSON, Protobuf) or enforce strict LookAheadObjectInputStream filtering.",
            "is_blocker": True,
        },
        {
            "rule_id": "REL_UNCLOSED_RESOURCE_JDBC",
            "category": ReliabilityCategory.RESOURCE_LEAK.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "detection_status": "SUSPICIOUS",
            "title": "Database Statement Created Without Resource Management",
            "pattern": re.compile(r"""(?:Statement|PreparedStatement|ResultSet)\s+[a-zA-Z0-9_]+\s*=\s*[a-zA-Z0-9_]+\.(?:createStatement|prepareStatement|executeQuery)"""),
            "description": "JDBC resources allocated outside try-with-resources risk connection pool exhaustion and memory leaks.",
            "remediation": "Wrap JDBC Statement, Connection, and ResultSet declarations in try-with-resources blocks.",
            "is_blocker": False,
        },
    ]

    @classmethod
    def analyze_reliability(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int = 0,
        commit_sha: str = "",
        files: Optional[List[Dict[str, Any]]] = None,
        changed_files: Optional[List[Dict[str, Any]]] = None,
        changed_symbols: Optional[List[str]] = None,
        retrieved_chunks: Optional[List[Dict[str, Any]]] = None,
        historical_signals: Optional[List[Dict[str, Any]]] = None,
        runtime_test_runs: Optional[List[Dict[str, Any]]] = None,
        runtime_trace_available: bool = False,
        **kwargs,
    ) -> ReliabilityAssessment:
        """
        Executes deterministic reliability scanning on pull request changes.
        """
        findings: List[ReliabilityFinding] = []
        historical_signals = historical_signals or []
        target_files = changed_files if changed_files is not None else (files or [])

        for f in target_files:
            file_path = f.get("filename") or f.get("file_path") or ""
            patch = f.get("patch") or ""
            content = f.get("content") or ""
            combined_text = f"{patch}\n{content}"

            if not combined_text.strip():
                continue

            lines = combined_text.splitlines()

            for rule in cls._RELIABILITY_RULES:
                detection_status = rule.get("detection_status", "DETECTED")
                if rule["rule_id"] in cls._MULTILINE_RULES:
                    m = rule["pattern"].search(combined_text)
                    if m:
                        line_idx = combined_text[:m.start()].count("\n") + 1
                        matched_snippet = m.group(0).strip().replace("\n", " ")[:120]
                        findings.append(
                            ReliabilityFinding(
                                organization_id=organization_id,
                                repository_id=repository_id,
                                pr_number=pr_number,
                                commit_sha=commit_sha,
                                category=rule["category"],
                                rule_id=rule["rule_id"],
                                severity=rule["severity"],
                                confidence=rule["confidence"],
                                title=rule["title"],
                                description=rule["description"],
                                file_path=file_path,
                                line_number=line_idx,
                                evidence=f"Line {line_idx}: [{detection_status}] {matched_snippet}",
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                                metadata={"detection_status": detection_status},
                            )
                        )
                else:
                    for line_idx, line in enumerate(lines, start=1):
                        if patch and not (line.startswith("+") or line.startswith(" ")):
                            continue
                        if rule["pattern"].search(line):
                            findings.append(
                                ReliabilityFinding(
                                    organization_id=organization_id,
                                    repository_id=repository_id,
                                    pr_number=pr_number,
                                    commit_sha=commit_sha,
                                    category=rule["category"],
                                    rule_id=rule["rule_id"],
                                    severity=rule["severity"],
                                    confidence=rule["confidence"],
                                    title=rule["title"],
                                    description=rule["description"],
                                    file_path=file_path,
                                    line_number=line_idx,
                                    evidence=f"Line {line_idx}: [{detection_status}] {line.strip()[:160]}",
                                    remediation=rule["remediation"],
                                    is_blocker=rule["is_blocker"],
                                    metadata={"detection_status": detection_status},
                                )
                            )


        # Historical Reliability Signals Integration
        for sig in historical_signals:
            commit_count = sig.get("churn_count") or sig.get("recent_modifications_count") or sig.get("commit_count", 0)
            file_p = sig.get("file_path", "")
            # High churn hotspot file (>= 10 commits) modified again
            if commit_count >= 10:
                findings.append(
                    ReliabilityFinding(
                        organization_id=organization_id,
                        repository_id=repository_id,
                        pr_number=pr_number,
                        commit_sha=commit_sha,
                        category=ReliabilityCategory.HISTORICAL_INSTABILITY.value,
                        rule_id="REL_HISTORICAL_CHURN_HOTSPOT",
                        severity=FindingSeverity.MEDIUM.value,
                        confidence="HIGH",
                        title="High-Churn Historical Hotspot Modified",
                        description=f"File '{file_p}' has been modified {commit_count} times previously. Historical churn hotspots exhibit higher regression rates.",
                        file_path=file_p,
                        line_number=None,
                        evidence=f"Observed commit count: {commit_count} prior modifications",
                        remediation="Ensure thorough automated test coverage for changes in this frequently modified module.",
                        is_blocker=False,
                    )
                )

        # Evidence Availability State
        evidence_status = EvidenceStatus.AVAILABLE.value if runtime_trace_available else EvidenceStatus.PARTIAL.value

        # Calculate counts
        critical_count = sum(1 for f in findings if f.severity == FindingSeverity.CRITICAL.value)
        high_count = sum(1 for f in findings if f.severity == FindingSeverity.HIGH.value)
        medium_count = sum(1 for f in findings if f.severity == FindingSeverity.MEDIUM.value)
        low_count = sum(1 for f in findings if f.severity == FindingSeverity.LOW.value)

        # Reliability Risk Score (0 - 100)
        # CRITICAL = +35 pts
        # HIGH = +20 pts
        # MEDIUM = +10 pts
        # LOW-confidence = +0 pts
        rel_score = 0.0
        for f in findings:
            if f.confidence == "LOW":
                continue
            if f.severity == FindingSeverity.CRITICAL.value:
                rel_score += 35.0
            elif f.severity == FindingSeverity.HIGH.value:
                rel_score += 20.0
            elif f.severity == FindingSeverity.MEDIUM.value:
                rel_score += 10.0
        rel_score = min(100.0, rel_score)

        # Reliability Status
        if critical_count > 0 or any(f.is_blocker for f in findings):
            rel_status = ReliabilityStatus.FAILED.value
        elif high_count > 0 or medium_count > 0:
            rel_status = ReliabilityStatus.WARNING.value
        else:
            rel_status = ReliabilityStatus.PASSED.value

        summary = (
            f"Deterministic reliability scan completed. Status: {rel_status}. "
            f"Findings: {critical_count} CRITICAL, {high_count} HIGH, {medium_count} MEDIUM, {low_count} LOW. "
            f"Evidence status: {evidence_status}."
        )

        return ReliabilityAssessment(
            reliability_status=rel_status,
            evidence_status=evidence_status,
            reliability_risk_score=rel_score,
            critical_count=critical_count,
            high_count=high_count,
            medium_count=medium_count,
            low_count=low_count,
            findings=findings,
            summary=summary,
        )

    @classmethod
    def scan_content_for_reliability_rules(cls, content: str, file_path: str = "patch") -> List[ReliabilityFinding]:
        findings = []
        lines = content.splitlines()
        # Single-line rules
        for rule in cls._RELIABILITY_RULES:
            detection_status = rule.get("detection_status", "DETECTED")
            if rule["rule_id"] in cls._MULTILINE_RULES:
                continue
            for line_idx, line in enumerate(lines, start=1):
                if rule["pattern"].search(line):
                    findings.append(
                        ReliabilityFinding(
                            category=rule["category"],
                            rule_id=rule["rule_id"],
                            severity=rule["severity"],
                            confidence=rule["confidence"],
                            title=rule["title"],
                            description=rule["description"],
                            file_path=file_path,
                            line_number=line_idx,
                            evidence=f"Line {line_idx}: [{detection_status}] {line.strip()[:160]}",
                            remediation=rule["remediation"],
                            is_blocker=rule["is_blocker"],
                            metadata={"detection_status": detection_status},
                        )
                    )
        # Multi-line rules
        for rule in cls._RELIABILITY_RULES:
            detection_status = rule.get("detection_status", "DETECTED")
            if rule["rule_id"] in cls._MULTILINE_RULES:
                if rule["pattern"].search(content):
                    findings.append(
                        ReliabilityFinding(
                            category=rule["category"],
                            rule_id=rule["rule_id"],
                            severity=rule["severity"],
                            confidence=rule["confidence"],
                            title=rule["title"],
                            description=rule["description"],
                            file_path=file_path,
                            line_number=1,
                            evidence=f"Rule {rule['rule_id']} [{detection_status}] detected in {file_path}",
                            remediation=rule["remediation"],
                            is_blocker=rule["is_blocker"],
                            metadata={"detection_status": detection_status},
                        )
                    )

        return findings

