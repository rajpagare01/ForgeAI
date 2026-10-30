import re
from typing import Set, List, Dict, Any, Optional
from app.application.security_intelligence_engine import SecurityIntelligenceEngine


class DocumentValidationError(Exception):
    """Raised when generated document fails path, size, secret, or citation verification."""
    pass


class DocumentValidator:
    """
    Enforces strict safety, security, size, and citation invariants
    on all generated repository documents before persistence or publication.
    """

    MAX_DOC_SIZE_BYTES: int = 100_000  # 100 KB

    CITATION_REGEX = re.compile(
        r'\[([a-zA-Z0-9_./\-]+?)@([a-fA-F0-9]{6,64}|HEAD)\]'
    )

    @classmethod
    def validate(
        cls,
        target_path: str,
        content_md: str,
        commit_sha: str,
        valid_file_paths: Set[str],
        structured_citations: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        """
        Validates target path, document size, zero unredacted secrets,
        and verified non-fabricated citations against repository state.
        """
        cls.validate_target_path(target_path)
        cls.validate_size(content_md)
        cls.validate_no_secrets(content_md)
        cls.validate_citations(content_md, valid_file_paths, commit_sha, structured_citations)

    @classmethod
    def validate_target_path(cls, target_path: str) -> None:
        if not target_path:
            raise DocumentValidationError("target_path is required")
        normalized = target_path.replace("\\", "/")
        if ".." in normalized.split("/"):
            raise DocumentValidationError(f"Path traversal detected in target_path: '{target_path}'")
        if not normalized.startswith("docs/generated/"):
            raise DocumentValidationError(
                f"target_path must reside in 'docs/generated/', got: '{target_path}'"
            )
        if not normalized.endswith(".md"):
            raise DocumentValidationError(
                f"target_path must have '.md' extension, got: '{target_path}'"
            )

    @classmethod
    def validate_size(cls, content_md: str) -> None:
        byte_len = len(content_md.encode("utf-8"))
        if byte_len > cls.MAX_DOC_SIZE_BYTES:
            raise DocumentValidationError(
                f"Document size {byte_len} bytes exceeds maximum allowed limit of {cls.MAX_DOC_SIZE_BYTES} bytes"
            )

    @classmethod
    def validate_no_secrets(cls, content_md: str) -> None:
        findings = SecurityIntelligenceEngine.scan_content_for_secrets(content_md, "generated_doc")
        if findings:
            rule_ids = ", ".join(f.rule_id for f in findings)
            raise DocumentValidationError(
                f"Document contains unredacted secret patterns ({rule_ids}). All secrets must be masked."
            )

    @classmethod
    def validate_citations(
        cls,
        content_md: str,
        valid_file_paths: Set[str],
        commit_sha: str,
        structured_citations: Optional[List[Dict[str, Any]]] = None,
    ) -> None:
        # Normalize valid paths
        norm_valid = {fp.replace("\\", "/").lstrip("./") for fp in valid_file_paths if fp}

        # 1. Parse citations from Markdown text
        found_citations = cls.CITATION_REGEX.findall(content_md)

        # 2. Add citations from structured metadata
        if structured_citations:
            for sc in structured_citations:
                fp = sc.get("file_path")
                sha = sc.get("commit_sha", commit_sha)
                if fp:
                    found_citations.append((fp, sha))

        target_sha = commit_sha.lower()

        for raw_fp, cited_sha in found_citations:
            norm_fp = raw_fp.replace("\\", "/").lstrip("./")
            
            # Verify file existence in repository code chunks / files
            if norm_valid and norm_fp not in norm_valid:
                raise DocumentValidationError(
                    f"Fabricated citation detected: Cited file '{raw_fp}' does not exist in indexed repository files"
                )

            # Verify commit SHA matches the version being documented
            lower_cited = cited_sha.lower()
            if lower_cited != "head":
                # Must match or be a prefix/suffix of target_sha
                if not (target_sha.startswith(lower_cited) or lower_cited.startswith(target_sha)):
                    raise DocumentValidationError(
                        f"Mismatched citation commit SHA: Cited '{cited_sha}' does not match repository commit '{commit_sha}'"
                    )

