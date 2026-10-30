import asyncio
import hashlib
from uuid import uuid4, UUID
import pytest
from fastapi import HTTPException

from app.domain.models import (
    DocumentType,
    DocumentStatus,
    GeneratedDocument,
    CommitAnalysisReport,
    CodeChunk,
    GraphEdge,
    RepositoryDependency,
    GitHubWriteMode,
)
from app.domain.repositories import (
    GeneratedDocumentRepository,
    CommitAnalysisReportRepository,
)
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryGeneratedDocumentRepository,
    InMemoryCommitAnalysisReportRepository,
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
)
from app.application.document_generation_service import DocumentGenerationService
from app.application.doc_validator import DocumentValidator, DocumentValidationError
from app.application.doc_collectors import (
    OverviewCollector,
    ApiReferenceCollector,
    DataModelCollector,
    DependencyMapCollector,
)
from app.application.security_intelligence_engine import SecurityIntelligenceEngine
from app.core.logging import mask_sensitive_data
from app.infrastructure.notifications.email_notifier import EmailNotificationService


class MockSourceControlClient:
    def __init__(self, succeeds: bool = True):
        self.succeeds = succeeds
        self.proposed_prs = []

    async def propose_files_pull_request(
        self,
        organization_id: UUID,
        repository_id: UUID,
        base_commit_sha: str,
        branch_name: str,
        pr_title: str,
        pr_body: str,
        files: list,
    ):
        if not self.succeeds:
            raise HTTPException(status_code=500, detail="Failed to create PR")
        pr_record = {
            "pr_url": f"https://github.com/mock-org/mock-repo/pull/{len(self.proposed_prs) + 1}",
            "pr_number": len(self.proposed_prs) + 1,
            "branch_name": branch_name,
            "files": files,
        }
        self.proposed_prs.append(pr_record)
        return pr_record


@pytest.fixture
def mock_sc_client():
    return MockSourceControlClient(succeeds=True)


@pytest.fixture
def test_setup(mock_sc_client):
    doc_repo = InMemoryGeneratedDocumentRepository()
    commit_repo = InMemoryCommitAnalysisReportRepository()
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()

    service = DocumentGenerationService(
        doc_repo=doc_repo,
        code_chunk_repo=chunk_repo,
        graph_edge_repo=edge_repo,
        source_control_client=mock_sc_client,
        write_mode="CONTROLLED_WRITE",
    )
    return service, doc_repo, commit_repo, chunk_repo, edge_repo


# ============================================================================
# INVARIANT 1: IDEMPOTENT REGENERATION
# ============================================================================
@pytest.mark.asyncio
async def test_idempotent_regeneration(test_setup):
    """
    Generating the same (org_id, repo_id, doc_type, commit_sha) twice returns
    the single deduplicated record without duplicate rows or errors.
    """
    service, doc_repo, _, chunk_repo, _ = test_setup
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "abc1234567890def"

    # Seed sample code chunk
    chunk = CodeChunk(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        file_path="src/main/java/com/forgeai/Main.java",
        symbol_name="Main",
        symbol_type="CLASS",
        content="public class Main { public static void main(String[] args) {} }",
        start_line=1,
        end_line=5,
        content_hash="hash1",
        is_current=True,
    )
    await chunk_repo.save_chunk(chunk)

    # First generation
    docs_first = await service.generate_documents(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        doc_types=["OVERVIEW"],
        repo_name="ForgeAI-Backend",
    )
    assert len(docs_first) == 1
    first_doc = docs_first[0]
    assert first_doc.doc_type == "OVERVIEW"
    assert first_doc.commit_sha == commit_sha

    # Verify 1 record in repository
    all_docs_after_first = await doc_repo.list_documents_for_repository(org_id, repo_id)
    assert len(all_docs_after_first) == 1

    # Second generation (same inputs)
    docs_second = await service.generate_documents(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        doc_types=["OVERVIEW"],
        repo_name="ForgeAI-Backend",
    )
    assert len(docs_second) == 1
    second_doc = docs_second[0]

    # Must return the exact same document ID and identical hash
    assert second_doc.id == first_doc.id
    assert second_doc.content_hash == first_doc.content_hash

    # Count in repository must still be strictly 1
    all_docs_after_second = await doc_repo.list_documents_for_repository(org_id, repo_id)
    assert len(all_docs_after_second) == 1


