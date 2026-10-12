import math
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.models import CodeChunk, GraphEdge, Analysis
from app.domain.repositories import (
    CodeChunkRepository,
    GraphEdgeRepository,
    AnalysisRepository,
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

    async def similarity_search(
        self, org_id: UUID, query_embedding: List[float], limit: int = 5, repo_id: Optional[UUID] = None
    ) -> List[Dict[str, Any]]:
        # Enforce strict tenant isolation: filter by organization_id == org_id
        matching = [
            c for c in self.chunks
            if c.organization_id == org_id and c.embedding is not None and (repo_id is None or c.repository_id == repo_id)
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

    async def find_impact_tree(
        self, org_id: UUID, target_symbol: str, max_depth: int = 3
    ) -> List[Dict[str, Any]]:
        # Simulates the recursive CTE with organization_id = org_id isolation
        visited: Dict[str, Dict[str, Any]] = {}
        queue = [(target_symbol, 1)]

        while queue:
            curr_target, depth = queue.pop(0)
            if depth > max_depth:
                continue

            for edge in self.edges:
                if edge.organization_id == org_id and edge.target_symbol == curr_target:
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
