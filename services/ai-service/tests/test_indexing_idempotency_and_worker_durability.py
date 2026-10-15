import asyncio
import pytest
from uuid import uuid4
from app.domain.models import IndexJob, IndexJobStatus
from app.application.indexing_service import IndexingService
from app.infrastructure.persistence.in_memory_repositories import (
    InMemoryCodeChunkRepository,
    InMemoryGraphEdgeRepository,
    InMemoryIndexJobRepository,
)
from app.workers.index_worker import DurableIndexWorker


@pytest.mark.asyncio
async def test_job_enqueue_idempotency_same_commit():
    """First enqueue creates one job; second enqueue for same org+repo+commit returns existing job."""
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    job_repo = InMemoryIndexJobRepository()
    indexing_service = IndexingService(chunk_repo, edge_repo)
    worker = DurableIndexWorker(indexing_service, job_repo=job_repo)

    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "a" * 40
    files = [{"file_path": "App.java", "content": "class App {}"}]

    # 1. First enqueue
    job1 = await worker.enqueue_index_job(org_id, repo_id, commit_sha, files)
    assert job1.id is not None
    assert job1.commit_sha == commit_sha
    assert len(job_repo.jobs) == 1

    # 2. Second identical enqueue
    job2 = await worker.enqueue_index_job(org_id, repo_id, commit_sha, files)
    assert job2.id == job1.id
    assert len(job_repo.jobs) == 1


@pytest.mark.asyncio
async def test_concurrent_enqueue_same_commit():
    """Concurrent enqueue requests for the exact same commit result in exactly one logical job."""
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    job_repo = InMemoryIndexJobRepository()
    indexing_service = IndexingService(chunk_repo, edge_repo)
    worker = DurableIndexWorker(indexing_service, job_repo=job_repo)

    org_id = uuid4()
    repo_id = uuid4()
    commit_sha = "c" * 40
    files = [{"file_path": "Main.java", "content": "class Main {}"}]

    tasks = [
        worker.enqueue_index_job(org_id, repo_id, commit_sha, files)
        for _ in range(5)
    ]
    results = await asyncio.gather(*tasks)

    # All returned jobs must have the same ID
    job_ids = {j.id for j in results}
    assert len(job_ids) == 1
    assert len(job_repo.jobs) == 1


@pytest.mark.asyncio
async def test_enqueue_different_commits_preserves_both():
    """Legitimate indexing of different commits creates distinct jobs."""
    chunk_repo = InMemoryCodeChunkRepository()
    edge_repo = InMemoryGraphEdgeRepository()
    job_repo = InMemoryIndexJobRepository()
    indexing_service = IndexingService(chunk_repo, edge_repo)
    worker = DurableIndexWorker(indexing_service, job_repo=job_repo)

    org_id = uuid4()
    repo_id = uuid4()
    commit1 = "1" * 40
    commit2 = "2" * 40

    j1 = await worker.enqueue_index_job(org_id, repo_id, commit1, [{"file_path": "f.txt", "content": "1"}])
    j2 = await worker.enqueue_index_job(org_id, repo_id, commit2, [{"file_path": "f.txt", "content": "2"}])

    assert j1.id != j2.id
    assert len(job_repo.jobs) == 2


@pytest.mark.asyncio
async def test_worker_crash_recovery_resets_processing_to_queued():
    """Crash recovery properly resets all PROCESSING jobs back to QUEUED."""
    job_repo = InMemoryIndexJobRepository()
    org_id = uuid4()
    repo_id = uuid4()

    job = IndexJob(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha="4" * 40,
        status=IndexJobStatus.PROCESSING,
        files=[{"file_path": "Test.java", "content": "class Test {}"}],
    )
    await job_repo.create_job(job)

    recovered = await job_repo.recover_stale_jobs()
    assert recovered == 1

    persisted = await job_repo.get_job_by_id(job.id)
    assert persisted.status == IndexJobStatus.QUEUED


@pytest.mark.asyncio
async def test_worker_retry_limit_and_permanent_failure():
    """Worker retries failed jobs until max_attempts, then transitions to permanent FAILED."""
    job_repo = InMemoryIndexJobRepository()
    org_id = uuid4()
    repo_id = uuid4()

    job = IndexJob(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha="5" * 40,
        status=IndexJobStatus.QUEUED,
        max_attempts=2,
        files=[{"file_path": "Err.java", "content": "class Err {}"}],
    )
    await job_repo.create_job(job)

    # Attempt 1: acquire and fail
    acq1 = await job_repo.acquire_next_queued_job()
    assert acq1.attempt_count == 1
    await job_repo.mark_job_status(acq1.id, "FAILED", last_error="Network timeout")

    # Should be back to QUEUED because attempt_count (1) < max_attempts (2)
    reloaded1 = await job_repo.get_job_by_id(job.id)
    assert reloaded1.status == IndexJobStatus.QUEUED
    assert reloaded1.last_error == "Network timeout"

    # Attempt 2: acquire and fail
    acq2 = await job_repo.acquire_next_queued_job()
    assert acq2.attempt_count == 2
    await job_repo.mark_job_status(acq2.id, "FAILED", last_error="Corrupt syntax")

    # Should now be permanently FAILED because attempt_count (2) >= max_attempts (2)
    reloaded2 = await job_repo.get_job_by_id(job.id)
    assert reloaded2.status == IndexJobStatus.FAILED
    assert reloaded2.last_error == "Corrupt syntax"
    assert reloaded2.completed_at is not None


@pytest.mark.asyncio
async def test_worker_successful_completion():
    """Successful processing marks job as COMPLETED and clears errors."""
    job_repo = InMemoryIndexJobRepository()
    org_id = uuid4()
    repo_id = uuid4()

    job = IndexJob(
        organization_id=org_id,
        repository_id=repo_id,
        commit_sha="6" * 40,
        status=IndexJobStatus.QUEUED,
        files=[],
    )
    await job_repo.create_job(job)

    acq = await job_repo.acquire_next_queued_job()
    assert acq.status == IndexJobStatus.PROCESSING

    await job_repo.mark_job_status(acq.id, "COMPLETED")
    done = await job_repo.get_job_by_id(job.id)
    assert done.status == IndexJobStatus.COMPLETED
    assert done.last_error is None
    assert done.completed_at is not None