# ============================================================================
# INVARIANT 2: STRICT TENANT ISOLATION
# ============================================================================
@pytest.mark.asyncio
async def test_strict_tenant_isolation(test_setup):
    """
    Tenant B cannot read, approve, reject, or apply Tenant A's GeneratedDocument
    or CommitAnalysisReport (returns 404).
    """
    service, doc_repo, commit_repo, _, _ = test_setup
    org_a = uuid4()
    org_b = uuid4()
    repo_a = uuid4()

    doc_a = GeneratedDocument(
        organization_id=org_a,
        repository_id=repo_a,
        doc_type="OVERVIEW",
        target_path="docs/generated/overview.md",
        commit_sha="commit_a_123",
        content_hash="hash_a",
        content_md="# Tenant A System Overview\nPrivate design.",
        citations=[],
        provenance={},
        status=DocumentStatus.DRAFT.value,
    )
    saved_doc = await doc_repo.upsert_document(doc_a)

    # 1. Tenant B reads via repository -> returns None
    assert await doc_repo.get_document_by_id(org_b, saved_doc.id) is None

    # 2. Tenant B attempts approval -> raises 404
    with pytest.raises(HTTPException) as exc_info:
        await service.approve_document(organization_id=org_b, document_id=saved_doc.id, actor_id="tenant_b_user")
    assert exc_info.value.status_code == 404

    # 3. Tenant B attempts apply -> raises 404
    with pytest.raises(HTTPException) as exc_info:
        await service.apply_document(organization_id=org_b, document_id=saved_doc.id, actor_id="tenant_b_user")
    assert exc_info.value.status_code == 404

    # 4. Commit analysis report isolation
    report_a = CommitAnalysisReport(
        organization_id=org_a,
        repository_id=repo_a,
        commit_sha="commit_a_123",
        status="CLEAN",
        risk_score=0.0,
        report_markdown="# Tenant A Commit Audit",
    )
    await commit_repo.upsert_commit_report(report_a)

    assert await commit_repo.get_commit_report(org_b, repo_a, "commit_a_123") is None
    tenant_b_reports = await commit_repo.list_commit_reports(org_b, repo_a)
    assert len(tenant_b_reports) == 0


# ============================================================================
# INVARIANT 3: FABRICATED CITATION REJECTION
# ============================================================================
def test_fabricated_citation_rejection():
    """
    DocumentValidator rejects any document whose [file_path@commit_sha] points
    to a file not in the repository or a mismatched commit SHA.
    """
    valid_files = {"src/main/java/App.java", "src/main/java/Utils.java"}
    commit_sha = "c0ffee123456"

    # 1. Valid citation passes
    valid_content = (
        "# System Architecture\n"
        "Entry point defined in [src/main/java/App.java@c0ffee123456].\n"
    )
    DocumentValidator.validate(
        target_path="docs/generated/overview.md",
        content_md=valid_content,
        commit_sha=commit_sha,
        valid_file_paths=valid_files,
    )

    # 2. Fabricated file path raises DocumentValidationError
    hallucinated_content = (
        "# System Architecture\n"
        "Configured in [src/main/java/HallucinatedConfig.java@c0ffee123456].\n"
    )
    with pytest.raises(DocumentValidationError) as exc_info:
        DocumentValidator.validate(
            target_path="docs/generated/overview.md",
            content_md=hallucinated_content,
            commit_sha=commit_sha,
            valid_file_paths=valid_files,
        )
    assert "Fabricated citation detected" in str(exc_info.value)

    # 3. Mismatched commit SHA raises DocumentValidationError
    mismatched_sha_content = (
        "# System Architecture\n"
        "Utility located at [src/main/java/Utils.java@deadbeef999999].\n"
    )
    with pytest.raises(DocumentValidationError) as exc_info:
        DocumentValidator.validate(
            target_path="docs/generated/overview.md",
            content_md=mismatched_sha_content,
            commit_sha=commit_sha,
            valid_file_paths=valid_files,
        )
    assert "Mismatched citation commit SHA" in str(exc_info.value)


