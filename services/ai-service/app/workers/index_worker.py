import asyncio
import logging
from typing import Dict, Any, List, Optional
from uuid import UUID
from app.application.indexing_service import IndexingService
from app.domain.models import IndexJob, IndexJobStatus
from app.domain.repositories import IndexJobRepository
from app.infrastructure.persistence.in_memory_repositories import InMemoryIndexJobRepository

logger = logging.getLogger(__name__)


class DurableIndexWorker:
    """
    Architecture-compliant Durable PostgreSQL-backed Index Job Queue and Worker.
    Guarantees:
      - Work is durably persisted in PostgreSQL (ai_indexing_jobs table).
      - Accepted jobs survive AI container/worker restarts.
      - Worker recovers uncompleted 'PROCESSING' jobs on startup.
      - Bounded retries, attempt count tracking, and failure reasoning.
    """
    def __init__(
        self,
        indexing_service: IndexingService,
        job_repo: Optional[IndexJobRepository] = None,
    ):
        self.indexing_service = indexing_service
        if job_repo is None:
            raise ValueError(
                "job_repo is required to initialize DurableIndexWorker. "
                "Production paths must provide AsyncpgIndexJobRepository; "
                "unit tests may pass InMemoryIndexJobRepository explicitly."
            )
        self.job_repo = job_repo
        self._running = False
        self._worker_task: Optional[asyncio.Task] = None
        self._poll_interval = 0.5

    async def enqueue_index_job(
        self,
        organization_id: UUID,
        repository_id: UUID,
        commit_sha: str,
        files: List[Dict[str, str]],  # [{"file_path": "...", "content": "..."}]
    ) -> IndexJob:
        """
        Durably enqueues an asynchronous indexing job into the database.
        Returns immediately with status QUEUED to keep HTTP requests non-blocking.
        """
        job = IndexJob(
            organization_id=organization_id,
            repository_id=repository_id,
            commit_sha=commit_sha,
            files=files,
            status=IndexJobStatus.QUEUED,
        )
        saved_job = await self.job_repo.create_job(job)
        logger.info(
            "Durably enqueued index job %s for repo %s (sha=%s, files=%d)",
            saved_job.id,
            repository_id,
            commit_sha[:8],
            len(files),
        )
        return saved_job

    async def start_worker(self):
        if self._running:
            return
        self._running = True

        # 1. Recover any stale PROCESSING jobs left from previous container crash/restart
        try:
            recovered_count = await self.job_repo.recover_stale_jobs()
            if recovered_count > 0:
                logger.info("Durable worker recovered %d stale index jobs on startup.", recovered_count)
        except Exception as e:
            logger.warning("Could not recover stale jobs on startup (%s)", e)

        # 2. Launch background polling loop
        self._worker_task = asyncio.create_task(self._process_loop())
        logger.info("Durable repository indexing background worker started.")

    async def stop_worker(self):
        self._running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
        logger.info("Durable repository indexing background worker stopped.")

    async def _process_loop(self):
        while self._running:
            try:
                job = await self.job_repo.acquire_next_queued_job()
                if job is None:
                    await asyncio.sleep(self._poll_interval)
                    continue

                org_id = job.organization_id
                repo_id = job.repository_id
                commit_sha = job.commit_sha
                files = job.files

                logger.info(
                    "Processing index job %s for repo %s (attempt %d/%d, %d files)",
                    job.id,
                    repo_id,
                    job.attempt_count,
                    job.max_attempts,
                    len(files),
                )

                for f in files:
                    await self.indexing_service.index_file(
                        organization_id=org_id,
                        repository_id=repo_id,
                        commit_sha=commit_sha,
                        file_path=f["file_path"],
                        content=f["content"],
                    )

                await self.job_repo.mark_job_status(job.id, "COMPLETED")
                logger.info("Index job %s successfully COMPLETED.", job.id)

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Error processing index job: %s", e, exc_info=True)
                if 'job' in locals() and job is not None:
                    try:
                        await self.job_repo.mark_job_status(job.id, "FAILED", str(e))
                    except Exception as me:
                        logger.error("Could not update job status to FAILED: %s", me)
                await asyncio.sleep(1.0)


# Backwards compatibility alias
IndexJobQueue = DurableIndexWorker
