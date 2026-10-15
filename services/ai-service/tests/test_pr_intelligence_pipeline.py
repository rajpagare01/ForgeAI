import pytest
from uuid import uuid4
from unittest.mock import AsyncMock, patch

from app.domain.models import IndexJob, IndexJobStatus, CodeChunk, GraphEdge, RiskLevel
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryAnalysisRepository,
    InMemoryIndexJobRepository,
)
from app.application.retrieval_service import RetrievalService
from app.application.indexing_service import IndexingService
from app.application.gemini_orchestration_service import GeminiOrchestrationService
from app.infrastructure.parser.tree_sitter_parser import TreeSitterParser
from app.infrastructure.client.source_control_client import SourceControlClient
from app.infrastructure.llm.gemini_client import GeminiClient


def test_static_ast_symbol_derivation_java_and_python():
    """
    Phase 3: Validates deterministic static AST symbol extraction from PR file diffs.
    """
    parser = TreeSitterParser()

    java_code = """
    package com.forgeai.billing;

    public class InvoiceService {
        public void generateInvoice(String orderId) {
            System.out.println("Generating invoice");
        }

        public void cancelInvoice(String invoiceId) {
            System.out.println("Cancelling invoice");
        }
    }
    """
    java_patch = """
    @@ -5,3 +5,6 @@
    +    public void generateInvoice(String orderId) {
    +        System.out.println("Generating invoice");
    +    }
    """

    py_code = """
class MetricsCollector:
    def record_latency(self, endpoint, duration_ms):
        pass

    def record_error(self, endpoint, error_code):
        pass
    """
    py_patch = """
    @@ -2,3 +2,4 @@
    +    def record_latency(self, endpoint, duration_ms):
    +        pass
    """

    files = [
        {
            "filename": "services/billing/src/main/java/com/forgeai/billing/InvoiceService.java",
            "status": "modified",
            "content": java_code,
            "patch": java_patch,
        },
        {
            "filename": "services/monitoring/collector.py",
            "status": "modified",
            "content": py_code,
            "patch": py_patch,
        },
        {
            "filename": "deploy/docker-compose.yml",
            "status": "modified",
            "content": "version: '3.8'",
            "patch": "+ version: '3.8'",
        },
    ]

    symbols = parser.derive_changed_symbols(files)

    # Assert Java symbol was extracted
    assert any("generateInvoice" in s for s in symbols)
    # Assert Python symbol was extracted
    assert any("record_latency" in s for s in symbols)
    # Assert non-code file fell back to file-level symbol
    assert "deploy/docker-compose.yml" in symbols