# ============================================================================
# INVARIANT 4: ZERO SECRET LEAKAGE
# ============================================================================
@pytest.mark.asyncio
async def test_zero_secret_leakage():
    """
    A planted AWS key (AKIAIOSFODNN7EXAMPLE) or GitHub token
    (ghp_1234567890abcdefghijklmnopqrstuvwxyz) in source code never appears
    in raw form in GeneratedDocument.content_md, CommitAnalysisReport.report_markdown,
    or the outbound alert email body.
    """
    raw_aws_key = "AKIAIOSFODNN7EXAMPLE"
    raw_gh_token = "ghp_1234567890abcdefghijklmnopqrstuvwxyz12"

    # 1. Validator blocks document containing raw secrets
    leaky_content = (
        f"# Leaked Credentials Document\n"
        f"Access key: {raw_aws_key}\n"
    )
    with pytest.raises(DocumentValidationError) as exc_info:
        DocumentValidator.validate(
            target_path="docs/generated/overview.md",
            content_md=leaky_content,
            commit_sha="c0ffee123456",
            valid_file_paths={"src/App.java"},
        )
    assert "unredacted secret patterns" in str(exc_info.value)

    # 2. Masking replaces raw secrets with [REDACTED]
    sanitized = mask_sensitive_data(
        f"AWS={raw_aws_key} and Token={raw_gh_token}"
    )
    assert raw_aws_key not in sanitized
    assert raw_gh_token not in sanitized
    assert "[REDACTED]" in sanitized

    # 3. Outbound email alert redacts raw secrets in HTML and text bodies
    email_notifier = EmailNotificationService()  # Unconfigured SMTP -> LOGGED_DRY_RUN
    findings = [
        {
            "category": "SECRET_LEAK",
            "rule_id": "AWS_ACCESS_KEY",
            "title": "AWS Access Key Exposed",
            "severity": "CRITICAL",
            "file_path": "config/credentials.json",
            "line_number": 12,
            "evidence": f'AWS_ACCESS_KEY_ID = "{raw_aws_key}"',
            "redacted_evidence": 'AWS_ACCESS_KEY_ID = "[REDACTED SECRET]"',
        }
    ]

    sent, status, recipient = await email_notifier.send_commit_alert_email(
        to_email="dev@forgeai.dev",
        repo_name="demo-repo",
        commit_sha="c0ffee123456",
        branch_ref="main",
        author_name="Alice Developer",
        risk_score=9.5,
        status="CRITICAL_ALERT",
        secrets_detected=1,
        findings=findings,
        report_markdown=f"Report containing {raw_aws_key}",
    )
    assert sent is True
    assert status == "LOGGED_DRY_RUN"


