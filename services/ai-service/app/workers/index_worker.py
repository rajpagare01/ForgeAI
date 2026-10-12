import asyncio
import logging
from typing import Dict, Any, List
from uuid import UUID
from app.application.indexing_service import IndexingService

logger = logging.getLogger(__name__)


class IndexJobQueue:
    def __init__(self, indexing_service: IndexingService):
        self.indexing_service = indexing_service
        self._queue: asyncio.Queue[Dict[str, Any]] = asyncio.Queue()
        self._running = False
        self._worker_task: asyncio.Task | None = None

    async def enqueue_index_job(
        self,
        organization_id: UUID,
        repository_id: UUID,
        commit_sha: str,
        files: List[Dict[str, str]], # [{"file_path": "...", "content": "..."}]
    ):
        """
        Enqueues an asynchronous indexing job. Returns immediately to keep HTTP requests non-blocking.
        """
        job = {
            "organization_id": organization_id,
            "repository_id": repository_id,
            "commit_sha": commit_sha,
            "files": files,
        }
        await self._queue.put(job)
        logger.info(f"Enqueued indexing job for repo {repository_id} ({len(files)} files)")

    async def start_worker(self):
        if self._running:
            return
        self._running = True
        self._worker_task = asyncio.create_task(self._process_queue())
        logger.info("Asynchronous repository indexing background worker started.")

    async def stop_worker(self):
        self._running = False
        if self._worker_task:
            self._worker_task.cancel()
            try:
                await self._worker_task
            except asyncio.CancelledError:
                pass
        logger.info("Asynchronous repository indexing background worker stopped.")

    async def _process_queue(self):
        while self._running:
            try:
                job = await self._queue.get()
                org_id = job["organization_id"]
                repo_id = job["repository_id"]
                commit_sha = job["commit_sha"]
                files = job["files"]

                logger.info(f"Worker processing indexing job for repo {repo_id} ({len(files)} files)")
                for f in files:
                    await self.indexing_service.index_file(
                        organization_id=org_id,
                        repository_id=repo_id,
                        commit_sha=commit_sha,
                        file_path=f["file_path"],
                        content=f["content"],
                    )
                self._queue.task_done()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in background indexing worker: {e}", exc_info=True)
