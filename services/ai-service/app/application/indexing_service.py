import hashlib
import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.models import CodeChunk, GraphEdge
from app.domain.repositories import CodeChunkRepository, GraphEdgeRepository
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
    ):
        self.code_chunk_repo = code_chunk_repo
        self.graph_edge_repo = graph_edge_repo
        self.parser = parser or TreeSitterParser()
        self.llm_client = llm_client or GeminiClient()

    async def index_file(
        self,
        organization_id: UUID,
        repository_id: UUID,
        commit_sha: str,
        file_path: str,
        content: str,
    ) -> Dict[str, Any]:
        """
        Parses source code file using tree-sitter AST, generates embeddings with hash caching,
        extracts graph edges, and persists into PostgreSQL.
        """
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
