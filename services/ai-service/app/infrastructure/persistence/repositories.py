import json
import logging
from typing import List, Optional, Dict, Any
from uuid import UUID
import asyncpg
from app.domain.models import CodeChunk, GraphEdge, Analysis
from app.domain.repositories import (
    CodeChunkRepository,
    GraphEdgeRepository,
    AnalysisRepository,
)

logger = logging.getLogger(__name__)


class AsyncpgCodeChunkRepository(CodeChunkRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_chunk(self, chunk: CodeChunk) -> CodeChunk:
        query = """
            INSERT INTO code_chunks (
                id, organization_id, repository_id, commit_sha, file_path,
                symbol_name, symbol_type, content, content_hash, embedding, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10::vector, $11
            ) RETURNING id;
        """
        emb_str = f"[{','.join(str(x) for x in chunk.embedding)}]" if chunk.embedding else None
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                chunk.id,
                chunk.organization_id,
                chunk.repository_id,
                chunk.commit_sha,
                chunk.file_path,
                chunk.symbol_name,
                chunk.symbol_type,
                chunk.content,
                chunk.content_hash,
                emb_str,
                chunk.created_at,
            )
        return chunk

    async def batch_save_chunks(self, chunks: List[CodeChunk]) -> int:
        if not chunks:
            return 0
        query = """
            INSERT INTO code_chunks (
                id, organization_id, repository_id, commit_sha, file_path,
                symbol_name, symbol_type, content, content_hash, embedding, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10::vector, $11
            );
        """
        args = [
            (
                c.id,
                c.organization_id,
                c.repository_id,
                c.commit_sha,
                c.file_path,
                c.symbol_name,
                c.symbol_type,
                c.content,
                c.content_hash,
                f"[{','.join(str(x) for x in c.embedding)}]" if c.embedding else None,
                c.created_at,
            )
            for c in chunks
        ]
        async with self.pool.acquire() as conn:
            await conn.executemany(query, args)
        return len(chunks)

    async def find_by_content_hash(self, org_id: UUID, content_hash: str) -> Optional[CodeChunk]:
        query = """
            SELECT id, organization_id, repository_id, commit_sha, file_path,
                   symbol_name, symbol_type, content, content_hash, created_at
            FROM code_chunks
            WHERE organization_id = $1 AND content_hash = $2
            LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, content_hash)
            if row:
                return CodeChunk(
                    id=row["id"],
                    organization_id=row["organization_id"],
                    repository_id=row["repository_id"],
                    commit_sha=row["commit_sha"],
                    file_path=row["file_path"],
                    symbol_name=row["symbol_name"],
                    symbol_type=row["symbol_type"],
                    content=row["content"],
                    content_hash=row["content_hash"],
                    created_at=row["created_at"],
                )
        return None

    async def similarity_search(
        self, org_id: UUID, query_embedding: List[float], limit: int = 5, repo_id: Optional[UUID] = None
    ) -> List[Dict[str, Any]]:
        """
        Enforces strict multi-tenancy: WHERE organization_id = $1 is mandatory.
        Uses pgvector cosine distance operator <=>
        """
        emb_str = f"[{','.join(str(x) for x in query_embedding)}]"
        if repo_id:
            query = """
                SELECT id, organization_id, repository_id, commit_sha, file_path,
                       symbol_name, symbol_type, content, content_hash,
                       (embedding <=> $2::vector) AS distance
                FROM code_chunks
                WHERE organization_id = $1 AND repository_id = $3 AND embedding IS NOT NULL
                ORDER BY embedding <=> $2::vector
                LIMIT $4;
            """
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(query, org_id, emb_str, repo_id, limit)
        else:
            query = """
                SELECT id, organization_id, repository_id, commit_sha, file_path,
                       symbol_name, symbol_type, content, content_hash,
                       (embedding <=> $2::vector) AS distance
                FROM code_chunks
                WHERE organization_id = $1 AND embedding IS NOT NULL
                ORDER BY embedding <=> $2::vector
                LIMIT $3;
            """
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(query, org_id, emb_str, limit)

        results = []
        for r in rows:
            results.append({
                "id": str(r["id"]),
                "organization_id": str(r["organization_id"]),
                "repository_id": str(r["repository_id"]),
                "commit_sha": r["commit_sha"],
                "file_path": r["file_path"],
                "symbol_name": r["symbol_name"],
                "symbol_type": r["symbol_type"],
                "content": r["content"],
                "distance": float(r["distance"]) if r["distance"] is not None else 1.0,
                "similarity": 1.0 - (float(r["distance"]) if r["distance"] is not None else 1.0),
            })
        return results


class AsyncpgGraphEdgeRepository(GraphEdgeRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_edge(self, edge: GraphEdge) -> GraphEdge:
        query = """
            INSERT INTO graph_edges (
                id, organization_id, repository_id, source_symbol,
                target_symbol, edge_type, valid_at_sha, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8);
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                edge.id,
                edge.organization_id,
                edge.repository_id,
                edge.source_symbol,
                edge.target_symbol,
                edge.edge_type,
                edge.valid_at_sha,
                edge.created_at,
            )
        return edge

    async def batch_save_edges(self, edges: List[GraphEdge]) -> int:
        if not edges:
            return 0
        query = """
            INSERT INTO graph_edges (
                id, organization_id, repository_id, source_symbol,
                target_symbol, edge_type, valid_at_sha, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8);
        """
        args = [
            (
                e.id,
                e.organization_id,
                e.repository_id,
                e.source_symbol,
                e.target_symbol,
                e.edge_type,
                e.valid_at_sha,
                e.created_at,
            )
            for e in edges
        ]
        async with self.pool.acquire() as conn:
            await conn.executemany(query, args)
        return len(edges)

    async def find_impact_tree(
        self, org_id: UUID, target_symbol: str, max_depth: int = 3
    ) -> List[Dict[str, Any]]:
        """
        Recursive CTE traversing knowledge graph up to max_depth hops.
        Enforces tenant isolation by constraining every level to organization_id = $1.
        """
        query = """
            WITH RECURSIVE impact_tree AS (
                SELECT source_symbol, target_symbol, edge_type, 1 AS depth
                FROM graph_edges
                WHERE organization_id = $1 AND target_symbol = $2
                UNION
                SELECT ge.source_symbol, ge.target_symbol, ge.edge_type, it.depth + 1
                FROM graph_edges ge
                JOIN impact_tree it ON ge.target_symbol = it.source_symbol
                WHERE ge.organization_id = $1 AND it.depth < $3
            )
            SELECT DISTINCT source_symbol, edge_type, depth
            FROM impact_tree
            ORDER BY depth ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, target_symbol, max_depth)
        return [
            {
                "source_symbol": r["source_symbol"],
                "edge_type": r["edge_type"],
                "depth": r["depth"],
            }
            for r in rows
        ]


class AsyncpgAnalysisRepository(AnalysisRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_analysis(self, analysis: Analysis) -> Analysis:
        query = """
            INSERT INTO analyses (
                id, organization_id, entity_type, entity_id, model_version,
                prompt_version, summary, risk_level, retrieved_chunks,
                execution_duration_ms, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11);
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                analysis.id,
                analysis.organization_id,
                analysis.entity_type,
                analysis.entity_id,
                analysis.model_version,
                analysis.prompt_version,
                analysis.summary,
                analysis.risk_level,
                json.dumps(analysis.retrieved_chunks) if analysis.retrieved_chunks else None,
                analysis.execution_duration_ms,
                analysis.created_at,
            )
        return analysis

    async def get_by_entity(
        self, org_id: UUID, entity_type: str, entity_id: str
    ) -> List[Analysis]:
        query = """
            SELECT id, organization_id, entity_type, entity_id, model_version,
                   prompt_version, summary, risk_level, retrieved_chunks,
                   execution_duration_ms, created_at
            FROM analyses
            WHERE organization_id = $1 AND entity_type = $2 AND entity_id = $3
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, entity_type, entity_id)

        results = []
        for r in rows:
            chunks = json.loads(r["retrieved_chunks"]) if r["retrieved_chunks"] else []
            results.append(
                Analysis(
                    id=r["id"],
                    organization_id=r["organization_id"],
                    entity_type=r["entity_type"],
                    entity_id=r["entity_id"],
                    model_version=r["model_version"],
                    prompt_version=r["prompt_version"],
                    summary=r["summary"],
                    risk_level=r["risk_level"],
                    retrieved_chunks=chunks,
                    execution_duration_ms=r["execution_duration_ms"],
                    created_at=r["created_at"],
                )
            )
        return results
