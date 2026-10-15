import math
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.models import CodeChunk, GraphEdge, Analysis, IndexJob
from app.domain.repositories import (
    CodeChunkRepository,
    GraphEdgeRepository,
    AnalysisRepository,
    IndexJobRepository,
)


class InMemoryCodeChunkRepository(CodeChunkRepository):
    def __init__(self):
        self.chunks: List[CodeChunk] = []

    async def save_chunk(self, chunk: CodeChunk) -> CodeChunk:
        self.chunks.append(chunk)
        return chunk

    async def batch_save_chunks(self, chunks: List[CodeChunk]) -> int:
        self.chunks.extend(chunks)
        return len(chunks)

    async def find_by_content_hash(self, org_id: UUID, content_hash: str) -> Optional[CodeChunk]:
        for c in self.chunks:
            if c.organization_id == org_id and c.content_hash == content_hash:
                return c
        return None

    async def deactivate_file_chunks(self, org_id: UUID, repo_id: UUID, file_path: str) -> int:
        count = 0
        for c in self.chunks:
            if c.organization_id == org_id and c.repository_id == repo_id and c.file_path == file_path and c.is_current:
                c.is_current = False
                count += 1
        return count

    async def deactivate_all_repo_chunks(self, org_id: UUID, repo_id: UUID) -> int:
        count = 0
        for c in self.chunks:
            if c.organization_id == org_id and c.repository_id == repo_id and c.is_current:
                c.is_current = False
                count += 1
        return count

    async def similarity_search(
        self, org_id: UUID, query_embedding: List[float], limit: int = 5, repo_id: Optional[UUID] = None, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        # Enforce strict tenant isolation: filter by organization_id == org_id
        matching = [
            c for c in self.chunks
            if c.organization_id == org_id and c.embedding is not None and (repo_id is None or c.repository_id == repo_id) and (not only_current or c.is_current)
        ]

        def cosine_distance(v1: List[float], v2: List[float]) -> float:
            dot = sum(a * b for a, b in zip(v1, v2))
            norm1 = math.sqrt(sum(a * a for a in v1))
            norm2 = math.sqrt(sum(b * b for b in v2))
            if norm1 == 0 or norm2 == 0:
                return 1.0
            cos_sim = dot / (norm1 * norm2)
            return 1.0 - cos_sim

        scored = []
        for c in matching:
            dist = cosine_distance(c.embedding, query_embedding)
            scored.append({
                "id": str(c.id),
                "organization_id": str(c.organization_id),
                "repository_id": str(c.repository_id),
                "commit_sha": c.commit_sha,
                "file_path": c.file_path,
                "symbol_name": c.symbol_name,
                "symbol_type": c.symbol_type,
                "content": c.content,
                "is_current": c.is_current,
                "distance": dist,
                "similarity": 1.0 - dist,
            })

        scored.sort(key=lambda x: x["distance"])
        return scored[:limit]


class InMemoryGraphEdgeRepository(GraphEdgeRepository):
    def __init__(self):
        self.edges: List[GraphEdge] = []

    async def save_edge(self, edge: GraphEdge) -> GraphEdge:
        self.edges.append(edge)
        return edge

    async def batch_save_edges(self, edges: List[GraphEdge]) -> int:
        self.edges.extend(edges)
        return len(edges)

    async def deactivate_edges_for_symbols(self, org_id: UUID, repo_id: UUID, symbols: List[str]) -> int:
        count = 0
        sym_set = set(symbols)
        for e in self.edges:
            if e.organization_id == org_id and e.repository_id == repo_id and e.is_current and (e.source_symbol in sym_set or e.target_symbol in sym_set):
                e.is_current = False
                count += 1
        return count

    async def find_impact_tree(
        self, org_id: UUID, repository_id: UUID, target_symbol: str, max_depth: int = 3, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        # Simulates the recursive CTE with organization_id and repository_id isolation
        visited: Dict[str, Dict[str, Any]] = {}
        queue = [(target_symbol, 1)]

        while queue:
            curr_target, depth = queue.pop(0)
            if depth > max_depth:
                continue

            for edge in self.edges:
                if (
                    edge.organization_id == org_id
                    and edge.repository_id == repository_id
                    and edge.target_symbol == curr_target
                    and (not only_current or edge.is_current)
                ):
                    src = edge.source_symbol
                    if src not in visited or depth < visited[src]["depth"]:
                        visited[src] = {
                            "source_symbol": src,
                            "edge_type": edge.edge_type,
                            "depth": depth,
                        }
                        queue.append((src, depth + 1))

        return sorted(list(visited.values()), key=lambda x: x["depth"])


class InMemoryAnalysisRepository(AnalysisRepository):
    def __init__(self):
        self.analyses: List[Analysis] = []

    async def save_analysis(self, analysis: Analysis) -> Analysis:
        self.analyses.append(analysis)
        return analysis

    async def get_by_entity(
        self, org_id: UUID, entity_type: str, entity_id: str
    ) -> List[Analysis]:
        return [
            a for a in self.analyses
            if a.organization_id == org_id and a.entity_type == entity_type and a.entity_id == entity_id
        ]


class InMemoryIndexJobRepository(IndexJobRepository):
    def __init__(self):
        self.jobs: Dict[UUID, IndexJob] = {}

    async def create_job(self, job: IndexJob) -> IndexJob:
        for existing in self.jobs.values():
            if (
                existing.organization_id == job.organization_id
                and existing.repository_id == job.repository_id
                and existing.commit_sha == job.commit_sha
            ):
                return existing
        self.jobs[job.id] = job
        return job

    async def get_job_by_id(self, job_id: UUID) -> Optional[IndexJob]:
        return self.jobs.get(job_id)

    async def get_job_by_commit(
        self, organization_id: UUID, repository_id: UUID, commit_sha: str
    ) -> Optional[IndexJob]:
        for job in reversed(list(self.jobs.values())):
            if (
                job.organization_id == organization_id
                and job.repository_id == repository_id
                and job.commit_sha == commit_sha
            ):
                return job
        return None

    async def acquire_next_queued_job(self) -> Optional[IndexJob]:
        from app.domain.models import IndexJobStatus, utc_now
        for job in sorted(self.jobs.values(), key=lambda j: j.created_at):
            if job.status == IndexJobStatus.QUEUED:
                job.status = IndexJobStatus.PROCESSING
                job.attempt_count += 1
                job.started_at = utc_now()
                job.updated_at = utc_now()
                return job
        return None

    async def mark_job_status(
        self, job_id: UUID, status: str, last_error: Optional[str] = None
    ) -> None:
        from app.domain.models import IndexJobStatus, utc_now
        job = self.jobs.get(job_id)
        if not job:
            return
        if status == "COMPLETED":
            job.status = IndexJobStatus.COMPLETED
            job.completed_at = utc_now()
            job.updated_at = utc_now()
            job.last_error = None
        elif status == "FAILED":
            if job.attempt_count < job.max_attempts:
                job.status = IndexJobStatus.QUEUED
            else:
                job.status = IndexJobStatus.FAILED
                job.completed_at = utc_now()
            job.last_error = last_error
            job.updated_at = utc_now()
        else:
            job.status = IndexJobStatus(status)
            job.last_error = last_error
            job.updated_at = utc_now()

    async def recover_stale_jobs(self) -> int:
        from app.domain.models import IndexJobStatus, utc_now
        recovered = 0
        for job in self.jobs.values():
            if job.status == IndexJobStatus.PROCESSING:
                job.status = IndexJobStatus.QUEUED
                job.updated_at = utc_now()
                recovered += 1
        return recovered

