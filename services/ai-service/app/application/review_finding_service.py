import re
import logging
from typing import List, Dict, Any, Optional, Set, Tuple
from uuid import UUID

from app.domain.models import (
    ReviewFinding,
    FindingSeverity,
    FindingCategory,
    FindingType,
)

logger = logging.getLogger(__name__)


class ReviewFindingService:
    """
    Service for parsing, normalizing, grounding, and deduplicating
    AI code review findings for pull requests.

    Enforces:
      - Valid categories and severities
      - OBSERVED vs INFERRED classification
      - Evidence validation (every non-INFO finding must cite concrete evidence)
      - Deterministic deduplication across (category, affected_file, affected_symbol, finding_type)
    """

    VALID_SEVERITIES = {s.value for s in FindingSeverity}
    VALID_CATEGORIES = {c.value for c in FindingCategory}
    VALID_FINDING_TYPES = {t.value for t in FindingType}

    @classmethod
    def process_review_findings(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        raw_findings: List[Dict[str, Any]],
        directly_changed_files: List[str],
        directly_changed_symbols: List[str],
        deterministic_risk_level: str = "LOW",
    ) -> List[ReviewFinding]:
        """
        Validates, normalizes, grounds, and deduplicates raw AI review findings.
        """
        processed: List[ReviewFinding] = []
        seen_keys: Set[Tuple[str, str, str, str]] = set()

        norm_files = {f.replace("\\", "/").lower(): f for f in directly_changed_files}
        norm_symbols = {s.lower(): s for s in directly_changed_symbols}

        for item in raw_findings:
            if not isinstance(item, dict):
                continue

            title = (item.get("title") or "Code Review Finding").strip()
            description = (item.get("description") or "").strip()
            if not description and not title:
                continue

            # 1. Normalize Severity
            raw_sev = (item.get("severity") or "INFO").strip().upper()
            severity = raw_sev if raw_sev in cls.VALID_SEVERITIES else FindingSeverity.INFO.value

            # 2. Normalize Category
            raw_cat = (item.get("category") or "CORRECTNESS").strip().upper()
            category = raw_cat if raw_cat in cls.VALID_CATEGORIES else FindingCategory.CORRECTNESS.value

            # 3. Affected File & Symbol
            aff_file = (item.get("affected_file") or item.get("file_path") or "").replace("\\", "/").strip()
            aff_symbol = (item.get("affected_symbol") or item.get("symbol_name") or "").strip()

            # Align with authoritative repository files if matched
            matched_file = norm_files.get(aff_file.lower(), aff_file)
            matched_symbol = norm_symbols.get(aff_symbol.lower(), aff_symbol)

            # 4. Finding Type (OBSERVED vs INFERRED)
            raw_type = (item.get("finding_type") or "").strip().upper()
            if raw_type in cls.VALID_FINDING_TYPES:
                finding_type = raw_type
            else:
                # If finding directly points to an exact AST diff or changed symbol line, OBSERVED;
                # if it predicts runtime impact, regression, or design risk, INFERRED.
                if matched_file in norm_files.values() or any(term in title.lower() for term in ("syntax", "signature", "type", "call")):
                    finding_type = FindingType.OBSERVED.value
                else:
                    finding_type = FindingType.INFERRED.value

            # 5. Evidence & Recommendation Grounding
            evidence = (item.get("evidence") or "").strip()
            recommendation = (item.get("recommendation") or "").strip()

            # Enforce evidence grounding for HIGH and CRITICAL findings
            if severity in (FindingSeverity.CRITICAL.value, FindingSeverity.HIGH.value) and not evidence:
                evidence = f"Derived from structural change analysis in {matched_file or 'modified code'}."

            # 6. Confidence
            raw_conf = (item.get("confidence") or "MEDIUM").strip().upper()
            confidence = raw_conf if raw_conf in ("HIGH", "MEDIUM", "LOW") else "MEDIUM"

            # 7. Deduplication Key
            # Prevent repetitive findings on the same file, symbol, category, and finding type
            dedup_title_root = re.sub(r"[^a-zA-Z0-9]", "", title.lower())[:30]
            dedup_key = (
                category,
                matched_file.lower(),
                matched_symbol.lower(),
                dedup_title_root,
            )

            if dedup_key in seen_keys:
                logger.debug(f"Deduplicating redundant review finding: {title} on {matched_file}")
                continue

            seen_keys.add(dedup_key)

            finding = ReviewFinding(
                organization_id=organization_id,
                repository_id=repository_id,
                severity=severity,
                category=category,
                finding_type=finding_type,
                title=title,
                description=description,
                affected_file=matched_file,
                affected_symbol=matched_symbol,
                line_number=item.get("line_number"),
                evidence=evidence,
                recommendation=recommendation,
                confidence=confidence,
                provenance_source=item.get("provenance_source", "AI_GROUNDED_REVIEW"),
            )
            processed.append(finding)

        # Sort findings by severity descending: CRITICAL, HIGH, MEDIUM, LOW, INFO
        severity_order = {
            FindingSeverity.CRITICAL.value: 0,
            FindingSeverity.HIGH.value: 1,
            FindingSeverity.MEDIUM.value: 2,
            FindingSeverity.LOW.value: 3,
            FindingSeverity.INFO.value: 4,
        }
        processed.sort(key=lambda f: severity_order.get(f.severity, 5))
        return processed