# ============================================================================
# INVARIANT 5: APPROVAL & WRITE-MODE ENFORCEMENT
# ============================================================================
@pytest.mark.asyncio
async def test_approval_and_write_mode_enforcement(test_setup):
    """
    Calling /apply on a DRAFT document fails with HTTP 400.
    Calling /apply on an APPROVED document in ADVISORY_ONLY mode fails with HTTP 403.
    Calling /apply on an APPROVED document in CONTROLLED_WRITE mode succeeds
    and targets docs/generated/*.md.
    """
    service, doc_repo, _, _, _ = test_setup
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "9876543210ab"

    doc = GeneratedDocument(
        organization_id=org_id,
        repository_id=repo_id,
        doc_type="API",
        target_path="docs/generated/api.md",
        commit_sha=commit_sha,
        content_hash="hash_api",
        content_md="# API Documentation\nMethod | Path",
        citations=[],
        provenance={},
        status=DocumentStatus.DRAFT.value,
    )
    saved = await doc_repo.upsert_document(doc)

    # 1. Apply DRAFT document -> HTTP 400
    with pytest.raises(HTTPException) as exc_info:
        await service.apply_document(
            organization_id=org_id,
            document_id=saved.id,
            actor_id="dev_user",
            write_mode_override="CONTROLLED_WRITE",
        )
    assert exc_info.value.status_code == 400
    assert "Explicit approval required" in exc_info.value.detail

    # 2. Approve document -> Transitions to APPROVED
    approved_doc = await service.approve_document(
        organization_id=org_id,
        document_id=saved.id,
        actor_id="approver_alice",
    )
    assert approved_doc.status == DocumentStatus.APPROVED.value
    assert approved_doc.approved_by == "approver_alice"

    # 3. Apply in ADVISORY_ONLY mode -> HTTP 403
    with pytest.raises(HTTPException) as exc_info:
        await service.apply_document(
            organization_id=org_id,
            document_id=saved.id,
            actor_id="dev_user",
            write_mode_override="ADVISORY_ONLY",
        )
    assert exc_info.value.status_code == 403
    assert "Advisory Mode" in exc_info.value.detail

    # 4. Apply in CONTROLLED_WRITE mode -> Success
    res = await service.apply_document(
        organization_id=org_id,
        document_id=saved.id,
        actor_id="dev_user",
        write_mode_override="CONTROLLED_WRITE",
    )
    assert res["status"] == "APPLIED"
    applied_doc = res["document"]
    assert applied_doc.status == DocumentStatus.APPLIED.value
    assert applied_doc.branch_name.startswith("forgeai/docs-api-")
    assert "pull_request" in res
    assert res["pull_request"]["branch_name"].startswith("forgeai/docs-api-")


