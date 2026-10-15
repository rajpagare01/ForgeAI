import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.repositories import CodeChunkRepository, GraphEdgeRepository
from app.infrastructure.llm.gemini_client import GeminiClient

logger = logging.getLogger(__name__)


class RetrievalService:
    def __init__(
        self,
        code_chunk_repo: CodeChunkRepository,
        graph_edge_repo: GraphEdgeRepository,
        llm_client: Optional[GeminiClient] = None,
    ):
        self.code_chunk_repo = code_chunk_repo
        self.graph_edge_repo = graph_edge_repo
        self.llm_client = llm_client or GeminiClient()

    async def semantic_search(
        self,
        organization_id: UUID,
        query: str,
        limit: int = 5,
        repository_id: Optional[UUID] = None,
    ) -> List[Dict[str, Any]]:
        """
        Retrieves top relevant code chunks via pgvector cosine distance.
        Guarantees strict tenant isolation by filtering organization_id.
        """
        query_embedding = await self.llm_client.generate_embedding(query)
        return await self.code_chunk_repo.similarity_search(
            org_id=organization_id,
            query_embedding=query_embedding,
            limit=limit,
            repo_id=repository_id,
        )

    async def get_impacted_symbols(
        self,
        organization_id: UUID,
        repository_id: UUID,
        changed_symbols: List[str],
        max_depth: int = 3,
    ) -> List[Dict[str, Any]]:
        """
        Runs recursive CTE on graph_edges for all changed symbols to find impacted upstream callers/users.
        Enforces both organization_id and repository_id isolation.
        """
        all_impacted: Dict[str, Dict[str, Any]] = {}
        for sym in changed_symbols:
            tree = await self.graph_edge_repo.find_impact_tree(
                org_id=organization_id,
                repository_id=repository_id,
                target_symbol=sym,
                max_depth=max_depth,
            )
            for item in tree:
                src = item["source_symbol"]
                if src not in all_impacted or item["depth"] < all_impacted[src]["depth"]:
                    all_impacted[src] = item

        return list(all_impacted.values())