@pytest.mark.asyncio
async def test_automatic_indexing_when_commit_missing():
    """
    Phase 4 & 5: When PR references an unindexed head commit, ForgeAI durably
    indexes the relevant files and marks the job COMPLETED.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    job_repo = InMemoryIndexJobRepository()
    llm_client = GeminiClient()

    indexing_service = IndexingService(chunk_repo, edge_repo, llm_client=llm_client)
    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client=llm_client)
    source_control_mock = AsyncMock(spec=SourceControlClient)

    org_id = uuid4()
    repo_id = uuid4()
    head_sha = "a1b2c3d4e5f67890"

    source_control_mock.get_pull_request_changes.return_value = {
        "repositoryId": str(repo_id),
        "repositoryFullName": "forgeai/core",
        "number": 101,
        "title": "Add Audit Logger",
        "body": "Implements audit logging",
        "headSha": head_sha,
        "baseSha": "0000000000000000",
        "files": [
            {
                "filename": "AuditLogger.java",
                "status": "added",
                "content": """
                package com.forgeai.audit;
                public class AuditLogger {
                    public void logEvent(String evt) {}
                }
                """,
                "patch": "+ public class AuditLogger {}",
            }
        ],
    }

    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        llm_client=llm_client,
        source_control_client=source_control_mock,
        indexing_service=indexing_service,
        job_repo=job_repo,
    )

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=101,
    )

    # Assert PR was analyzed
    assert analysis is not None
    assert analysis.entity_id == f"{repo_id}#101"

    # Assert job was durably created and COMPLETED
    job = await job_repo.get_job_by_commit(org_id, repo_id, head_sha)
    assert job is not None
    assert job.status == IndexJobStatus.COMPLETED

    # Assert chunks were indexed into code_chunks
    assert len(chunk_repo.chunks) >= 1
    assert any("AuditLogger" in c.symbol_name for c in chunk_repo.chunks)


@pytest.mark.asyncio
async def test_existing_indexed_commit_reused_without_duplicate():
    """
    Phase 4: If the commit is already indexed, the existing state is reused
    without creating duplicate jobs.
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    job_repo = InMemoryIndexJobRepository()
    llm_client = GeminiClient()

    indexing_service = IndexingService(chunk_repo, edge_repo, llm_client=llm_client)
    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client=llm_client)
    source_control_mock = AsyncMock(spec=SourceControlClient)

    org_id = uuid4()
    repo_id = uuid4()
    head_sha = "ff99887766554433"

    # Pre-populate completed index job
    existing_job = IndexJob(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha=head_sha,
        status=IndexJobStatus.COMPLETED,
        files=[{"file_path": "Foo.java", "content": "class Foo {}"}],
    )
    await job_repo.create_job(existing_job)
    initial_job_count = len(job_repo.jobs)

    source_control_mock.get_pull_request_changes.return_value = {
        "repositoryId": str(repo_id),
        "repositoryFullName": "forgeai/core",
        "number": 102,
        "title": "Modify Foo",
        "body": "Updates Foo",
        "headSha": head_sha,
        "files": [{"filename": "Foo.java", "status": "modified", "content": "class Foo {}"}],
    }

    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        llm_client=llm_client,
        source_control_client=source_control_mock,
        indexing_service=indexing_service,
        job_repo=job_repo,
    )

    with patch.object(indexing_service, "index_file", wraps=indexing_service.index_file) as mock_index_file:
        await orchestration_service.analyze_pull_request(
            organization_id=org_id,
            repository_id=repo_id,
            pr_number=102,
        )
        # index_file should NOT have been called because commit was already indexed
        mock_index_file.assert_not_called()

    # Job count should remain 1
    assert len(job_repo.jobs) == initial_job_count


@pytest.mark.asyncio
async def test_provenance_correctness_in_analysis():
    """
    Phase 12: Verifies that retrieved evidence records provenance (source types PR_DIFF, GRAPH, VECTOR).
    """
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    analysis_repo = InMemoryAnalysisRepository()
    llm_client = GeminiClient()

    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "9876543210abcdef"

    # Add code chunk
    await chunk_repo.save_chunk(
        CodeChunk(
            organization_id=org_id,
            repository_id=repo_id,
            commit_sha=commit_sha,
            file_path="src/Service.java",
            symbol_name="com.forgeai.Service#execute",
            symbol_type="METHOD",
            content="public void execute() {}",
            content_hash="hash1",
            embedding=[0.1] * 768,
        )
    )

    # Add graph edge
    await edge_repo.save_edge(
        GraphEdge(
            organization_id=org_id,
            repository_id=repo_id,
            source_symbol="com.forgeai.Controller#handle",
            target_symbol="com.forgeai.Service#execute",
            edge_type="CALLS",
            valid_at_sha=commit_sha,
        )
    )

    retrieval_service = RetrievalService(chunk_repo, edge_repo, llm_client=llm_client)
    orchestration_service = GeminiOrchestrationService(
        retrieval_service=retrieval_service,
        analysis_repo=analysis_repo,
        llm_client=llm_client,
    )

    analysis = await orchestration_service.analyze_pull_request(
        organization_id=org_id,
        repository_id=repo_id,
        pr_number=205,
        title="Execute Service Call",
        description="Calls execute in service",
        changed_symbols=["com.forgeai.Service#execute"],
        changed_files=[{"filename": "src/Service.java", "status": "modified", "additions": 5, "deletions": 1}],
    )

    # Assert provenance records contain PR_DIFF, GRAPH, and VECTOR
    sources = {c.get("source") for c in analysis.retrieved_chunks}
    assert "PR_DIFF" in sources
    assert "GRAPH" in sources
    assert "VECTOR" in sources