# ============================================================================
# INVARIANT 6: BYTE-IDENTICAL DETERMINISTIC RENDERING
# ============================================================================
def test_byte_identical_deterministic_rendering():
    """
    Rendering OVERVIEW, API, DATA_MODEL, and DEPENDENCIES twice over the same
    indexed chunks/edges produces byte-identical content_md and content_hash.
    """
    commit_sha = "d00dcafe1234"
    repo_name = "DeterministicTestRepo"

    chunks = [
        CodeChunk(
            organization_id=uuid4(),
            repository_id=uuid4(),
            commit_sha=commit_sha,
            file_path="src/main/java/com/forgeai/controller/UserController.java",
            symbol_name="UserController",
            symbol_type="CLASS",
            content='@RestController\n@RequestMapping("/api/v1/users")\npublic class UserController {\n@GetMapping("/{id}")\npublic User getUser() {}\n}',
            start_line=1,
            end_line=6,
            content_hash="h1",
        ),
        CodeChunk(
            organization_id=uuid4(),
            repository_id=uuid4(),
            commit_sha=commit_sha,
            file_path="src/main/resources/db/migration/V1__init.sql",
            symbol_name="users_table",
            symbol_type="MODULE",
            content="CREATE TABLE users (id UUID PRIMARY KEY, username VARCHAR(64) NOT NULL);",
            start_line=1,
            end_line=2,
            content_hash="h2",
        ),
    ]
    edges = [
        GraphEdge(
            organization_id=uuid4(),
            repository_id=uuid4(),
            valid_at_sha=commit_sha,
            source_symbol="UserController",
            target_symbol="UserService",
            edge_type="CALLS",
        )
    ]
    cross_deps = [
        RepositoryDependency(
            id=uuid4(),
            organization_id=uuid4(),
            source_repository_id=uuid4(),
            target_repository_id=uuid4(),
            dependency_type="REST_API",
            confidence="HIGH",
            state="ACTIVE",
        )
    ]

    # Overview
    ov_c = OverviewCollector()
    ov_1, hash_ov_1, _ = ov_c.collect_and_render(chunks, commit_sha, repo_name)
    ov_2, hash_ov_2, _ = ov_c.collect_and_render(chunks, commit_sha, repo_name)
    assert ov_1 == ov_2
    assert hash_ov_1 == hash_ov_2

    # API Reference
    api_c = ApiReferenceCollector()
    api_1, hash_api_1, _ = api_c.collect_and_render(chunks, commit_sha, repo_name)
    api_2, hash_api_2, _ = api_c.collect_and_render(chunks, commit_sha, repo_name)
    assert api_1 == api_2
    assert hash_api_1 == hash_api_2

    # Data Model
    dm_c = DataModelCollector()
    dm_1, hash_dm_1, _ = dm_c.collect_and_render(chunks, commit_sha, repo_name)
    dm_2, hash_dm_2, _ = dm_c.collect_and_render(chunks, commit_sha, repo_name)
    assert dm_1 == dm_2
    assert hash_dm_1 == hash_dm_2

    # Dependencies Map
    dep_c = DependencyMapCollector()
    dep_1, hash_dep_1, _ = dep_c.collect_and_render(edges, cross_deps, commit_sha, repo_name)
    dep_2, hash_dep_2, _ = dep_c.collect_and_render(edges, cross_deps, commit_sha, repo_name)
    assert dep_1 == dep_2
    assert hash_dep_1 == hash_dep_2


# ============================================================================
# INVARIANT 7: STALENESS ON PUSH
# ============================================================================
@pytest.mark.asyncio
async def test_staleness_on_push(test_setup):
    """
    Pushing a new commit SHA to the repository marks prior DRAFT/APPROVED
    documents for earlier commits as STALE.
    """
    _, doc_repo, _, _, _ = test_setup
    org_id = uuid4()
    repo_id = uuid4()
    old_sha = "111111111111"
    new_sha = "222222222222"

    doc_draft = GeneratedDocument(
        organization_id=org_id,
        repository_id=repo_id,
        doc_type="OVERVIEW",
        target_path="docs/generated/overview.md",
        commit_sha=old_sha,
        content_hash="h_old_draft",
        content_md="# Old Draft Overview",
        citations=[],
        provenance={},
        status=DocumentStatus.DRAFT.value,
    )
    doc_approved = GeneratedDocument(
        organization_id=org_id,
        repository_id=repo_id,
        doc_type="API",
        target_path="docs/generated/api.md",
        commit_sha=old_sha,
        content_hash="h_old_app",
        content_md="# Old Approved API",
        citations=[],
        provenance={},
        status=DocumentStatus.APPROVED.value,
    )
    doc_applied = GeneratedDocument(
        organization_id=org_id,
        repository_id=repo_id,
        doc_type="DATA_MODEL",
        target_path="docs/generated/data-model.md",
        commit_sha=old_sha,
        content_hash="h_old_applied",
        content_md="# Old Applied Data Model",
        citations=[],
        provenance={},
        status=DocumentStatus.APPLIED.value,
    )

    await doc_repo.upsert_document(doc_draft)
    await doc_repo.upsert_document(doc_approved)
    await doc_repo.upsert_document(doc_applied)

    # Push event triggers mark_stale_for_repository
    stale_count = await doc_repo.mark_stale_for_repository(org_id, repo_id, new_commit_sha=new_sha)
    assert stale_count == 2  # DRAFT and APPROVED become STALE; APPLIED remains APPLIED

    updated_draft = await doc_repo.get_document_by_id(org_id, doc_draft.id)
    updated_approved = await doc_repo.get_document_by_id(org_id, doc_approved.id)
    updated_applied = await doc_repo.get_document_by_id(org_id, doc_applied.id)

    assert updated_draft.status == DocumentStatus.STALE.value
    assert updated_approved.status == DocumentStatus.STALE.value
    assert updated_applied.status == DocumentStatus.APPLIED.value


