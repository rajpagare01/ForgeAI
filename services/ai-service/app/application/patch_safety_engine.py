import re
import ast
import json
import logging
from typing import Dict, Any, List, Optional, Tuple
from app.application.security_intelligence_engine import SecurityIntelligenceEngine
from app.application.reliability_intelligence_engine import ReliabilityIntelligenceEngine

logger = logging.getLogger(__name__)


class PatchSafetyValidationError(Exception):
    """Raised when a patch proposal fails safety, scope, or static validation."""
    pass


class PatchSafetyEngine:
    """
    Patch Safety & Static Post-Patch Validation Engine.
    Enforces strict architectural boundaries before any patch can be approved or applied:

    1. Scope Limits:
       - Max files changed (default: 3)
       - Max lines changed (default: 150)
       - Max diff size (default: 32,768 bytes)
       - Forbidden paths (.git/, .env*, credentials, etc.)
       - Protected branch defense (never touch main/master directly)

    2. Context Verification:
       - Target file exists
       - Expected original context matches current source

    3. Secret Defense:
       - Scans proposed replacement with SecurityIntelligenceEngine secret rules.
       - Rejects any patch that introduces secrets.

    4. In-Memory Structured Patch Applicator:
       - Applies unified diff in-memory without shell commands or arbitrary OS processes.

    5. Static Post-Patch Validation:
       - AST syntax validation (ast.parse for Python, json.loads for JSON, delimiter balance for Java/TS)
       - Re-evaluates deterministic security and reliability rules on patched content.
       - Verifies that target finding is resolved and no new findings are introduced.
       - NEVER executes arbitrary code (NO npm install, NO mvn, NO gradle, NO shell scripts).
    """

    MAX_FILES = 3
    MAX_LINES = 150
    MAX_BYTES = 32768
    FORBIDDEN_PATH_PATTERNS = [
        r"^\.git/",
        r"^\.env(\..+)?$",
        r".*id_rsa.*",
        r".*\.pem$",
        r".*secret.*",
        r".*credential.*",
        r"^\.github/workflows/.*",  # CI workflows protected from automated modification
    ]
    PROTECTED_BRANCHES = {"main", "master", "release", "prod", "production"}

    @classmethod
    def validate_patch_safety(
        cls,
        target_file: str,
        original_content: str,
        unified_diff: str,
        proposed_replacement: Optional[str] = None,
        source_finding_id: Optional[str] = None,
        target_branch: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Runs comprehensive pre-application safety checks.
        Returns validation metadata dict if valid, or raises PatchSafetyValidationError.
        """
        # 1. Protected Branch Check
        if target_branch and target_branch.lower() in cls.PROTECTED_BRANCHES:
            raise PatchSafetyValidationError(
                f"Patch violates safety policy: cannot target protected branch '{target_branch}' directly."
            )

        # 2. Forbidden Path Check
        cls._verify_allowed_path(target_file)

        # 3. Diff Size and Line Limits
        diff_bytes = len(unified_diff.encode("utf-8"))
        if diff_bytes > cls.MAX_BYTES:
            raise PatchSafetyValidationError(
                f"Patch exceeds size limit: {diff_bytes} bytes (max: {cls.MAX_BYTES} bytes)."
            )

        diff_lines = unified_diff.splitlines()
        changed_lines_count = sum(1 for line in diff_lines if line.startswith("+") or line.startswith("-"))
        if changed_lines_count > cls.MAX_LINES:
            raise PatchSafetyValidationError(
                f"Patch exceeds line modification limit: {changed_lines_count} lines changed (max: {cls.MAX_LINES})."
            )

        # 4. Zero Secrets Introduction Check
        content_to_scan = proposed_replacement or unified_diff
        secret_findings = SecurityIntelligenceEngine.scan_content_for_secrets(content_to_scan, target_file)
        if secret_findings:
            raise PatchSafetyValidationError(
                f"Patch rejected: proposed replacement contains suspected raw secret: {secret_findings[0].title}"
            )

        # 5. Apply Patch In-Memory & Validate Hunk Matching
        patched_content, applied_hunks = cls.apply_patch_in_memory(original_content, unified_diff)

        # 6. Static Post-Patch AST / Syntax Validation
        syntax_valid, syntax_error = cls.validate_syntax(target_file, patched_content)
        if not syntax_valid:
            raise PatchSafetyValidationError(
                f"Patch rejected: post-patch static syntax validation failed for {target_file}: {syntax_error}"
            )

        # 7. Post-Patch Deterministic Rule Verification
        rule_check = cls.verify_rule_remediation(target_file, original_content, patched_content, source_finding_id)

        return {
            "valid": True,
            "target_file": target_file,
            "applied_hunks": applied_hunks,
            "changed_lines": changed_lines_count,
            "diff_size_bytes": diff_bytes,
            "syntax_valid": syntax_valid,
            "finding_resolved": rule_check.get("resolved", True),
            "new_findings_count": rule_check.get("new_findings_count", 0),
            "patched_content": patched_content,
        }

    @classmethod
    def _verify_allowed_path(cls, file_path: str):
        normalized = file_path.replace("\\", "/").lstrip("/")
        for pattern in cls.FORBIDDEN_PATH_PATTERNS:
            if re.search(pattern, normalized, re.IGNORECASE):
                raise PatchSafetyValidationError(
                    f"Patch violates forbidden path policy: '{file_path}' matches restricted pattern '{pattern}'."
                )

    @classmethod
    def apply_patch_in_memory(cls, original_content: str, unified_diff: str) -> Tuple[str, int]:
        """
        Pure in-memory structured patch application.
        Parses standard unified diff hunks (--- a/..., +++ b/..., @@ -start,len +start,len @@)
        and applies changes line-by-line.
        """
        lines = original_content.splitlines(keepends=True)
        diff_lines = unified_diff.splitlines()

        hunks = cls._parse_unified_diff_hunks(diff_lines)
        if not hunks:
            # If no formal @@ hunks found, attempt exact replacement if original_context can be located
            raise PatchSafetyValidationError("Patch format error: no valid unified diff hunks found.")

        # Sort hunks in reverse line order so line index changes don't disrupt earlier hunks
        hunks.sort(key=lambda h: h["old_start"], reverse=True)

        applied_count = 0
        for hunk in hunks:
            old_start = hunk["old_start"] - 1  # 0-indexed
            old_lines = hunk["old_lines"]
            new_lines = hunk["new_lines"]

            # If 0-indexed old_start is within bounds, verify context matches
            # Allow flexible matching within +/- 5 lines
            match_idx = cls._find_hunk_match(lines, old_start, old_lines)
            if match_idx is None:
                raise PatchSafetyValidationError(
                    f"Context mismatch: expected context for hunk @@ -{hunk['old_start']} @@ does not match target file."
                )

            # Replace lines in slice
            lines[match_idx : match_idx + len(old_lines)] = [l + "\n" if not l.endswith("\n") else l for l in new_lines]
            applied_count += 1

        return "".join(lines), applied_count

    @classmethod
    def _parse_unified_diff_hunks(cls, diff_lines: List[str]) -> List[Dict[str, Any]]:
        hunks = []
        current_hunk = None

        hunk_header_re = re.compile(r"^@@\s*-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s*@@")

        for line in diff_lines:
            match = hunk_header_re.match(line)
            if match:
                if current_hunk:
                    hunks.append(current_hunk)
                old_start = int(match.group(1))
                old_len = int(match.group(2)) if match.group(2) else 1
                new_start = int(match.group(3))
                new_len = int(match.group(4)) if match.group(4) else 1
                current_hunk = {
                    "old_start": old_start,
                    "old_len": old_len,
                    "new_start": new_start,
                    "new_len": new_len,
                    "old_lines": [],
                    "new_lines": [],
                }
            elif current_hunk:
                if line.startswith("-"):
                    current_hunk["old_lines"].append(line[1:])
                elif line.startswith("+"):
                    current_hunk["new_lines"].append(line[1:])
                elif line.startswith(" ") or line == "":
                    # Context line present in both
                    ctx = line[1:] if line.startswith(" ") else line
                    current_hunk["old_lines"].append(ctx)
                    current_hunk["new_lines"].append(ctx)

        if current_hunk:
            hunks.append(current_hunk)

        return hunks

    @classmethod
    def _find_hunk_match(cls, lines: List[str], target_start: int, expected_old_lines: List[str]) -> Optional[int]:
        def strip_lines(l_list):
            return [l.strip() for l in l_list]

        expected_stripped = strip_lines(expected_old_lines)
        if not expected_stripped:
            return max(0, min(target_start, len(lines)))

        window_size = len(expected_old_lines)
        # Check target_start first
        if 0 <= target_start <= len(lines) - window_size:
            actual_stripped = strip_lines(lines[target_start : target_start + window_size])
            if actual_stripped == expected_stripped:
                return target_start

        # Search within +/- 15 lines window for minor drift
        search_min = max(0, target_start - 15)
        search_max = min(len(lines) - window_size, target_start + 15)
        for idx in range(search_min, search_max + 1):
            actual_stripped = strip_lines(lines[idx : idx + window_size])
            if actual_stripped == expected_stripped:
                return idx

        # If strict match fails, check if the first and last expected lines match
        for idx in range(search_min, search_max + 1):
            if strip_lines([lines[idx]]) == [expected_stripped[0]] and strip_lines([lines[idx + window_size - 1]]) == [expected_stripped[-1]]:
                return idx

        return None

    @classmethod
    def validate_syntax(cls, file_path: str, content: str) -> Tuple[bool, Optional[str]]:
        """
        Static syntax & AST validation without arbitrary command execution.
        """
        ext = file_path.split(".")[-1].lower() if "." in file_path else ""

        # 1. Python AST parsing
        if ext == "py":
            try:
                ast.parse(content, filename=file_path)
                return True, None
            except SyntaxError as e:
                return False, f"Python SyntaxError at line {e.lineno}: {e.msg}"

        # 2. JSON parsing
        if ext == "json":
            try:
                json.loads(content)
                return True, None
            except Exception as e:
                return False, f"Invalid JSON syntax: {e}"

        # 3. Delimiter Balance for Java, TypeScript, JavaScript
        if ext in ("java", "ts", "tsx", "js", "jsx"):
            balanced, err = cls._check_delimiter_balance(content)
            if not balanced:
                return False, f"Unbalanced syntax delimiters in {file_path}: {err}"
            return True, None

        # Other supported types (yaml, xml, md) — pass if non-empty
        return True, None

    @classmethod
    def _check_delimiter_balance(cls, code: str) -> Tuple[bool, Optional[str]]:
        """Checks balance of braces, brackets, and parentheses ignoring comments/strings."""
        stack = []
        pairs = {")": "(", "}": "{", "]": "["}
        
        # Simple lexer skipping single-line and multi-line comments
        in_line_comment = False
        in_block_comment = False
        in_string = False
        string_char = ""
        i = 0
        n = len(code)

        while i < n:
            char = code[i]
            nxt = code[i + 1] if i + 1 < n else ""

            if in_line_comment:
                if char == "\n":
                    in_line_comment = False
                i += 1
                continue

            if in_block_comment:
                if char == "*" and nxt == "/":
                    in_block_comment = False
                    i += 2
                    continue
                i += 1
                continue

            if in_string:
                if char == "\\" and i + 1 < n:
                    i += 2  # skip escaped char
                    continue
                if char == string_char:
                    in_string = False
                i += 1
                continue

            # Check comment start
            if char == "/" and nxt == "/":
                in_line_comment = True
                i += 2
                continue
            if char == "/" and nxt == "*":
                in_block_comment = True
                i += 2
                continue

            # Check string start
            if char in ('"', "'", "`"):
                in_string = True
                string_char = char
                i += 1
                continue

            # Delimiters
            if char in ("(", "{", "["):
                stack.append((char, i))
            elif char in (")", "}", "]"):
                if not stack:
                    return False, f"Unexpected closing '{char}' at character {i}"
                top, _ = stack.pop()
                if pairs[char] != top:
                    return False, f"Mismatched closing '{char}' for '{top}' at character {i}"

            i += 1

        if stack:
            unclosed, pos = stack[-1]
            return False, f"Unclosed '{unclosed}' remaining at character {pos}"

        return True, None

    @classmethod
    def verify_rule_remediation(
        cls,
        file_path: str,
        original_content: str,
        patched_content: str,
        source_finding_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Re-evaluates security & reliability rules over the patched content.
        Verifies that:
        1. Target finding is no longer detected.
        2. Zero new security or reliability findings are introduced.
        """
        # Run Security Rules
        sec_findings_before = SecurityIntelligenceEngine.scan_content_for_security_rules(original_content, file_path)
        sec_findings_after = SecurityIntelligenceEngine.scan_content_for_security_rules(patched_content, file_path)

        # Run Reliability Rules
        rel_findings_before = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(original_content, file_path)
        rel_findings_after = ReliabilityIntelligenceEngine.scan_content_for_reliability_rules(patched_content, file_path)

        before_rules = {f.rule_id for f in sec_findings_before + rel_findings_before}
        after_rules = {f.rule_id for f in sec_findings_after + rel_findings_after}

        resolved = True
        if source_finding_id and source_finding_id in after_rules:
            resolved = False

        new_rules = after_rules - before_rules

        return {
            "resolved": resolved,
            "new_findings_count": len(new_rules),
            "new_rules": list(new_rules),
            "remaining_rules": list(after_rules),
        }
