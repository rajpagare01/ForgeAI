import hashlib
import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.models import CodeChunk, GraphEdge, IndexJob, IndexJobStatus
from app.domain.repositories import CodeChunkRepository, GraphEdgeRepository, IndexJobRepository
from app.infrastructure.parser.tree_sitter_parser import TreeSitterParser
from app.infrastructure.llm.gemini_client import GeminiClient

logger = logging.getLogger(__name__)


class IndexingService:
    def __init__(
        self,
        code_chunk_repo: CodeChunkRepository,
        graph_edge_repo: GraphEdgeRepository,
        parser: Optional[TreeSitterParser] = None,
        llm_client: Optional[GeminiClient] = None,
        job_repo: Optional[IndexJobRepository] = None,
    ):
        self.code_chunk_repo = code_chunk_repo
        self.graph_edge_repo = graph_edge_repo
        self.parser = parser or TreeSitterParser()
        self.llm_client = llm_client or GeminiClient()
        self.job_repo = job_repo

    async def index_file(
        self,
        organization_id: UUID,
        repository_id: UUID,
        commit_sha: str,
        file_path: str,
        content: str,
        deactivate_previous: bool = True,
    ) -> Dict[str, Any]:
        """
        Parses source code file using tree-sitter AST, generates embeddings with hash caching,
        extracts graph edges, and persists into PostgreSQL as current state.
        """
        if deactivate_previous:
            await self.code_chunk_repo.deactivate_file_chunks(organization_id, repository_id, file_path)
        parsed = self.parser.parse_code(file_path, content)
        symbols = parsed.get("symbols", [])
        raw_edges = parsed.get("edges", [])

        # Process Code Chunks
        chunks_to_insert: List[CodeChunk] = []
        cached_count = 0

        for sym in symbols:
            chunk_content = sym["content"]
            content_hash = hashlib.sha256(chunk_content.encode("utf-8")).hexdigest()

            # Hash check: if same chunk already exists for this tenant, reuse embedding
            cached_chunk = await self.code_chunk_repo.find_by_content_hash(organization_id, content_hash)
            if cached_chunk and cached_chunk.embedding:
                embedding = cached_chunk.embedding
                cached_count += 1
            else:
                embedding = await self.llm_client.generate_embedding(chunk_content)

            chunk = CodeChunk(
                organization_id=organization_id,
                repository_id=repository_id,
                commit_sha=commit_sha,
                file_path=file_path,
                symbol_name=sym.get("symbol_name"),
                symbol_type=sym.get("symbol_type"),
                content=chunk_content,
                content_hash=content_hash,
                embedding=embedding,
            )
            chunks_to_insert.append(chunk)

        saved_chunks = await self.code_chunk_repo.batch_save_chunks(chunks_to_insert)

        # Process Graph Edges
        edges_to_insert: List[GraphEdge] = []
        for re in raw_edges:
            edge = GraphEdge(
                organization_id=organization_id,
                repository_id=repository_id,
                source_symbol=re["source_symbol"],
                target_symbol=re["target_symbol"],
                edge_type=re["edge_type"],
                valid_at_sha=commit_sha,
            )
            edges_to_insert.append(edge)

        saved_edges = await self.graph_edge_repo.batch_save_edges(edges_to_insert)

        logger.info(
            f"Indexed {file_path}: {saved_chunks} chunks ({cached_count} cache hits), {saved_edges} edges"
        )
        return {
            "file_path": file_path,
            "chunks_indexed": saved_chunks,
            "cache_hits": cached_count,
            "edges_indexed": saved_edges,
        }

    async def sync_incremental_push(
        self,
        organization_id: UUID,
        repository_id: UUID,
        commit_sha: str,
        ref: Optional[str],
        files: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """
        Incrementally synchronizes a repository push event:
        1. Removed files: deactivates active chunks and related graph edges.
        2. Modified files: deactivates older active chunks, re-parses symbols/AST, reuses matching content hashes.
        3. Added files: parses symbols/AST, reuses matching content hashes or generates embeddings.
        4. Durably records/updates IndexJob state in PostgreSQL.
        """
        logger.info(
            "Starting incremental push sync for repo %s @ %s (%d files, ref=%s)",
            repository_id,
            commit_sha[:8],
            len(files),
            ref,
        )

        total_chunks = 0
        total_cache_hits = 0
        total_edges = 0
        processed_files = 0
        deactivated_files = 0

        # Persist durable job record if job_repo is present
        job = None
        if self.job_repo:
            try:
                job = IndexJob(
                    organization_id=organization_id,
                    repository_id=repository_id,
                    commit_sha=commit_sha,
                    files=[{"file_path": f.get("file_path", ""), "status": f.get("status", "")} for f in files],
                    status=IndexJobStatus.PROCESSING,
                )
                job = await self.job_repo.create_job(job)
            except Exception as e:
                logger.warning("Could not persist durable IndexJob for push (%s)", e)

        try:
            for item in files:
                file_path = item.get("file_path")
                status = item.get("status", "modified").lower()
                content = item.get("content")

                if not file_path:
                    continue

                if status == "removed":
                    count = await self.code_chunk_repo.deactivate_file_chunks(organization_id, repository_id, file_path)
                    deactivated_files += 1
                    logger.info("Deactivated %d chunks for removed file %s", count, file_path)
                elif status in ("modified", "added"):
                    if content:
                        res = await self.index_file(
                            organization_id=organization_id,
                            repository_id=repository_id,
                            commit_sha=commit_sha,
                            file_path=file_path,
                            content=content,
                            deactivate_previous=True,
                        )
                        total_chunks += res.get("chunks_indexed", 0)
                        total_cache_hits += res.get("cache_hits", 0)
                        total_edges += res.get("edges_indexed", 0)
                        processed_files += 1

            if job and self.job_repo:
                await self.job_repo.mark_job_status(job.id, "COMPLETED")

            logger.info(
                "Completed incremental push sync for repo %s @ %s: processed=%d, deactivated=%d, chunks=%d (cache_hits=%d)",
                repository_id,
                commit_sha[:8],
                processed_files,
                deactivated_files,
                total_chunks,
                total_cache_hits,
            )

            return {
                "status": "COMPLETED",
                "repository_id": str(repository_id),
                "commit_sha": commit_sha,
                "files_processed": processed_files,
                "files_deactivated": deactivated_files,
                "chunks_indexed": total_chunks,
                "cache_hits": total_cache_hits,
                "edges_indexed": total_edges,
            }
        except Exception as e:
            logger.error("Failed incremental push sync for repo %s @ %s: %s", repository_id, commit_sha, e, exc_info=True)
            if job and self.job_repo:
                await self.job_repo.mark_job_status(job.id, "FAILED", str(e))
            raise
