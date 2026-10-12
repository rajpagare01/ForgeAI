import time
import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
from app.domain.models import Analysis, RiskLevel
from app.domain.repositories import AnalysisRepository
from app.application.retrieval_service import RetrievalService
from app.infrastructure.llm.gemini_client import GeminiClient

logger = logging.getLogger(__name__)


class GeminiOrchestrationService:
    def __init__(
        self,
        retrieval_service: RetrievalService,
        analysis_repo: AnalysisRepository,
        llm_client: Optional[GeminiClient] = None,
    ):
        self.retrieval_service = retrieval_service
        self.analysis_repo = analysis_repo
        self.llm_client = llm_client or GeminiClient()
        self.prompt_version = "v1.0.0"

    async def analyze_pull_request(
        self,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        title: str,
        description: str,
        changed_symbols: List[str],
        trace_id: Optional[str] = None,
    ) -> Analysis:
        """
        Orchestrates hybrid RAG:
        1. Multi-hop recursive graph traversal for changed symbols
        2. Vector semantic search for PR context
        3. Gemini reasoning with structured prompt
        4. Audit record persistence in analyses table with correlated trace_id
        """
        start_time = time.time()

        # 1. Graph impact
        impacted_symbols = await self.retrieval_service.get_impacted_symbols(
            organization_id=organization_id,
            changed_symbols=changed_symbols,
            max_depth=3,
        )

        # 2. Semantic retrieval for PR description context
        retrieved_chunks = await self.retrieval_service.semantic_search(
            organization_id=organization_id,
            query=f"{title}\n{description}",
            limit=4,
            repository_id=repository_id,
        )

        # 3. Construct prompt
        impact_summary = "\n".join(
            [f"- {node['source_symbol']} (depth {node['depth']}, via {node['edge_type']})" for node in impacted_symbols]
        ) or "None detected"

        code_context = "\n\n".join(
            [f"// File: {c['file_path']} ({c.get('symbol_name')})\n{c['content']}" for c in retrieved_chunks]
        ) or "No code context retrieved."

        prompt = f"""
You are an expert Principal Systems Engineer auditing a Pull Request.

PR Title: {title}
PR Description: {description}

Directly Changed Symbols:
{', '.join(changed_symbols) if changed_symbols else 'None provided'}

Graph Impact Analysis (Upstream dependencies affected):
{impact_summary}

Relevant Code Context:
{code_context}

Please provide:
1. Executive architectural summary of this PR
2. Blast radius and risk evaluation (LOW, MEDIUM, HIGH, CRITICAL)
3. Specific test coverage recommendations based on the affected symbols
"""

        system_instruction = (
            "You are ForgeAI's Chief Architect. Provide concise, rigorous engineering reviews."
        )

        summary_text = await self.llm_client.generate_text(prompt, system_instruction)

        # Assess risk level based on graph impact depth & node count
        if len(impacted_symbols) > 10:
            risk = RiskLevel.HIGH.value
        elif len(impacted_symbols) > 3:
            risk = RiskLevel.MEDIUM.value
        else:
            risk = RiskLevel.LOW.value

        duration_ms = int((time.time() - start_time) * 1000)

        # 4. Save audit record in analyses table with trace_id
        analysis = Analysis(
            organization_id=organization_id,
            entity_type="PR",
            entity_id=str(pr_number),
            model_version=self.llm_client.model_name,
            prompt_version=self.prompt_version,
            summary=summary_text,
            risk_level=risk,
            retrieved_chunks=retrieved_chunks,
            execution_duration_ms=duration_ms,
            trace_id=trace_id,
        )

        saved = await self.analysis_repo.save_analysis(analysis)
        logger.info(
            f"Saved PR analysis for PR #{pr_number} [traceId={trace_id}] (org={organization_id}, duration={duration_ms}ms, risk={risk})"
        )
        return saved

