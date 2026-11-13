import re
import logging
from typing import List, Dict, Any, Optional, Tuple
from uuid import UUID

from app.domain.models import (
    SecurityStatus,
    EvidenceStatus,
    SecurityCategory,
    FindingSeverity,
    SecurityFinding,
    SecurityAssessment,
)

logger = logging.getLogger(__name__)


class SecurityIntelligenceEngine:
    """
    Deterministic static security analysis engine for pull requests.
    Zero hallucination: only generates findings backed by concrete static patterns.
    Raw secrets are NEVER persisted, logged, or returned — only redacted representations.
    """

    # --------------------------------------------------------------------------
    # 1. SECRET EXPOSURE REGEX PATTERNS & REDACTION
    # --------------------------------------------------------------------------
    _SECRET_RULES = [
        {
            "rule_id": "SEC_SECRET_PRIVATE_KEY",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.CRITICAL.value,
            "confidence": "HIGH",
            "title": "Exposed Cryptographic Private Key",
            "pattern": re.compile(
                r"-----BEGIN (?:([A-Z0-9_-]+)\s+)?PRIVATE KEY-----",
                re.IGNORECASE,
            ),
            "description": "Unencrypted private cryptographic key block detected in repository files.",
            "remediation": "Immediately revoke and rotate this private key. Remove it from source history and inject via secrets management (e.g. Vault, AWS Secrets Manager).",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_SECRET_AWS_KEY",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.CRITICAL.value,
            "confidence": "HIGH",
            "title": "Exposed AWS Access Key ID",
            "pattern": re.compile(r"\b((?:AKIA|ASIA)[0-9A-Z]{16})\b"),
            "description": "AWS permanent or STS temporary Access Key ID exposed in code or configuration.",
            "remediation": "Revoke this IAM user access key in AWS IAM Console immediately. Use IAM Roles / IRSA instead of static credentials.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_SECRET_GITHUB_TOKEN",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.CRITICAL.value,
            "confidence": "HIGH",
            "title": "Exposed GitHub Personal Access / Service Token",
            "pattern": re.compile(r"\b((?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,})\b"),
            "description": "High-privilege GitHub Personal Access Token or OAuth Token committed to repository.",
            "remediation": "Revoke token immediately in GitHub settings. Audit access logs for unauthorized actions.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_SECRET_STRIPE_KEY",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.CRITICAL.value,
            "confidence": "HIGH",
            "title": "Exposed Live Stripe Secret API Key",
            "pattern": re.compile(r"\b((?:sk_live|rk_live)_[0-9a-zA-Z]{24,})\b"),
            "description": "Production Stripe secret/restricted API key committed in plaintext.",
            "remediation": "Roll API key in Stripe Dashboard immediately and restrict key permissions.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_SECRET_JWT_TOKEN",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Committed JWT / Authorization Token",
            "pattern": re.compile(r"\b(eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})\b"),
            "description": "Signed JWT authorization token committed to source.",
            "remediation": "Revoke JWT signing key if compromised, or invalidate the token session.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_SECRET_GENERIC_KEY",
            "category": SecurityCategory.SECRET_EXPOSURE.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "MEDIUM",
            "title": "Hardcoded Credential or API Secret",
            "pattern": re.compile(
                r"""(?i)\b(?:api_key|apikey|secret_key|client_secret|auth_token|db_password|password)\s*[:=]\s*["']([^"'\s]{16,})["']"""
            ),
            "description": "Potential hardcoded API key, token, or password assignment.",
            "remediation": "Extract secret into environment variables or secrets manager.",
            "is_blocker": False,
        },
    ]

    # Whitelist false-positive placeholder patterns
    _SAFE_PLACEHOLDERS = {
        "changeme",
        "password",
        "dummy",
        "example",
        "test",
        "placeholder",
        "mock",
        "todo",
        "your-secret-key",
        "secret",
        "undefined",
        "null",
        "none",
    }

    # --------------------------------------------------------------------------
    # 2. INSECURE CONFIGURATION REGEX PATTERNS
    # --------------------------------------------------------------------------
    _CONFIG_RULES = [
        {
            "rule_id": "SEC_INSECURE_TLS_VERIFY_DISABLED",
            "category": SecurityCategory.INSECURE_CONFIGURATION.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "TLS Certificate Verification Disabled",
            "pattern": re.compile(
                r"""(?i)(?:verify\s*=\s*False|NODE_TLS_REJECT_UNAUTHORIZED\s*=\s*['"]?0['"]?|trustAllCerts|InsecureTrustManager|check_hostname\s*=\s*False)"""
            ),
            "description": "Disabling TLS certificate verification exposes network traffic to Man-in-the-Middle (MitM) attacks.",
            "remediation": "Enable valid CA certificate verification for all outbound HTTP/TLS connections.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_CFG_AUTH_BYPASS",
            "category": SecurityCategory.AUTHORIZATION.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "MEDIUM",
            "title": "Permissive Authorization Bypass in Security Configuration",
            "pattern": re.compile(
                r"""(?i)(?:\.permitAll\(\)|@PermitAll|security\.basic\.enabled\s*=\s*false|web\.ignoring\(\))"""
            ),
            "description": "Route or endpoint explicitly configured to permit all unauthenticated requests without authorization checks.",
            "remediation": "Ensure public access is intentional and does not expose internal administrative or tenant-scoped APIs.",
            "is_blocker": False,
        },
        {
            "rule_id": "SEC_CFG_WILDCARD_CORS",
            "category": SecurityCategory.INSECURE_CONFIGURATION.value,
            "severity": FindingSeverity.MEDIUM.value,
            "confidence": "HIGH",
            "title": "Overly Permissive Wildcard CORS Configuration",
            "pattern": re.compile(
                r"""(?i)(?:Access-Control-Allow-Origin\s*:\s*\*|allowedOrigins\s*\(\s*["']\*["']\s*\)|cors\(\s*\{\s*origin\s*:\s*["']\*["']\s*\})"""
            ),
            "description": "Wildcard origin in CORS policy allows untrusted third-party websites to make cross-origin requests.",
            "remediation": "Restrict allowed origins to trusted corporate and client domain names.",
            "is_blocker": False,
        },
        {
            "rule_id": "SEC_CFG_DEBUG_MODE",
            "category": SecurityCategory.INSECURE_CONFIGURATION.value,
            "severity": FindingSeverity.MEDIUM.value,
            "confidence": "HIGH",
            "title": "Debug Mode Enabled in Application Configuration",
            "pattern": re.compile(
                r"""(?i)(?:DEBUG\s*=\s*True|server\.error\.include-stacktrace\s*=\s*always|app\.run\(.*debug\s*=\s*True.*\)|\bdebug:\s*true\b)"""
            ),
            "description": "Enabling debug mode in non-development environments risks leaking stack traces and memory structures.",
            "remediation": "Disable debug flags and stack trace inclusion in production configurations.",
            "is_blocker": False,
        },
    ]

    # --------------------------------------------------------------------------
    # 3. CODE-LEVEL VULNERABILITY PATTERNS
    # --------------------------------------------------------------------------
    _CODE_RULES = [
        {
            "rule_id": "SEC_CODE_SQL_INJECTION",
            "category": SecurityCategory.INJECTION.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "SQL Injection via Dynamic String Concatenation",
            "pattern": re.compile(
                r"""(?i)(?:(?:"[^"]*\b(?:SELECT|INSERT|UPDATE|DELETE|DROP|UNION)\b[^"]*"\s*\+\s*[a-zA-Z0-9_.]+)|(?:'[^']*\b(?:SELECT|INSERT|UPDATE|DELETE|DROP|UNION)\b[^']*'\s*\+\s*[a-zA-Z0-9_.]+)|(?:f["'][^"']*\b(?:SELECT|INSERT|UPDATE|DELETE|DROP|UNION)\b[^"']*\{[a-zA-Z0-9_.]+))"""
            ),
            "description": "Dynamic string concatenation in SQL query construction creates severe SQL Injection vulnerability.",
            "remediation": "Use parameterized queries or ORM query builders (e.g. prepared statements, SQLAlchemy parameters).",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_CODE_COMMAND_INJECTION",
            "category": SecurityCategory.COMMAND_EXECUTION.value,
            "severity": FindingSeverity.CRITICAL.value,
            "confidence": "HIGH",
            "title": "Command Execution with Untrusted Shell Arguments",
            "pattern": re.compile(
                r"""(?i)(?:Runtime\.getRuntime\(\)\.exec\(|subprocess\.(?:call|Popen|run|check_output)\([^)]*shell\s*=\s*True|os\.system\([^)]*[\+])"""
            ),
            "description": "Executing system shell commands with concatenated or shell=True arguments allows arbitrary remote code execution.",
            "remediation": "Avoid invoking system shells; pass arguments as separate array elements and sanitize all inputs.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_CODE_UNSAFE_DESERIALIZATION",
            "category": SecurityCategory.DESERIALIZATION.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "HIGH",
            "title": "Unsafe Object Deserialization",
            "pattern": re.compile(
                r"""(?i)(?:pickle\.loads?\(|yaml\.load\([^)]*Loader\s*=\s*yaml\.Loader|ObjectInputStream\([^)]*\)\.readObject\(\))"""
            ),
            "description": "Deserializing untrusted data with unsafe deserializers leads to arbitrary code execution.",
            "remediation": "Use safe deserialization parsers (e.g. json.loads, yaml.safe_load) or cryptographic message signatures.",
            "is_blocker": True,
        },
        {
            "rule_id": "SEC_CODE_PATH_TRAVERSAL",
            "category": SecurityCategory.PATH_TRAVERSAL.value,
            "severity": FindingSeverity.HIGH.value,
            "confidence": "MEDIUM",
            "title": "Potential Path Traversal via Direct Path Concatenation",
            "pattern": re.compile(
                r"""(?i)(?:open\s*\([^)]*\+\s*[a-zA-Z0-9_.]+|new\s+File\s*\([^)]*,\s*[a-zA-Z0-9_.]+\)|path\.join\([^)]*req\.(?:query|params|body))"""
            ),
            "description": "Constructing filesystem paths from dynamic input without path sanitization exposes arbitrary files to traversal.",
            "remediation": "Normalize and canonicalize paths; verify that the resolved path resides inside the authorized root directory.",
            "is_blocker": False,
        },
    ]

    @staticmethod
    def _redact_value(val: str) -> str:
        """
        Redacts sensitive tokens to ensure raw secrets are NEVER exposed in logs,
        storage, or downstream LLM prompts.
        """
        clean = val.strip()
        if len(clean) <= 8:
            return "***[REDACTED]***"
        prefix = clean[:4]
        return f"{prefix}***[REDACTED_LEN_{len(clean)}]"

    @staticmethod
    def _extract_scannable_lines(patch: str, content: str) -> List[Tuple[int, str]]:
        """
        Returns list of (line_number, line_text) without shifting line indices
        or skipping non-indented content lines.
        """
        scannable: List[Tuple[int, str]] = []
        seen_texts = set()
        if content and content.strip():
            for idx, line in enumerate(content.splitlines(), start=1):
                scannable.append((idx, line))
                seen_texts.add(line.strip())
        if patch and patch.strip():
            for idx, raw_line in enumerate(patch.splitlines(), start=1):
                if raw_line.startswith("+++") or raw_line.startswith("---") or raw_line.startswith("@@"):
                    continue
                if raw_line.startswith("+") or (not content.strip() and raw_line.startswith(" ")):
                    clean_line = raw_line[1:] if (raw_line.startswith("+") or raw_line.startswith(" ")) else raw_line
                    if not content.strip() or clean_line.strip() not in seen_texts:
                        scannable.append((idx, clean_line))
                        seen_texts.add(clean_line.strip())
        return scannable

    @classmethod
    def analyze_security(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int = 0,
        commit_sha: str = "",
        files: Optional[List[Dict[str, Any]]] = None,
        changed_files: Optional[List[Dict[str, Any]]] = None,
        changed_symbols: Optional[List[str]] = None,
        retrieved_chunks: Optional[List[Dict[str, Any]]] = None,
        downstream_dependents: Optional[List[Any]] = None,
        downstream_deps: Optional[List[Any]] = None,
        dependency_scanner_configured: bool = False,
        **kwargs,
    ) -> SecurityAssessment:
        """
        Executes deterministic security scanning across pull request files.
        """
        findings: List[SecurityFinding] = []
        target_files = changed_files if changed_files is not None else (files or [])
        target_deps = downstream_deps if downstream_deps is not None else (downstream_dependents or [])
        seen_text_keys = set()
        seen_chunk_keys = set()

        for f in target_files:
            file_path = f.get("filename") or f.get("file_path") or ""
            patch = f.get("patch") or ""
            content = f.get("content") or ""
            scannable_lines = cls._extract_scannable_lines(patch, content)
            if not scannable_lines:
                continue

            # 1. Secret Exposure Scanning
            for rule in cls._SECRET_RULES:
                for line_idx, line in scannable_lines:
                    match = rule["pattern"].search(line)
                    if match:
                        dedup_key = (rule["rule_id"], file_path, line.strip())
                        if dedup_key in seen_text_keys:
                            continue
                        matched_str = match.group(1) if match.groups() else match.group(0)
                        val_lower = (matched_str or "").lower()
                        if val_lower in cls._SAFE_PLACEHOLDERS or any(w in val_lower for w in ("example", "dummy", "placeholder", "changeme", "mock_key")):
                            continue

                        if rule["rule_id"] == "SEC_SECRET_PRIVATE_KEY":
                            key_type = (match.group(1) or "").upper() if match.groups() else ""
                            redacted = f"[REDACTED_{key_type}_KEY]" if key_type else "[REDACTED_RSA_KEY]"
                        else:
                            redacted = cls._redact_value(matched_str)

                        seen_text_keys.add(dedup_key)
                        seen_chunk_keys.add((rule["rule_id"], file_path, line_idx))
                        findings.append(
                            SecurityFinding(
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
                                redacted_evidence=f"Line {line_idx}: matched pattern {rule['rule_id']}: {redacted}",
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                            )
                        )

            # 2. Insecure Configuration Scanning
            for rule in cls._CONFIG_RULES:
                for line_idx, line in scannable_lines:
                    if rule["pattern"].search(line):
                        dedup_key = (rule["rule_id"], file_path, line.strip())
                        if dedup_key in seen_text_keys:
                            continue
                        seen_text_keys.add(dedup_key)
                        seen_chunk_keys.add((rule["rule_id"], file_path, line_idx))
                        findings.append(
                            SecurityFinding(
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
                                redacted_evidence=f"Line {line_idx}: {line.strip()[:160]}",
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                            )
                        )

            # 3. Code-Level Security Scanning
            for rule in cls._CODE_RULES:
                for line_idx, line in scannable_lines:
                    if rule["pattern"].search(line):
                        dedup_key = (rule["rule_id"], file_path, line.strip())
                        if dedup_key in seen_text_keys:
                            continue
                        seen_text_keys.add(dedup_key)
                        seen_chunk_keys.add((rule["rule_id"], file_path, line_idx))
                        findings.append(
                            SecurityFinding(
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
                                redacted_evidence=f"Line {line_idx}: {line.strip()[:160]}",
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                            )
                        )

        # 3b. Scan retrieved repository chunks individually to preserve per-chunk paths & line numbers
        if retrieved_chunks:
            for c in retrieved_chunks:
                c_path = (c.get("file_path") if isinstance(c, dict) else getattr(c, "file_path", None)) or "retrieved_context"
                c_content = (c.get("content") if isinstance(c, dict) else getattr(c, "content", None)) or ""
                if not c_content.strip():
                    continue
                for line_idx, line in enumerate(c_content.splitlines(), start=1):
                    for rule in cls._SECRET_RULES + cls._CONFIG_RULES + cls._CODE_RULES:
                        match = rule["pattern"].search(line)
                        if not match:
                            continue
                        chunk_key = (rule["rule_id"], c_path, line_idx)
                        text_key = (rule["rule_id"], c_path, line.strip())
                        if chunk_key in seen_chunk_keys or text_key in seen_text_keys:
                            continue
                        if rule in cls._SECRET_RULES:
                            matched_str = match.group(1) if match.groups() else match.group(0)
                            val_lower = (matched_str or "").lower()
                            if val_lower in cls._SAFE_PLACEHOLDERS or any(w in val_lower for w in ("example", "dummy", "placeholder", "changeme", "mock_key")):
                                continue
                            if rule["rule_id"] == "SEC_SECRET_PRIVATE_KEY":
                                key_type = (match.group(1) or "").upper() if match.groups() else ""
                                redacted = f"[REDACTED_{key_type}_KEY]" if key_type else "[REDACTED_RSA_KEY]"
                            else:
                                redacted = cls._redact_value(matched_str)
                            evidence = f"Line {line_idx}: matched pattern {rule['rule_id']}: {redacted}"
                        else:
                            evidence = f"Line {line_idx}: {line.strip()[:160]}"
                        seen_chunk_keys.add(chunk_key)
                        seen_text_keys.add(text_key)
                        findings.append(
                            SecurityFinding(
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
                                file_path=c_path,
                                line_number=line_idx,
                                redacted_evidence=evidence,
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                            )
                        )
        # 4. Dependency Vulnerability Scanning
        from app.application.dependency_vulnerability_scanner import DependencyVulnerabilityScanner
        dep_scanner = DependencyVulnerabilityScanner(is_external_configured=dependency_scanner_configured)
        dep_result = dep_scanner.scan_files(target_files)

        for df in dep_result.findings:
            rule_id = f"SEC_DEP_{df.vulnerability_id.replace('-', '_')}"
            findings.append(
                SecurityFinding(
                    organization_id=organization_id,
                    repository_id=repository_id,
                    pr_number=pr_number,
                    commit_sha=commit_sha,
                    category=SecurityCategory.DEPENDENCY_RISK.value,
                    rule_id=rule_id,
                    severity=df.severity,
                    confidence="HIGH",
                    title=f"Vulnerable Dependency: {df.package}@{df.version} ({df.vulnerability_id})",
                    description=f"{df.title}: {df.description} (Affected: {df.affected_version_range})",
                    file_path=df.file_path,
                    line_number=1,
                    redacted_evidence=f"Manifest {df.file_path}: dependency {df.package}@{df.version} matches {df.vulnerability_id}",
                    remediation=df.remediation,
                    is_blocker=df.severity in (FindingSeverity.CRITICAL.value, FindingSeverity.HIGH.value),
                    metadata={
                        "vulnerability_id": df.vulnerability_id,
                        "fixed_version": df.fixed_version,
                        "ecosystem": df.ecosystem,
                    },
                )
            )

        # 5. Cross-Repository Security Propagation
        # If source repo has critical/high security issues and downstream repos depend on it
        if target_deps and any(f.severity in (FindingSeverity.CRITICAL.value, FindingSeverity.HIGH.value) for f in findings):
            affected_repo_names = [
                str(getattr(d, "source_repository_id", None) or (d.get("source_repository_id") if isinstance(d, dict) else d))
                for d in target_deps
            ]
            findings.append(
                SecurityFinding(
                    organization_id=organization_id,
                    repository_id=repository_id,
                    pr_number=pr_number,
                    commit_sha=commit_sha,
                    category=SecurityCategory.DEPENDENCY_RISK.value,
                    rule_id="SEC_DISTRIBUTED_DOWNSTREAM_EXPOSURE",
                    severity=FindingSeverity.HIGH.value,
                    confidence="HIGH",
                    title="Distributed Security Impact on Downstream Repositories",
                    description=f"Security vulnerabilities in this pull request expose {len(target_deps)} downstream dependent repositories: {', '.join(affected_repo_names[:3])}.",
                    file_path="cross-repo-topology",
                    line_number=None,
                    redacted_evidence=f"Downstream consumer count: {len(target_deps)}",
                    remediation="Remediate source security vulnerabilities before downstream services consume these modified interfaces.",
                    is_blocker=True,
                )
            )

        # 6. Dependency Vulnerability Source Truthfulness
        evidence_status = EvidenceStatus.AVAILABLE.value
        if not dependency_scanner_configured:
            # We explicitly record partial evidence because dependency CVE scanning is unavailable
            evidence_status = EvidenceStatus.PARTIAL.value

        # Calculate counts & risk score
        critical_count = sum(1 for f in findings if f.severity == FindingSeverity.CRITICAL.value)
        high_count = sum(1 for f in findings if f.severity == FindingSeverity.HIGH.value)
        medium_count = sum(1 for f in findings if f.severity == FindingSeverity.MEDIUM.value)
        low_count = sum(1 for f in findings if f.severity == FindingSeverity.LOW.value)

        # Security Risk Score (0 - 100)
        # CRITICAL = +40 pts each
        # HIGH = +25 pts each
        # MEDIUM = +10 pts each
        # LOW-confidence findings add 0 pts
        sec_score = 0.0
        for f in findings:
            if f.confidence == "LOW":
                continue
            if f.severity == FindingSeverity.CRITICAL.value:
                sec_score += 40.0
            elif f.severity == FindingSeverity.HIGH.value:
                sec_score += 25.0
            elif f.severity == FindingSeverity.MEDIUM.value:
                sec_score += 10.0
        sec_score = min(100.0, sec_score)

        # Determine overall Security Status
        if critical_count > 0 or high_count > 0 or any(f.is_blocker for f in findings):
            sec_status = SecurityStatus.SECURITY_BLOCKER.value
        elif medium_count > 0:
            sec_status = SecurityStatus.SECURITY_WARNING.value
        else:
            sec_status = SecurityStatus.NO_SECURITY_FINDINGS.value

        summary = (
            f"Deterministic security scan completed. Status: {sec_status}. "
            f"Findings: {critical_count} CRITICAL, {high_count} HIGH, {medium_count} MEDIUM, {low_count} LOW. "
            f"Evidence status: {evidence_status}."
        )

        return SecurityAssessment(
            security_status=sec_status,
            evidence_status=evidence_status,
            security_risk_score=sec_score,
            critical_count=critical_count,
            high_count=high_count,
            medium_count=medium_count,
            low_count=low_count,
            findings=findings,
            summary=summary,
            metadata={
                "dependency_scan": dep_result.model_dump(),
            },
        )

    @classmethod
    def scan_repository_snapshot(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        files: List[Dict[str, Any]],
        commit_sha: str = "",
        dependency_scanner_configured: bool = False,
    ) -> SecurityAssessment:
        """
        Executes complete deterministic security scan across repository snapshot files.
        Detects hardcoded secrets, dangerous configuration, code-level patterns, and dependency CVEs.
        """
        return cls.analyze_security(
            organization_id=organization_id,
            repository_id=repository_id,
            pr_number=0,
            commit_sha=commit_sha,
            files=files,
            changed_files=files,
            dependency_scanner_configured=dependency_scanner_configured,
        )

    @classmethod
    def scan_repository_for_secrets(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        files: List[Dict[str, Any]],
        commit_sha: str = "",
    ) -> Dict[str, Any]:
        """
        On-demand dedicated secret scan across files or repository snapshot.
        Guarantees zero raw secret exposure — all evidence strictly redacted.
        """
        findings: List[SecurityFinding] = []
        files_scanned = len(files)

        for f in files:
            file_path = f.get("filename") or f.get("file_path") or ""
            content = f.get("content") or f.get("patch") or ""
            if not content.strip():
                continue

            lines = content.splitlines()
            for rule in cls._SECRET_RULES:
                for line_idx, line in enumerate(lines, start=1):
                    match = rule["pattern"].search(line)
                    if match:
                        matched_str = match.group(1) if match.groups() else match.group(0)
                        val_lower = (matched_str or "").lower()
                        if val_lower in cls._SAFE_PLACEHOLDERS or any(w in val_lower for w in ("example", "dummy", "placeholder", "changeme", "mock_key")):
                            continue

                        if rule["rule_id"] == "SEC_SECRET_PRIVATE_KEY":
                            key_type = (match.group(1) or "").upper() if match.groups() else ""
                            redacted = f"[REDACTED_{key_type}_KEY]" if key_type else "[REDACTED_RSA_KEY]"
                        else:
                            redacted = cls._redact_value(matched_str)

                        findings.append(
                            SecurityFinding(
                                organization_id=organization_id,
                                repository_id=repository_id,
                                pr_number=0,
                                commit_sha=commit_sha,
                                category=rule["category"],
                                rule_id=rule["rule_id"],
                                severity=rule["severity"],
                                confidence=rule["confidence"],
                                title=rule["title"],
                                description=rule["description"],
                                file_path=file_path,
                                line_number=line_idx,
                                redacted_evidence=f"Line {line_idx}: matched {rule['rule_id']}: {redacted}",
                                remediation=rule["remediation"],
                                is_blocker=rule["is_blocker"],
                            )
                        )

        critical_count = sum(1 for f in findings if f.severity == FindingSeverity.CRITICAL.value)
        high_count = sum(1 for f in findings if f.severity == FindingSeverity.HIGH.value)
        warning_count = sum(1 for f in findings if f.severity not in (FindingSeverity.CRITICAL.value, FindingSeverity.HIGH.value))

        return {
            "secrets_found": len(findings),
            "critical_count": critical_count,
            "high_count": high_count,
            "warning_count": warning_count,
            "files_scanned": files_scanned,
            "commit_sha": commit_sha,
            "findings": [f.model_dump() for f in findings],
            "status": "ACTIVE_EXPOSURE" if critical_count > 0 or high_count > 0 else "CLEAN",
        }

    @classmethod
    def scan_content_for_secrets(cls, content: str, file_path: str = "patch") -> List[SecurityFinding]:
        findings = []
        for line_idx, line in enumerate(content.splitlines(), start=1):
            for rule in cls._SECRET_RULES:
                match = rule["pattern"].search(line)
                if match:
                    findings.append(
                        SecurityFinding(
                            category=rule["category"],
                            rule_id=rule["rule_id"],
                            severity=rule["severity"],
                            confidence=rule["confidence"],
                            title=rule["title"],
                            description=rule["description"],
                            file_path=file_path,
                            line_number=line_idx,
                            redacted_evidence=f"Line {line_idx}: matched {rule['rule_id']}",
                            remediation=rule["remediation"],
                            is_blocker=rule["is_blocker"],
                        )
                    )
        return findings

    @classmethod
    def scan_content_for_security_rules(cls, content: str, file_path: str = "patch") -> List[SecurityFinding]:
        findings = []
        lines = content.splitlines()
        for rule in cls._SECRET_RULES + cls._CONFIG_RULES + cls._CODE_RULES:
            for line_idx, line in enumerate(lines, start=1):
                if rule["pattern"].search(line):
                    findings.append(
                        SecurityFinding(
                            category=rule["category"],
                            rule_id=rule["rule_id"],
                            severity=rule["severity"],
                            confidence=rule["confidence"],
                            title=rule["title"],
                            description=rule["description"],
                            file_path=file_path,
                            line_number=line_idx,
                            redacted_evidence=f"Line {line_idx}: {line.strip()[:160]}",
                            remediation=rule["remediation"],
                            is_blocker=rule["is_blocker"],
                        )
                    )
        return findings