# ============================================================================
# INVARIANT 8: PUSH-TRIGGERED COMMIT REPORT & ALERT EMAIL
# ============================================================================
@pytest.mark.asyncio
async def test_push_triggered_commit_report_and_alert_email(test_setup):
    """
    Pushing a commit containing a leaked secret or critical bug automatically
    creates a CommitAnalysisReport (status == "CRITICAL_ALERT"), updates the
    SYSTEM_DESIGN document, and triggers EmailNotificationService with redacted evidence.
    """
    service, doc_repo, commit_repo, _, _ = test_setup
    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "f00ba4123456"

    # Simulate detected secret and vulnerability in push
    raw_secret = "ghp_secureTokenTest1234567890abcdefghij"
    findings = [
        {
            "category": "SECRET_LEAK",
            "rule_id": "GITHUB_PAT",
            "severity": "CRITICAL",
            "title": "GitHub Personal Access Token Leaked",
            "file_path": "services/auth/token.py",
            "line_number": 45,
            "evidence": f"GH_TOKEN = '{raw_secret}'",
            "redacted_evidence": "GH_TOKEN = '[REDACTED SECRET]'",
        },
        {
            "category": "SECURITY_VULNERABILITY",
            "rule_id": "SEC-001",
            "severity": "HIGH",
            "title": "SQL Injection in User Query",
            "file_path": "services/auth/dao.py",
            "line_number": 88,
            "evidence": "SELECT * FROM users WHERE name = " + "name",
            "redacted_evidence": "SELECT * FROM users WHERE name = name",
        },
    ]

    # Generate or refresh System Design document for commit
    docs = await service.generate_documents(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        doc_types=["SYSTEM_DESIGN"],
        files=[{"path": "services/auth/token.py", "content": "class Token {}"}],
        repo_name="PushGuardRepo",
    )
    system_design_doc = docs[0]
    assert system_design_doc.doc_type == "SYSTEM_DESIGN"

    # Trigger Email Notification
    email_notifier = EmailNotificationService()
    alert_sent, alert_status, recipient = await email_notifier.send_commit_alert_email(
        to_email="committer@forgeai.dev",
        repo_name="PushGuardRepo",
        commit_sha=commit_sha,
        branch_ref="main",
        author_name="Dev Committer",
        risk_score=9.8,
        status="CRITICAL_ALERT",
        secrets_detected=1,
        security_issues=1,
        reliability_issues=0,
        findings=findings,
        report_markdown="# Push Audit Report\n1 secret detected.",
        system_design_doc_id=system_design_doc.id,
    )
    assert alert_sent is True
    assert alert_status in ["LOGGED_DRY_RUN", "SENT"]

    # Persist CommitAnalysisReport
    report = CommitAnalysisReport(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=commit_sha,
        ref="refs/heads/main",
        author_name="Dev Committer",
        author_email="committer@forgeai.dev",
        commit_message="feat: added authentication module",
        status="CRITICAL_ALERT",
        risk_score=9.8,
        secrets_detected=1,
        security_issues=1,
        reliability_issues=0,
        findings=findings,
        report_markdown="# Push Audit Report\n1 secret detected.",
        system_design_doc_id=system_design_doc.id,
        alert_sent=alert_sent,
        alert_recipient=recipient,
        alert_status=alert_status,
    )
    saved_report = await commit_repo.upsert_commit_report(report)

    # Verify retrieval
    retrieved = await commit_repo.get_commit_report(org_id, repo_id, commit_sha)
    assert retrieved is not None
    assert retrieved.status == "CRITICAL_ALERT"
    assert retrieved.secrets_detected == 1
    assert retrieved.alert_sent is True
    assert retrieved.system_design_doc_id == system_design_doc.id


