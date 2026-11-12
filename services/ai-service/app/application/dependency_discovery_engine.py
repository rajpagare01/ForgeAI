"""
DependencyDiscoveryEngine — Deterministic Cross-Repository Dependency Discovery

Scans repository manifest files and configuration artifacts for EXPLICIT,
authoritative dependency relationships. Never uses embedding similarity.
Never executes repository code or runs builds.

Supported evidence types:
  - Maven pom.xml (groupId + artifactId)
  - Python requirements.txt / setup.cfg / pyproject.toml
  - package.json (internal workspace packages)
  - OpenAPI specification references ($ref, servers.url)
  - Dockerfile / docker-compose service URL references
  - Spring Boot application.yml / application.properties service URLs
  - Explicit proto import statements

Confidence model:
  HIGH   — exact artifact coordinate, explicit service URL, OpenAPI spec ref, schema import
  MEDIUM — strong static config relationship (URL pattern, configured endpoint)
  LOW    — heuristic — never auto-blocks merge
"""
import re
import logging
from typing import List, Dict, Any, Optional, Set, Tuple
from uuid import UUID
from datetime import timezone, datetime

from app.domain.models import (
    RepositoryDependency,
    DependencyEvidence,
    DependencyType,
    DependencyEvidenceType,
    DependencyConfidence,
    DependencyState,
    ContractCompatibilityStatus,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Patterns for dependency discovery
# ---------------------------------------------------------------------------

# Maven pom.xml: captures groupId + artifactId inside <dependency>
_MAVEN_DEP_RE = re.compile(
    r"<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>",
    re.DOTALL,
)

# Python requirements.txt: captures package names (ignoring version constraints)
_REQUIREMENTS_LINE_RE = re.compile(r"^\s*([A-Za-z0-9_\-\.]+)\s*(?:[>=<!~\^].*)?$", re.MULTILINE)

# Python import: captures top-level package from "import X" / "from X.y import z"
_PYTHON_IMPORT_RE = re.compile(r"^(?:from|import)\s+([A-Za-z_][A-Za-z0-9_]*)", re.MULTILINE)

# OpenAPI / YAML server URLs
_OPENAPI_SERVER_URL_RE = re.compile(r"url\s*:\s*['\"]?(https?://[^\s'\"#]+)", re.MULTILINE)

# Spring Boot / application.properties service URL
_SPRING_URL_RE = re.compile(r"(?:url|base-url|base_url|serviceUrl|service-url)\s*[:=]\s*(https?://[^\s#\n\"']+)", re.MULTILINE)

# Docker Compose service links  (image: <service-name>)
_DOCKER_IMAGE_RE = re.compile(r"image\s*:\s*([^\s\n]+)", re.MULTILINE)

# Dockerfile FROM or internal service references
_DOCKERFILE_FROM_RE = re.compile(r"^FROM\s+([^\s]+)", re.MULTILINE)

# Proto import: import "path/to/other.proto"
_PROTO_IMPORT_RE = re.compile(r'import\s+"([^"]+\.proto)"', re.MULTILINE)

# JSON Schema / OpenAPI $ref to external file
_JSONSCHEMA_REF_RE = re.compile(r'\$ref\s*:\s*["\']([^"\'#][^"\']+\.(?:json|yaml|yml|proto))', re.MULTILINE)

# Generic HTTP service URL patterns in config (non-localhost)
_GENERIC_HTTP_URL_RE = re.compile(
    r"(?:url|endpoint|host|baseUrl|base_url)\s*[:=]\s*['\"]?(https?://(?!localhost|127\.0\.0\.1)[^\s'\"#\n]+)",
    re.MULTILINE | re.IGNORECASE,
)


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class DependencyDiscoveryEngine:
    """
    Deterministic static-analysis-based cross-repository dependency discovery.

    Usage:
        results = DependencyDiscoveryEngine.discover(
            organization_id=org_id,
            source_repository_id=src_repo_id,
            source_repository_name="source-control-service",
            all_org_repositories=[{"id": str(uuid), "name": "identity-service", ...}],
            repository_files=[{"file_path": "pom.xml", "content": "..."}, ...],
            commit_sha="abc123...",
        )
        # results: List[Tuple[RepositoryDependency, DependencyEvidence]]
    """

    @classmethod
    def discover_dependencies_from_files(
        cls,
        organization_id: UUID,
        repository_id: UUID,
        changed_files: List[Dict[str, Any]],
        all_org_repositories: List[Dict[str, Any]],
        commit_sha: str = "",
        source_repository_name: str = "",
    ) -> List[Tuple[RepositoryDependency, List[DependencyEvidence]]]:
        """
        Convenience wrapper around discover() that accepts changed_files and returns
        dependencies grouped with their evidence list.
        """
        pairs = cls.discover(
            organization_id=organization_id,
            source_repository_id=repository_id,
            source_repository_name=source_repository_name,
            all_org_repositories=all_org_repositories,
            repository_files=changed_files,
            commit_sha=commit_sha,
        )
        grouped: Dict[str, Tuple[RepositoryDependency, List[DependencyEvidence]]] = {}
        for dep, ev in pairs:
            k = f"{dep.source_repository_id}:{dep.target_repository_id}:{dep.dependency_type}:{dep.source_reference}"
            if k not in grouped:
                grouped[k] = (dep, [])
            grouped[k][1].append(ev)
        return list(grouped.values())

    @classmethod
    def discover(
        cls,
        organization_id: UUID,
        source_repository_id: UUID,
        source_repository_name: str,
        all_org_repositories: List[Dict[str, Any]],   # [{id, name, fullName}]
        repository_files: List[Dict[str, Any]],       # [{file_path, content}]
        commit_sha: str = "",
        existing_dependency_ids: Optional[Set[str]] = None,
    ) -> List[Tuple[RepositoryDependency, DependencyEvidence]]:
        """
        Scans repository_files for explicit cross-repo dependency evidence.
        Returns a list of (RepositoryDependency, DependencyEvidence) pairs.

        Security:
         - Never executes file content as code.
         - Treats file content as UNTRUSTED DATA.
         - Only extracts text patterns from known manifest formats.
         - Never infers dependencies from semantic similarity.
        """
        results: List[Tuple[RepositoryDependency, DependencyEvidence]] = []
        seen: Set[str] = set()

        # Build lookup: repo name → repo id (both ways) for internal matching
        name_to_id: Dict[str, UUID] = {}
        for repo in all_org_repositories:
            rid = repo.get("id") or repo.get("repositoryId")
            rname = (repo.get("name") or repo.get("fullName") or "").lower()
            if rid and rname:
                try:
                    uid = UUID(str(rid))
                    name_to_id[rname] = uid
                    name_to_id[rname.replace("-", "_").replace(".", "_")] = uid
                    name_to_id[rname.replace("_", "-").replace(".", "-")] = uid
                    short = rname.split("/")[-1]
                    if short:
                        name_to_id[short] = uid
                        name_to_id[short.replace("-", "_").replace(".", "_")] = uid
                        name_to_id[short.replace("_", "-").replace(".", "-")] = uid
                except (ValueError, AttributeError):
                    pass

        def add(
            target_repo_id: UUID,
            dep_type: str,
            evidence_type: str,
            confidence: str,
            source_ref: str,
            target_ref: str,
            evidence_kind: str,
            file_path: str,
            excerpt: str,
        ):
            key = f"{source_repository_id}:{target_repo_id}:{dep_type}:{source_ref}"
            if key in seen:
                return
            # Do NOT create a dependency on self
            if target_repo_id == source_repository_id:
                return
            seen.add(key)
            dep = RepositoryDependency(
                organization_id=organization_id,
                source_repository_id=source_repository_id,
                target_repository_id=target_repo_id,
                dependency_type=dep_type,
                evidence_type=evidence_type,
                confidence=confidence,
                source_reference=source_ref[:500] if source_ref else None,
                target_reference=target_ref[:500] if target_ref else None,
                current_state=DependencyState.ACTIVE,
                discovered_at=_utc_now(),
                last_verified_at=_utc_now(),
            )
            ev = DependencyEvidence(
                organization_id=organization_id,
                dependency_id=dep.id,
                evidence_kind=evidence_kind,
                file_path=file_path,
                excerpt=excerpt[:800] if excerpt else None,
                commit_sha=commit_sha or None,
            )
            results.append((dep, ev))

        for file_item in repository_files:
            file_path = file_item.get("file_path") or ""
            content = file_item.get("content") or ""
            if not content or not file_path:
                continue

            fname = file_path.split("/")[-1].lower()

            # ------------------------------------------------------------------
            # Maven pom.xml → SHARED_LIBRARY (HIGH confidence)
            # ------------------------------------------------------------------
            if fname == "pom.xml":
                for m in _MAVEN_DEP_RE.finditer(content):
                    group_id = m.group(1).strip()
                    artifact_id = m.group(2).strip()
                    coord = f"{group_id}:{artifact_id}"
                    # Match artifact_id against known org repo names
                    target = cls._match_repo_name(artifact_id, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"pom.xml:{coord}",
                            target_ref=artifact_id,
                            evidence_kind="MAVEN_POM",
                            file_path=file_path,
                            excerpt=m.group(0)[:300],
                        )

            # ------------------------------------------------------------------
            # requirements.txt → SHARED_LIBRARY (MEDIUM confidence for internal)
            # ------------------------------------------------------------------
            elif fname in ("requirements.txt", "requirements-dev.txt"):
                for m in _REQUIREMENTS_LINE_RE.finditer(content):
                    pkg = m.group(1).strip().lower().replace("-", "_").replace(".", "_")
                    target = cls._match_repo_name(pkg, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.MEDIUM,
                            source_ref=f"requirements.txt:{m.group(1).strip()}",
                            target_ref=m.group(1).strip(),
                            evidence_kind="REQUIREMENTS_TXT",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

            # ------------------------------------------------------------------
            # package.json → SHARED_LIBRARY (MEDIUM confidence for internal pkgs)
            # ------------------------------------------------------------------
            elif fname == "package.json":
                for dep_key in re.findall(r'"(@?[^"]+)":\s*"([^"]+)"', content):
                    pkg_name = dep_key[0].split("/")[-1].lower().replace("-", "_")
                    target = cls._match_repo_name(pkg_name, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.MEDIUM,
                            source_ref=f"package.json:{dep_key[0]}",
                            target_ref=dep_key[0],
                            evidence_kind="PACKAGE_JSON",
                            file_path=file_path,
                            excerpt=f'"{dep_key[0]}": "{dep_key[1]}"',
                        )

            # ------------------------------------------------------------------
            # OpenAPI / Swagger spec → HTTP_API (HIGH confidence)
            # ------------------------------------------------------------------
            if fname.endswith((".yaml", ".yml", ".json")) and (
                "openapi" in content.lower()[:500] or "swagger" in content.lower()[:500]
            ):
                for m in _OPENAPI_SERVER_URL_RE.finditer(content):
                    url = m.group(1).strip()
                    target = cls._match_url_to_repo(url, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.HTTP_API,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{file_path}:servers.url",
                            target_ref=url,
                            evidence_kind="OPENAPI_REF",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )
                # JSON Schema / $ref cross-file references
                for m in _JSONSCHEMA_REF_RE.finditer(content):
                    ref_path = m.group(1).strip()
                    target = cls._match_repo_name(ref_path.split("/")[0], name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_SCHEMA,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{file_path}:$ref",
                            target_ref=ref_path,
                            evidence_kind="JSONSCHEMA_REF",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

            # ------------------------------------------------------------------
            # Spring Boot application.yml / .properties → HTTP_API (HIGH/MEDIUM)
            # ------------------------------------------------------------------
            if fname in (
                "application.yml", "application.yaml",
                "application.properties", "application-prod.yml",
                "application-dev.yml", "bootstrap.yml",
            ):
                for m in _SPRING_URL_RE.finditer(content):
                    url = m.group(1).strip()
                    target = cls._match_url_to_repo(url, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.HTTP_API,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{file_path}:{m.group(0).split(':')[0].strip()}",
                            target_ref=url,
                            evidence_kind="SPRING_CONFIG_URL",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

            # ------------------------------------------------------------------
            # Dockerfile FROM → SHARED_LIBRARY (MEDIUM)
            # ------------------------------------------------------------------
            if fname == "dockerfile" or file_path.lower().endswith("dockerfile"):
                for m in _DOCKERFILE_FROM_RE.finditer(content):
                    image = m.group(1).strip().split(":")[0].split("/")[-1].lower()
                    target = cls._match_repo_name(image, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.DISCOVERED,
                            confidence=DependencyConfidence.MEDIUM,
                            source_ref=f"{file_path}:FROM",
                            target_ref=m.group(1).strip(),
                            evidence_kind="DOCKERFILE_FROM",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

            # ------------------------------------------------------------------
            # Proto imports → EVENT_CONTRACT / SHARED_SCHEMA (HIGH confidence)
            # ------------------------------------------------------------------
            if fname.endswith(".proto"):
                for m in _PROTO_IMPORT_RE.finditer(content):
                    import_path = m.group(1).strip()
                    # Match first path component to known repo
                    first_component = import_path.split("/")[0]
                    target = cls._match_repo_name(first_component, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.SHARED_SCHEMA,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{file_path}:import",
                            target_ref=import_path,
                            evidence_kind="PROTO_IMPORT",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

            # ------------------------------------------------------------------
            # Generic HTTP URL in config / environment files → HTTP_API (MEDIUM)
            # ------------------------------------------------------------------
            if fname in (".env", ".env.example", ".env.local", "config.yml",
                         "config.yaml", "settings.py", "config.py"):
                for m in _GENERIC_HTTP_URL_RE.finditer(content):
                    url = m.group(1).strip()
                    target = cls._match_url_to_repo(url, name_to_id)
                    if target:
                        add(
                            target_repo_id=target,
                            dep_type=DependencyType.HTTP_API,
                            evidence_type=DependencyEvidenceType.DISCOVERED,
                            confidence=DependencyConfidence.MEDIUM,
                            source_ref=f"{file_path}:env_url",
                            target_ref=url,
                            evidence_kind="ENV_URL",
                            file_path=file_path,
                            excerpt=m.group(0)[:200],
                        )

        if not results:
            cls._discover_monorepo_dependencies(
                organization_id=organization_id,
                source_repository_id=source_repository_id,
                source_repository_name=source_repository_name,
                repository_files=repository_files,
                commit_sha=commit_sha,
                results=results,
                seen=seen,
            )

        logger.info(
            "DependencyDiscovery: source=%s found %d dependencies across %d files",
            source_repository_name, len(results), len(repository_files),
        )
        return results

    @classmethod
    def _discover_monorepo_dependencies(
        cls,
        organization_id: UUID,
        source_repository_id: UUID,
        source_repository_name: str,
        repository_files: List[Dict[str, Any]],
        commit_sha: str,
        results: List[Tuple[RepositoryDependency, DependencyEvidence]],
        seen: Set[str],
    ) -> None:
        """
        Extracts deterministic internal service-to-service topology edges when
        external cross-repository matching returns 0 and the repository files span
        multiple monorepo service directories (services/<name>/ or infrastructure/<name>/).
        """
        service_dir_re = re.compile(r"^(?:services|infrastructure)/([^/]+)/", re.IGNORECASE)
        maven_module_re = re.compile(r"<module>\s*([^<\s]+)\s*</module>", re.IGNORECASE)
        service_url_re = re.compile(r"(?:https?|lb|ws)://([a-z0-9][a-z0-9\-]+)(?::\d+)?", re.IGNORECASE)

        monorepo_services: Set[str] = set()
        for f in repository_files:
            fp = (f.get("file_path") or "").lstrip("./")
            m = service_dir_re.match(fp)
            if m:
                monorepo_services.add(m.group(1).lower())
            elif fp.lower() == "pom.xml" and f.get("content"):
                for mod in maven_module_re.findall(f.get("content") or ""):
                    mod_clean = mod.strip().rstrip("/").split("/")[-1].lower()
                    if mod_clean:
                        monorepo_services.add(mod_clean)

        if len(monorepo_services) < 2:
            return

        svc_lookup: Dict[str, str] = {}
        for svc in monorepo_services:
            svc_lookup[svc] = svc
            svc_lookup[svc.replace("-", "_")] = svc
            for suffix in ("-service", "_service"):
                if svc.endswith(suffix):
                    svc_lookup[svc[: -len(suffix)]] = svc

        def match_monorepo_service(candidate: str) -> Optional[str]:
            if not candidate:
                return None
            norm = candidate.strip().lower().replace("_", "-")
            if norm in svc_lookup:
                return svc_lookup[norm]
            norm_under = norm.replace("-", "_")
            if norm_under in svc_lookup:
                return svc_lookup[norm_under]
            return None

        def add_monorepo(
            source_svc: str,
            target_svc: str,
            dep_type: str,
            evidence_type: str,
            confidence: str,
            source_ref: str,
            target_ref: str,
            evidence_kind: str,
            file_path: str,
            excerpt: str,
        ) -> None:
            if not target_svc or source_svc == target_svc:
                return
            key = f"monorepo:{source_svc}:{target_svc}:{dep_type}:{source_ref}"
            if key in seen:
                return
            seen.add(key)
            dep = RepositoryDependency(
                organization_id=organization_id,
                source_repository_id=source_repository_id,
                target_repository_id=source_repository_id,
                dependency_type=dep_type,
                evidence_type=evidence_type,
                confidence=confidence,
                source_reference=source_ref[:500] if source_ref else None,
                target_reference=target_ref[:500] if target_ref else None,
                current_state=DependencyState.ACTIVE,
                metadata={
                    "monorepo_internal": True,
                    "source_service": source_svc,
                    "target_service": target_svc,
                },
                discovered_at=_utc_now(),
                last_verified_at=_utc_now(),
            )
            ev = DependencyEvidence(
                organization_id=organization_id,
                dependency_id=dep.id,
                evidence_kind=evidence_kind,
                file_path=file_path,
                excerpt=excerpt[:800] if excerpt else None,
                commit_sha=commit_sha or None,
            )
            results.append((dep, ev))

        root_name = (source_repository_name or "monorepo-root").split("/")[-1].lower()

        for file_item in repository_files:
            fp = (file_item.get("file_path") or "").lstrip("./")
            content = file_item.get("content") or ""
            if not fp or not content:
                continue

            fname = fp.split("/")[-1].lower()
            dir_match = service_dir_re.match(fp)
            source_svc = dir_match.group(1).lower() if dir_match else None

            # Root pom.xml <module> declarations
            if not source_svc and fname == "pom.xml":
                for mod in maven_module_re.findall(content):
                    mod_clean = mod.strip().rstrip("/")
                    mod_svc = mod_clean.split("/")[-1].lower()
                    target_svc = match_monorepo_service(mod_svc)
                    if target_svc:
                        add_monorepo(
                            source_svc=root_name,
                            target_svc=target_svc,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{root_name}:pom.xml:module:{mod_clean}",
                            target_ref=target_svc,
                            evidence_kind="MAVEN_POM",
                            file_path=fp,
                            excerpt=f"<module>{mod_clean}</module>",
                        )
                continue

            if not source_svc:
                continue

            # Service pom.xml dependencies
            if fname == "pom.xml":
                for m in _MAVEN_DEP_RE.finditer(content):
                    group_id = m.group(1).strip()
                    artifact_id = m.group(2).strip()
                    target_svc = match_monorepo_service(artifact_id)
                    if target_svc:
                        add_monorepo(
                            source_svc=source_svc,
                            target_svc=target_svc,
                            dep_type=DependencyType.SHARED_LIBRARY,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{source_svc}:pom.xml:{group_id}:{artifact_id}",
                            target_ref=target_svc,
                            evidence_kind="MAVEN_POM",
                            file_path=fp,
                            excerpt=m.group(0)[:300],
                        )

            # Service-to-service URLs in Spring configs, Python configs, env files, or client adapters
            is_config_or_client = (
                fname in (
                    "application.yml", "application.yaml", "application.properties",
                    "application-prod.yml", "application-dev.yml", "bootstrap.yml",
                    ".env", ".env.example", ".env.local", "config.yml", "config.yaml",
                    "config.py", "settings.py",
                )
                or fname.endswith((".yml", ".yaml", ".properties"))
                or "client" in fname
                or "filter" in fname
                or "gateway" in fname
            )
            if is_config_or_client:
                for m in service_url_re.finditer(content):
                    host = m.group(1).strip().lower()
                    target_svc = match_monorepo_service(host)
                    if target_svc:
                        add_monorepo(
                            source_svc=source_svc,
                            target_svc=target_svc,
                            dep_type=DependencyType.HTTP_API,
                            evidence_type=DependencyEvidenceType.EXPLICIT,
                            confidence=DependencyConfidence.HIGH,
                            source_ref=f"{source_svc}:{fp}:{target_svc}",
                            target_ref=target_svc,
                            evidence_kind="SPRING_CONFIG_URL",
                            file_path=fp,
                            excerpt=m.group(0)[:200],
                        )

    # ---------------------------------------------------------------------------
    # Contract Compatibility Analysis
    # ---------------------------------------------------------------------------

    @classmethod
    def classify_contract_compatibility(
        cls,
        changed_files: List[Dict[str, Any]],
        changed_symbols: List[str],
    ) -> Tuple[str, str]:
        """
        Deterministically classifies contract compatibility of a changeset.
        Returns (ContractCompatibilityStatus, rationale_str).

        Evidence used:
          - Deleted files with API/schema indicators
          - Changed method signatures in changed_symbols
          - Deleted endpoints in OpenAPI spec files
          - Removed fields / type changes in proto files
          - Removed enum values

        UNKNOWN is returned when evidence is insufficient.
        The LLM must never upgrade UNKNOWN to BREAKING.
        """
        if not changed_files and not changed_symbols:
            return ContractCompatibilityStatus.UNKNOWN, "No changed files or symbols to classify."

        removed_files = [
            f for f in changed_files
            if (f.get("status") or "").lower() == "removed"
        ]
        changed_paths = [
            (f.get("filename") or f.get("file_path") or "").lower()
            for f in changed_files
        ]

        breaking_signals: List[str] = []
        potentially_breaking_signals: List[str] = []

        # Removed API / schema / proto files
        for f in removed_files:
            fp = (f.get("filename") or f.get("file_path") or "").lower()
            if any(kw in fp for kw in ("openapi", "swagger", "api", "proto", "schema", "contract")):
                breaking_signals.append(f"API/schema file removed: {fp}")

        # Deleted endpoint indicators (HTTP method + path in patch)
        for f in changed_files:
            patch = f.get("patch") or f.get("diff") or ""
            fp = (f.get("filename") or f.get("file_path") or "").lower()

            # Proto: removed field definitions
            if fp.endswith(".proto"):
                removed_fields = re.findall(r"^-\s+\w+\s+\w+\s+\w+\s*=\s*\d+", patch, re.MULTILINE)
                if removed_fields:
                    breaking_signals.append(f"Proto field removed in {fp}: {removed_fields[:2]}")

                removed_enums = re.findall(r"^-\s+[A-Z_]+\s*=\s*\d+", patch, re.MULTILINE)
                if removed_enums:
                    breaking_signals.append(f"Proto enum value removed in {fp}: {removed_enums[:2]}")

            # OpenAPI: removed path/method
            if fp.endswith((".yaml", ".yml", ".json")) and "openapi" in fp:
                removed_paths = re.findall(r"^-\s+(?:get|post|put|delete|patch):", patch, re.MULTILINE | re.IGNORECASE)
                if removed_paths:
                    potentially_breaking_signals.append(f"HTTP method entry removed in OpenAPI spec: {fp}")

                removed_required = re.findall(r"^-\s+- \w+", patch, re.MULTILINE)
                if removed_required and "required" in patch:
                    potentially_breaking_signals.append(f"Required field removed in {fp}")

            # Controller code: removed REST/HTTP endpoint annotations
            deleted_endpoints = re.findall(
                r"^-\s*@(DeleteMapping|GetMapping|PostMapping|PutMapping|PatchMapping|RequestMapping)\b",
                patch,
                re.MULTILINE,
            )
            if deleted_endpoints:
                breaking_signals.append(f"Deleted or removed API endpoint annotation in {fp}: {deleted_endpoints[0]}")


        # Changed symbols: deleted public methods / classes
        for sym in changed_symbols:
            sym_lower = sym.lower()
            # Heuristic: symbol names with "delete", "remove", "drop" suggest breaking
            if any(kw in sym_lower for kw in ("delete", "remove", "drop")):
                potentially_breaking_signals.append(f"Symbol with breaking semantics: {sym}")

        # Evaluate
        if breaking_signals:
            return (
                ContractCompatibilityStatus.BREAKING,
                f"Breaking signals detected: {'; '.join(breaking_signals[:3])}",
            )
        elif potentially_breaking_signals:
            return (
                ContractCompatibilityStatus.POTENTIALLY_BREAKING,
                f"Potentially breaking: {'; '.join(potentially_breaking_signals[:3])}",
            )
        elif removed_files:
            return (
                ContractCompatibilityStatus.POTENTIALLY_BREAKING,
                f"{len(removed_files)} files removed — compatibility unknown for downstream consumers.",
            )
        elif any("api" in p or "openapi" in p or "schema" in p or "proto" in p for p in changed_paths):
            return (
                ContractCompatibilityStatus.POTENTIALLY_BREAKING,
                "API/schema files changed — manual compatibility review recommended.",
            )
        else:
            return (
                ContractCompatibilityStatus.COMPATIBLE,
                "No API/schema removals or breaking symbol changes detected.",
            )

    # ---------------------------------------------------------------------------
    # Internal helpers
    # ---------------------------------------------------------------------------

    @staticmethod
    def _match_repo_name(name: str, name_to_id: Dict[str, UUID]) -> Optional[UUID]:
        """
        Exact match of a package/artifact/image name to a known org repository.
        Normalizes hyphens/underscores/dots for fuzzy-but-deterministic matching.
        Never uses semantic similarity.
        """
        if not name:
            return None
        norm = name.lower().replace("-", "_").replace(".", "_")
        # Direct match
        if norm in name_to_id:
            return name_to_id[norm]
        # Try original
        if name.lower() in name_to_id:
            return name_to_id[name.lower()]
        # Try the name with service suffix stripped
        for suffix in ("-service", "_service", "-api", "_api", "-lib", "_lib"):
            candidate = norm + suffix
            if candidate in name_to_id:
                return name_to_id[candidate]
            # Also try without suffix if name already has it
            if norm.endswith(suffix.replace("-", "_")):
                stripped = norm[: -len(suffix)]
                if stripped in name_to_id:
                    return name_to_id[stripped]
        return None

    @staticmethod
    def _match_url_to_repo(url: str, name_to_id: Dict[str, UUID]) -> Optional[UUID]:
        """
        Extracts host/path fragments from a URL and matches against known repo names.
        Matches internal service hostnames (e.g., http://identity-service:8081 → identity_service).
        Does NOT infer ownership of external domains.
        """
        if not url:
            return None
        # Strip scheme
        host_part = re.sub(r"^https?://", "", url).split("/")[0].split(":")[0].lower()
        # Match hostname against repo names
        norm = host_part.replace("-", "_").replace(".", "_")
        if norm in name_to_id:
            return name_to_id[norm]
        if host_part in name_to_id:
            return name_to_id[host_part]
        # Only match internal-looking names (not external domains)
        # External domains have dots in them (e.g., api.example.com)
        if "." in host_part:
            return None  # External domain — not an internal dependency
        return None