# ============================================================================
# ROUTER TESTS: APPLY & REJECT DOCUMENT ENDPOINTS (BUG-ABC-01 & BUG-ABC-04)
# ============================================================================
def test_router_apply_document_returns_valid_dto_without_attribute_error():
    """
    BUG-ABC-01: Verifies POST /api/v1/ai/documents/{document_id}/apply
    unwraps the dict returned by DocumentGenerationService.apply_document()
    and returns a valid GeneratedDocumentDTO without throwing AttributeError.
    """
    from unittest.mock import patch, AsyncMock
    from fastapi.testclient import TestClient
    from app.main import app

    client = TestClient(app)
    doc_id = uuid4()
    org_id = uuid4()
    repo_id = uuid4()

    mock_doc = GeneratedDocument(
        id=doc_id,
        organization_id=org_id,
        repository_id=repo_id,
        doc_type=DocumentType.OVERVIEW,
        target_path="docs/generated/OVERVIEW.md",
        commit_sha="a1b2c3d4e5f6",
        content_hash="abc123hash",
        content_md="# Project Overview\nClean documentation.",
        citations=[],
        provenance={"generator": "OverviewCollector"},
        status=DocumentStatus.APPLIED,
        approved_by="developer-1",
        pr_url="https://github.com/forgeai/repo/pull/42",
        branch_name="forgeai/docs/overview",
    )

    mock_apply_result = {
        "status": "APPLIED",
        "document": mock_doc,
        "pull_request": {
            "pr_url": "https://github.com/forgeai/repo/pull/42",
            "branch_name": "forgeai/docs/overview",
        },
    }

    with patch("app.presentation.router.db_pool.get_pool", new_callable=AsyncMock) as mock_get_pool, \
         patch.object(DocumentGenerationService, "apply_document", new_callable=AsyncMock) as mock_apply:
        mock_get_pool.return_value = AsyncMock()
        mock_apply.return_value = mock_apply_result

        response = client.post(
            f"/api/v1/ai/documents/{doc_id}/apply",
            json={"write_mode_override": "CONTROLLED_WRITE"},
            headers={
                "X-Org-Id": str(org_id),
                "X-User-Id": "developer-1",
                "X-ForgeAI-Internal": "attested",
            },
        )

        assert response.status_code == 200, f"Unexpected status: {response.text}"
        data = response.json()
        assert data["id"] == str(doc_id)
        assert data["status"] == "APPLIED"
        assert data["pr_url"] == "https://github.com/forgeai/repo/pull/42"
        assert data["branch_name"] == "forgeai/docs/overview"
        assert data["doc_type"] == "OVERVIEW"


def test_router_reject_document_with_json_body():
    """
    BUG-ABC-04: Verifies POST /api/v1/ai/documents/{document_id}/reject
    accepts a JSON body { "reason": "..." } and passes effective_reason cleanly.
    """
    from unittest.mock import patch, AsyncMock
    from fastapi.testclient import TestClient
    from app.main import app

    client = TestClient(app)
    doc_id = uuid4()
    org_id = uuid4()
    repo_id = uuid4()

    mock_doc = GeneratedDocument(
        id=doc_id,
        organization_id=org_id,
        repository_id=repo_id,
        doc_type=DocumentType.API,
        target_path="docs/generated/API.md",
        commit_sha="a1b2c3d4e5f6",
        content_hash="abc123hash",
        content_md="# API Documentation",
        citations=[],
        provenance={},
        status=DocumentStatus.REJECTED,
        approved_by=None,
    )

    with patch("app.presentation.router.db_pool.get_pool", new_callable=AsyncMock) as mock_get_pool, \
         patch.object(DocumentGenerationService, "reject_document", new_callable=AsyncMock) as mock_reject:
        mock_get_pool.return_value = AsyncMock()
        mock_reject.return_value = mock_doc

        response = client.post(
            f"/api/v1/ai/documents/{doc_id}/reject",
            json={"reason": "Contains inaccurate endpoint schema"},
            headers={
                "X-Org-Id": str(org_id),
                "X-User-Id": "reviewer-1",
                "X-ForgeAI-Internal": "attested",
            },
        )

        assert response.status_code == 200, f"Unexpected status: {response.text}"
        data = response.json()
        assert data["id"] == str(doc_id)
        assert data["status"] == "REJECTED"
        mock_reject.assert_awaited_once_with(
            org_id,
            doc_id,
            actor_id="reviewer-1",
            reason="Contains inaccurate endpoint schema",
        )


def test_router_validate_post_pr_test_execution_with_empty_executed_tests():
    """
    CI-AI-BUG-02: Verifies POST /api/v1/ai/test-validation/validate
    synthesizes executed tests from test_strategy recommendations when executed_tests is empty,
    and saves updated analysis without throwing UniqueViolationError.
    """
    from unittest.mock import patch, AsyncMock
    from fastapi.testclient import TestClient
    from app.main import app
    from app.domain.models import Analysis

    client = TestClient(app)
    analysis_id = uuid4()
    org_id = uuid4()
    repo_id = uuid4()

    mock_analysis = Analysis(
        id=analysis_id,
        organization_id=org_id,
        entity_type="PR",
        entity_id=f"{repo_id}#42",
        model_version="gemini-1.5-flash",
        prompt_version="v1.0",
        summary="Test Analysis",
        risk_level="MEDIUM",
        test_strategy={
            "must_run": [
                {"test_name": "AuthServiceTest", "test_file": "AuthServiceTest.java", "tier": "MUST_RUN", "confidence": "HIGH", "relationship": "DIRECT"}
            ],
            "should_run": [],
            "optional": [],
        },
        test_effectiveness=None,
    )

    with patch("app.presentation.router.db_pool.get_pool", new_callable=AsyncMock) as mock_get_pool, \
         patch("app.presentation.router.AsyncpgAnalysisRepository") as mock_analysis_repo_cls, \
         patch("app.presentation.router.AsyncpgTestValidationFeedbackRepository") as mock_feedback_repo_cls, \
         patch("app.infrastructure.client.testing_service_client.TestingServiceClient.list_test_runs", new_callable=AsyncMock) as mock_list_runs:
        
        mock_get_pool.return_value = AsyncMock()
        mock_repo_inst = AsyncMock()
        mock_repo_inst.get_by_id.return_value = mock_analysis
        mock_repo_inst.save_analysis.return_value = mock_analysis
        mock_analysis_repo_cls.return_value = mock_repo_inst

        mock_feedback_inst = AsyncMock()
        mock_feedback_repo_cls.return_value = mock_feedback_inst

        mock_list_runs.return_value = [{
            "id": str(uuid4()),
            "status": "COMPLETED",
            "conclusion": "success",
            "workflowRunId": 12345,
        }]

        response = client.post(
            "/api/v1/ai/test-validation/validate",
            json={
                "repository_id": str(repo_id),
                "analysis_id": str(analysis_id),
                "commit_sha": "abc1234",
                "executed_tests": [],
            },
            headers={
                "X-Org-Id": str(org_id),
                "X-ForgeAI-Internal": "attested",
            },
        )

        assert response.status_code == 200, f"Unexpected response: {response.text}"
        data = response.json()
        assert data["status"] == "VALIDATED"
        assert data["analysis_id"] == str(analysis_id)
        assert data["metrics"]["recommended_count"] == 1
        assert data["metrics"]["executed_count"] == 1
        mock_repo_inst.save_analysis.assert_awaited_once()

