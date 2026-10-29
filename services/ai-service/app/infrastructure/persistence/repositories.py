import json
import logging
from datetime import date, datetime
from typing import List, Optional, Dict, Any, Tuple
from uuid import UUID
import asyncpg

def _json_serial(obj):
    if isinstance(obj, (datetime, date)):
        return obj.isoformat()
    if isinstance(obj, UUID):
        return str(obj)
    if hasattr(obj, "model_dump"):
        return obj.model_dump(mode="json")
    if hasattr(obj, "dict"):
        return obj.dict()
    return str(obj)

_builtin_json_dumps = json.dumps

def _safe_json_dumps(obj, *args, **kwargs):
    if "default" not in kwargs:
        kwargs["default"] = _json_serial
    return _builtin_json_dumps(obj, *args, **kwargs)

json.dumps = _safe_json_dumps

from app.domain.models import (
    CodeChunk,
    GraphEdge,
    Analysis,
    IndexJob,
    TestRecommendation,
    TestValidationFeedback,
    MergeEvaluation,
    RepositoryDependency,
    DependencyEvidence,
    SecurityFinding,
    ReliabilityFinding,
    SecurityAssessment,
    ReliabilityAssessment,
    EngineeringAction,
    RemediationStep,
    RemediationPlan,
    PatchProposal,
    RemediationEffectiveness,
    ActionEvent,
)
from app.domain.repositories import (
    CodeChunkRepository,
    GraphEdgeRepository,
    AnalysisRepository,
    IndexJobRepository,
    TestRecommendationRepository,
    TestValidationFeedbackRepository,
    MergeEvaluationRepository,
    RepositoryDependencyRepository,
    SecurityAssessmentRepository,
    ReliabilityAssessmentRepository,
    EngineeringActionRepository,
    RemediationPlanRepository,
    PatchProposalRepository,
    RemediationEffectivenessRepository,
    ActionEventRepository,
)

logger = logging.getLogger(__name__)


class AsyncpgCodeChunkRepository(CodeChunkRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_chunk(self, chunk: CodeChunk) -> CodeChunk:
        query = """
            INSERT INTO code_chunks (
                id, organization_id, repository_id, commit_sha, file_path,
                symbol_name, symbol_type, content, content_hash, embedding, is_current, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10::vector, $11, $12
            )
            ON CONFLICT (organization_id, repository_id, commit_sha, file_path, content_hash)
            DO UPDATE SET is_current = EXCLUDED.is_current
            RETURNING id;
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
                chunk.is_current,
                chunk.created_at,
            )
        return chunk

    async def batch_save_chunks(self, chunks: List[CodeChunk]) -> int:
        if not chunks:
            return 0
        query = """
            INSERT INTO code_chunks (
                id, organization_id, repository_id, commit_sha, file_path,
                symbol_name, symbol_type, content, content_hash, embedding, is_current, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10::vector, $11, $12
            )
            ON CONFLICT (organization_id, repository_id, commit_sha, file_path, content_hash)
            DO UPDATE SET is_current = EXCLUDED.is_current;
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
                c.is_current,
                c.created_at,
            )
            for c in chunks
        ]
        async with self.pool.acquire() as conn:
            await conn.executemany(query, args)
        return len(chunks)

    async def deactivate_file_chunks(self, org_id: UUID, repo_id: UUID, file_path: str) -> int:
        query = """
            UPDATE code_chunks
            SET is_current = FALSE
            WHERE organization_id = $1 AND repository_id = $2 AND file_path = $3 AND is_current = TRUE;
        """
        async with self.pool.acquire() as conn:
            res = await conn.execute(query, org_id, repo_id, file_path)
            try:
                return int(res.split()[-1])
            except Exception:
                return 0

    async def deactivate_all_repo_chunks(self, org_id: UUID, repo_id: UUID) -> int:
        query = """
            UPDATE code_chunks
            SET is_current = FALSE
            WHERE organization_id = $1 AND repository_id = $2 AND is_current = TRUE;
        """
        async with self.pool.acquire() as conn:
            res = await conn.execute(query, org_id, repo_id)
            try:
                return int(res.split()[-1])
            except Exception:
                return 0

    async def find_by_content_hash(self, org_id: UUID, content_hash: str) -> Optional[CodeChunk]:
        query = """
            SELECT id, organization_id, repository_id, commit_sha, file_path,
                   symbol_name, symbol_type, content, content_hash, is_current, created_at,
                   embedding::text AS embedding_str
            FROM code_chunks
            WHERE organization_id = $1 AND content_hash = $2
            LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, content_hash)
            if row:
                emb = None
                if row.get("embedding_str"):
                    try:
                        emb = [float(x) for x in row["embedding_str"].strip("[]").split(",") if x.strip()]
                    except Exception:
                        emb = None
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
                    embedding=emb,
                    is_current=row.get("is_current", True),
                    created_at=row["created_at"],
                )
        return None

    async def get_by_repository(self, org_id: UUID, repo_id: UUID) -> List[CodeChunk]:
        query = """
            SELECT id, organization_id, repository_id, commit_sha, file_path,
                   symbol_name, symbol_type, content, content_hash, is_current, created_at,
                   embedding::text AS embedding_str
            FROM code_chunks
            WHERE organization_id = $1 AND repository_id = $2 AND is_current = TRUE
            ORDER BY file_path ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, repo_id)
            chunks = []
            for r in rows:
                emb = None
                if r.get("embedding_str"):
                    try:
                        emb = [float(x) for x in r["embedding_str"].strip("[]").split(",") if x.strip()]
                    except Exception:
                        emb = None
                chunks.append(
                    CodeChunk(
                        id=r["id"],
                        organization_id=r["organization_id"],
                        repository_id=r["repository_id"],
                        commit_sha=r["commit_sha"],
                        file_path=r["file_path"],
                        symbol_name=r["symbol_name"],
                        symbol_type=r["symbol_type"],
                        content=r["content"],
                        content_hash=r["content_hash"],
                        embedding=emb,
                        is_current=r.get("is_current", True),
                        created_at=r["created_at"],
                    )
                )
            return chunks

    async def similarity_search(
        self, org_id: UUID, query_embedding: List[float], limit: int = 5, repo_id: Optional[UUID] = None, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        """
        Enforces strict multi-tenancy: WHERE organization_id = $1 is mandatory.
        Uses pgvector cosine distance operator <=>
        Filters by is_current = TRUE when only_current is set to True.
        """
        emb_str = f"[{','.join(str(x) for x in query_embedding)}]"
        current_clause = "AND is_current = TRUE" if only_current else ""
        if repo_id:
            query = f"""
                SELECT id, organization_id, repository_id, commit_sha, file_path,
                       symbol_name, symbol_type, content, content_hash, is_current,
                       (embedding <=> $2::vector) AS distance
                FROM code_chunks
                WHERE organization_id = $1 AND repository_id = $3 AND embedding IS NOT NULL {current_clause}
                ORDER BY embedding <=> $2::vector
                LIMIT $4;
            """
            async with self.pool.acquire() as conn:
                rows = await conn.fetch(query, org_id, emb_str, repo_id, limit)
        else:
            query = f"""
                SELECT id, organization_id, repository_id, commit_sha, file_path,
                       symbol_name, symbol_type, content, content_hash, is_current,
                       (embedding <=> $2::vector) AS distance
                FROM code_chunks
                WHERE organization_id = $1 AND embedding IS NOT NULL {current_clause}
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
                "is_current": r.get("is_current", True),
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
                target_symbol, edge_type, valid_at_sha, is_current, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            ON CONFLICT (organization_id, repository_id, valid_at_sha, source_symbol, target_symbol, edge_type)
            DO UPDATE SET is_current = EXCLUDED.is_current;
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
                edge.is_current,
                edge.created_at,
            )
        return edge

    async def batch_save_edges(self, edges: List[GraphEdge]) -> int:
        if not edges:
            return 0
        query = """
            INSERT INTO graph_edges (
                id, organization_id, repository_id, source_symbol,
                target_symbol, edge_type, valid_at_sha, is_current, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
            ON CONFLICT (organization_id, repository_id, valid_at_sha, source_symbol, target_symbol, edge_type)
            DO UPDATE SET is_current = EXCLUDED.is_current;
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
                e.is_current,
                e.created_at,
            )
            for e in edges
        ]
        async with self.pool.acquire() as conn:
            await conn.executemany(query, args)
        return len(edges)

    async def deactivate_edges_for_symbols(self, org_id: UUID, repo_id: UUID, symbols: List[str]) -> int:
        if not symbols:
            return 0
        query = """
            UPDATE graph_edges
            SET is_current = FALSE
            WHERE organization_id = $1 AND repository_id = $2 AND is_current = TRUE
              AND (source_symbol = ANY($3) OR target_symbol = ANY($3));
        """
        async with self.pool.acquire() as conn:
            res = await conn.execute(query, org_id, repo_id, symbols)
            try:
                return int(res.split()[-1])
            except Exception:
                return 0

    async def find_impact_tree(
        self, org_id: UUID, repository_id: UUID, target_symbol: str, max_depth: int = 3, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        """
        Recursive CTE traversing knowledge graph up to max_depth hops.
        Enforces tenant AND repository isolation by constraining every level to:
        organization_id = $1 AND repository_id = $2.
        Filters by is_current = TRUE when only_current is set to True.
        """
        current_base = "AND is_current = TRUE" if only_current else ""
        current_join = "AND ge.is_current = TRUE" if only_current else ""

        query = f"""
            WITH RECURSIVE impact_tree AS (
                SELECT source_symbol, target_symbol, edge_type, 1 AS depth
                FROM graph_edges
                WHERE organization_id = $1 AND repository_id = $2 AND target_symbol = $3 {current_base}
                UNION
                SELECT ge.source_symbol, ge.target_symbol, ge.edge_type, it.depth + 1
                FROM graph_edges ge
                JOIN impact_tree it ON ge.target_symbol = it.source_symbol
                WHERE ge.organization_id = $1 AND ge.repository_id = $2 {current_join} AND it.depth < $4
            )
            SELECT DISTINCT source_symbol, edge_type, depth
            FROM impact_tree
            ORDER BY depth ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, repository_id, target_symbol, max_depth)
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
                execution_duration_ms, trace_id, provider, fallback_used,
                risk_score, confidence, risk_factors, impact_metadata,
                test_strategy, test_effectiveness, merge_readiness, review_findings,
                security_assessment, reliability_assessment, unified_risk,
                engineering_actions, remediation_summary, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, $22, $23, $24, $25, $26, $27);
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
                analysis.trace_id,
                analysis.provider,
                analysis.fallback_used,
                float(analysis.risk_score),
                analysis.confidence,
                json.dumps(analysis.risk_factors) if analysis.risk_factors else "[]",
                json.dumps(analysis.impact_metadata) if analysis.impact_metadata else "{}",
                json.dumps(analysis.test_strategy) if analysis.test_strategy else "{}",
                json.dumps(analysis.test_effectiveness) if analysis.test_effectiveness else None,
                json.dumps(analysis.merge_readiness) if analysis.merge_readiness else None,
                json.dumps(analysis.review_findings) if analysis.review_findings else "[]",
                json.dumps(analysis.security_assessment) if analysis.security_assessment else None,
                json.dumps(analysis.reliability_assessment) if analysis.reliability_assessment else None,
                json.dumps(analysis.unified_risk) if analysis.unified_risk else None,
                json.dumps(analysis.engineering_actions) if analysis.engineering_actions else "[]",
                json.dumps(analysis.remediation_summary) if analysis.remediation_summary else None,
                analysis.created_at,
            )
        return analysis

    async def get_by_entity(
        self, org_id: UUID, entity_type: str, entity_id: str
    ) -> List[Analysis]:
        if entity_id == "%":
            where_clause = "WHERE organization_id = $1 AND entity_type = $2"
            params = [org_id, entity_type]
        elif entity_type == "PR" and "#" not in entity_id:
            where_clause = "WHERE organization_id = $1 AND entity_type = $2 AND (entity_id = $3 OR entity_id LIKE '%#' || $3)"
            params = [org_id, entity_type, entity_id]
        else:
            where_clause = "WHERE organization_id = $1 AND entity_type = $2 AND entity_id = $3"
            params = [org_id, entity_type, entity_id]

        query = f"""
            SELECT id, organization_id, entity_type, entity_id, model_version,
               prompt_version, summary, risk_level, retrieved_chunks,
               execution_duration_ms, trace_id,
               COALESCE(provider, 'GEMINI') as provider,
               COALESCE(fallback_used, false) as fallback_used,
               COALESCE(risk_score, 0.0) as risk_score,
               COALESCE(confidence, 'MEDIUM') as confidence,
               risk_factors, impact_metadata, test_strategy, test_effectiveness,
               merge_readiness, review_findings,
               security_assessment, reliability_assessment, unified_risk,
               engineering_actions, remediation_summary,
               created_at
            FROM analyses
            {where_clause}
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, *params)

        results = []
        for r in rows:
            chunks = json.loads(r["retrieved_chunks"]) if r["retrieved_chunks"] else []
            r_factors = json.loads(r["risk_factors"]) if r.get("risk_factors") else []
            r_impact = json.loads(r["impact_metadata"]) if r.get("impact_metadata") else {}
            r_strat = json.loads(r["test_strategy"]) if r.get("test_strategy") else {}
            r_eff = json.loads(r["test_effectiveness"]) if r.get("test_effectiveness") else None
            m_readiness = json.loads(r["merge_readiness"]) if r.get("merge_readiness") else None
            r_findings = json.loads(r["review_findings"]) if r.get("review_findings") else []
            sec_assessment = json.loads(r["security_assessment"]) if r.get("security_assessment") else None
            rel_assessment = json.loads(r["reliability_assessment"]) if r.get("reliability_assessment") else None
            u_risk = json.loads(r["unified_risk"]) if r.get("unified_risk") else None
            eng_actions = json.loads(r["engineering_actions"]) if r.get("engineering_actions") else []
            rem_summary = json.loads(r["remediation_summary"]) if r.get("remediation_summary") else None
            results.append(
                Analysis(
                    id=r["id"],
                    organization_id=r["organization_id"],
                    entity_type=r["entity_type"],
                    entity_id=r["entity_id"],
                    provider=r["provider"],
                    model_version=r["model_version"],
                    prompt_version=r["prompt_version"],
                    summary=r["summary"],
                    risk_level=r["risk_level"],
                    risk_score=float(r["risk_score"]),
                    confidence=r["confidence"],
                    risk_factors=r_factors,
                    impact_metadata=r_impact,
                    test_strategy=r_strat,
                    test_effectiveness=r_eff,
                    merge_readiness=m_readiness,
                    review_findings=r_findings,
                    security_assessment=sec_assessment,
                    reliability_assessment=rel_assessment,
                    unified_risk=u_risk,
                    engineering_actions=eng_actions,
                    remediation_summary=rem_summary,
                    retrieved_chunks=chunks,
                    execution_duration_ms=r["execution_duration_ms"],
                    trace_id=r["trace_id"],
                    fallback_used=r["fallback_used"],
                    created_at=r["created_at"],
                )
            )
        return results

    async def get_by_id(self, org_id: UUID, analysis_id: UUID) -> Optional[Analysis]:
        query = """
            SELECT id, organization_id, entity_type, entity_id, model_version,
               prompt_version, summary, risk_level, retrieved_chunks,
               execution_duration_ms, trace_id,
               COALESCE(provider, 'GEMINI') as provider,
               COALESCE(fallback_used, false) as fallback_used,
               COALESCE(risk_score, 0.0) as risk_score,
               COALESCE(confidence, 'MEDIUM') as confidence,
               risk_factors, impact_metadata, test_strategy, test_effectiveness,
               merge_readiness, review_findings,
               security_assessment, reliability_assessment, unified_risk,
               engineering_actions, remediation_summary,
               created_at
            FROM analyses
            WHERE organization_id = $1 AND id = $2;
        """
        async with self.pool.acquire() as conn:
            r = await conn.fetchrow(query, org_id, analysis_id)
        if not r:
            return None
        chunks = json.loads(r["retrieved_chunks"]) if r["retrieved_chunks"] else []
        r_factors = json.loads(r["risk_factors"]) if r.get("risk_factors") else []
        r_impact = json.loads(r["impact_metadata"]) if r.get("impact_metadata") else {}
        r_strat = json.loads(r["test_strategy"]) if r.get("test_strategy") else {}
        r_eff = json.loads(r["test_effectiveness"]) if r.get("test_effectiveness") else None
        m_readiness = json.loads(r["merge_readiness"]) if r.get("merge_readiness") else None
        r_findings = json.loads(r["review_findings"]) if r.get("review_findings") else []
        sec_assessment = json.loads(r["security_assessment"]) if r.get("security_assessment") else None
        rel_assessment = json.loads(r["reliability_assessment"]) if r.get("reliability_assessment") else None
        u_risk = json.loads(r["unified_risk"]) if r.get("unified_risk") else None
        eng_actions = json.loads(r["engineering_actions"]) if r.get("engineering_actions") else []
        rem_summary = json.loads(r["remediation_summary"]) if r.get("remediation_summary") else None
        return Analysis(
            id=r["id"],
            organization_id=r["organization_id"],
            entity_type=r["entity_type"],
            entity_id=r["entity_id"],
            provider=r["provider"],
            model_version=r["model_version"],
            prompt_version=r["prompt_version"],
            summary=r["summary"],
            risk_level=r["risk_level"],
            risk_score=float(r["risk_score"]),
            confidence=r["confidence"],
            risk_factors=r_factors,
            impact_metadata=r_impact,
            test_strategy=r_strat,
            test_effectiveness=r_eff,
            merge_readiness=m_readiness,
            review_findings=r_findings,
            security_assessment=sec_assessment,
            reliability_assessment=rel_assessment,
            unified_risk=u_risk,
            engineering_actions=eng_actions,
            remediation_summary=rem_summary,
            retrieved_chunks=chunks,
            execution_duration_ms=r["execution_duration_ms"],
            trace_id=r["trace_id"],
            fallback_used=r["fallback_used"],
            created_at=r["created_at"],
        )



class AsyncpgTestRecommendationRepository(TestRecommendationRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_recommendations(self, recommendations: List[TestRecommendation]) -> int:
        if not recommendations:
            return 0
        query = """
            INSERT INTO test_recommendations (
                id, organization_id, repository_id, analysis_id, pr_number,
                commit_sha, test_name, test_file, test_method, target_symbol,
                priority_score, priority_bucket, confidence, evidence_sources,
                evidence_types, evidence_path, reason, historical_failure_count,
                cochange_count, runtime_coverage_verified, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21);
        """
        args = [
            (
                r.id,
                r.organization_id,
                r.repository_id,
                r.analysis_id,
                getattr(r, "pr_number", 0),
                getattr(r, "commit_sha", ""),
                r.test_name,
                r.test_file,
                r.test_method,
                r.target_symbol,
                float(r.priority_score),
                r.priority_bucket,
                r.confidence,
                json.dumps(r.evidence_sources),
                json.dumps(r.evidence_types),
                json.dumps(r.evidence_path),
                r.reason,
                r.historical_failure_count,
                r.cochange_count,
                r.runtime_coverage_verified,
                r.created_at,
            )
            for r in recommendations
        ]
        async with self.pool.acquire() as conn:
            await conn.executemany(query, args)
        return len(recommendations)

    async def get_by_analysis(self, org_id: UUID, analysis_id: UUID) -> List[TestRecommendation]:
        query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, test_name, test_file, test_method, target_symbol,
                   priority_score, priority_bucket, confidence, evidence_sources,
                   evidence_types, evidence_path, reason, historical_failure_count,
                   cochange_count, runtime_coverage_verified, created_at
            FROM test_recommendations
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY priority_score DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, analysis_id)
        results = []
        for r in rows:
            results.append(
                TestRecommendation(
                    id=r["id"],
                    organization_id=r["organization_id"],
                    repository_id=r["repository_id"],
                    analysis_id=r["analysis_id"],
                    test_name=r["test_name"],
                    test_file=r["test_file"],
                    test_method=r["test_method"],
                    target_symbol=r["target_symbol"],
                    priority_score=float(r["priority_score"]),
                    priority_bucket=r["priority_bucket"],
                    confidence=r["confidence"],
                    evidence_sources=json.loads(r["evidence_sources"]) if r["evidence_sources"] else [],
                    evidence_types=json.loads(r["evidence_types"]) if r["evidence_types"] else [],
                    evidence_path=json.loads(r["evidence_path"]) if r["evidence_path"] else [],
                    reason=r["reason"],
                    historical_failure_count=r["historical_failure_count"],
                    cochange_count=r["cochange_count"],
                    runtime_coverage_verified=r["runtime_coverage_verified"],
                    created_at=r["created_at"],
                )
            )
        return results

    async def get_by_commit(self, org_id: UUID, repo_id: UUID, commit_sha: str) -> List[TestRecommendation]:
        query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, test_name, test_file, test_method, target_symbol,
                   priority_score, priority_bucket, confidence, evidence_sources,
                   evidence_types, evidence_path, reason, historical_failure_count,
                   cochange_count, runtime_coverage_verified, created_at
            FROM test_recommendations
            WHERE organization_id = $1 AND repository_id = $2 AND commit_sha = $3
            ORDER BY priority_score DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, repo_id, commit_sha)
        results = []
        for r in rows:
            results.append(
                TestRecommendation(
                    id=r["id"],
                    organization_id=r["organization_id"],
                    repository_id=r["repository_id"],
                    analysis_id=r["analysis_id"],
                    test_name=r["test_name"],
                    test_file=r["test_file"],
                    test_method=r["test_method"],
                    target_symbol=r["target_symbol"],
                    priority_score=float(r["priority_score"]),
                    priority_bucket=r["priority_bucket"],
                    confidence=r["confidence"],
                    evidence_sources=json.loads(r["evidence_sources"]) if r["evidence_sources"] else [],
                    evidence_types=json.loads(r["evidence_types"]) if r["evidence_types"] else [],
                    evidence_path=json.loads(r["evidence_path"]) if r["evidence_path"] else [],
                    reason=r["reason"],
                    historical_failure_count=r["historical_failure_count"],
                    cochange_count=r["cochange_count"],
                    runtime_coverage_verified=r["runtime_coverage_verified"],
                    created_at=r["created_at"],
                )
            )
        return results


class AsyncpgTestValidationFeedbackRepository(TestValidationFeedbackRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_feedback(self, feedback: TestValidationFeedback) -> TestValidationFeedback:
        query = """
            INSERT INTO test_validation_feedback (
                id, organization_id, repository_id, analysis_id, commit_sha,
                workflow_run_id, recommended_count, executed_count, passed_count,
                failed_count, high_priority_missed_count, prediction_to_failure_correlation,
                test_selection_efficiency, metrics, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15);
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                feedback.id,
                feedback.organization_id,
                feedback.repository_id,
                feedback.analysis_id,
                feedback.commit_sha,
                feedback.workflow_run_id,
                feedback.recommended_count,
                feedback.executed_count,
                feedback.passed_count,
                feedback.failed_count,
                feedback.high_priority_missed_count,
                float(feedback.prediction_to_failure_correlation) if feedback.prediction_to_failure_correlation is not None else 0.0,
                float(feedback.test_selection_efficiency) if feedback.test_selection_efficiency is not None else 0.0,
                json.dumps(feedback.metrics or {}),
                feedback.created_at,
            )
        return feedback

    async def get_by_commit(self, org_id: UUID, repo_id: UUID, commit_sha: str) -> Optional[TestValidationFeedback]:
        query = """
            SELECT id, organization_id, repository_id, analysis_id, commit_sha,
                   workflow_run_id, recommended_count, executed_count, passed_count,
                   failed_count, high_priority_missed_count, prediction_to_failure_correlation,
                   test_selection_efficiency, metrics, created_at
            FROM test_validation_feedback
            WHERE organization_id = $1 AND repository_id = $2 AND commit_sha = $3
            ORDER BY created_at DESC
            LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            r = await conn.fetchrow(query, org_id, repo_id, commit_sha)
        if not r:
            return None
        return TestValidationFeedback(
            id=r["id"],
            organization_id=r["organization_id"],
            repository_id=r["repository_id"],
            analysis_id=r["analysis_id"],
            commit_sha=r["commit_sha"],
            workflow_run_id=r["workflow_run_id"],
            recommended_count=r["recommended_count"],
            executed_count=r["executed_count"],
            passed_count=r["passed_count"],
            failed_count=r["failed_count"],
            high_priority_missed_count=r["high_priority_missed_count"],
            prediction_to_failure_correlation=float(r["prediction_to_failure_correlation"]) if r["prediction_to_failure_correlation"] is not None else None,
            test_selection_efficiency=float(r["test_selection_efficiency"]) if r["test_selection_efficiency"] is not None else None,
            metrics=r["metrics"] if isinstance(r["metrics"], dict) else (json.loads(r["metrics"]) if r["metrics"] else {}),
            created_at=r["created_at"],
        )



class AsyncpgIndexJobRepository(IndexJobRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def create_job(self, job: IndexJob) -> IndexJob:
        query = """
            INSERT INTO ai_indexing_jobs (
                id, organization_id, repository_id, commit_sha, status,
                attempt_count, max_attempts, last_error, files, created_at, updated_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, $10, $11)
            ON CONFLICT (organization_id, repository_id, commit_sha)
            DO UPDATE SET updated_at = NOW()
            RETURNING id, organization_id, repository_id, commit_sha, status,
                      attempt_count, max_attempts, last_error, files,
                      created_at, updated_at, started_at, completed_at;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                query,
                job.id,
                job.organization_id,
                job.repository_id,
                job.commit_sha,
                job.status.value if hasattr(job.status, "value") else str(job.status),
                job.attempt_count,
                job.max_attempts,
                job.last_error,
                json.dumps(job.files),
                job.created_at,
                job.updated_at,
            )
            if row:
                return self._row_to_job(row)
        return job

    async def get_job_by_id(self, job_id: UUID) -> Optional[IndexJob]:
        query = """
            SELECT id, organization_id, repository_id, commit_sha, status,
                   attempt_count, max_attempts, last_error, files,
                   created_at, updated_at, started_at, completed_at
            FROM ai_indexing_jobs
            WHERE id = $1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, job_id)
            if row:
                return self._row_to_job(row)
        return None

    async def get_job_by_commit(
        self, organization_id: UUID, repository_id: UUID, commit_sha: str
    ) -> Optional[IndexJob]:
        query = """
            SELECT id, organization_id, repository_id, commit_sha, status,
                   attempt_count, max_attempts, last_error, files,
                   created_at, updated_at, started_at, completed_at
            FROM ai_indexing_jobs
            WHERE organization_id = $1 AND repository_id = $2 AND commit_sha = $3
            ORDER BY created_at DESC
            LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, organization_id, repository_id, commit_sha)
            if row:
                return self._row_to_job(row)
        return None

    async def acquire_next_queued_job(self) -> Optional[IndexJob]:
        query = """
            WITH next_job AS (
                SELECT id FROM ai_indexing_jobs
                WHERE status = 'QUEUED'
                ORDER BY created_at ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            UPDATE ai_indexing_jobs j
            SET status = 'PROCESSING',
                started_at = NOW(),
                updated_at = NOW(),
                attempt_count = j.attempt_count + 1
            FROM next_job
            WHERE j.id = next_job.id
            RETURNING j.id, j.organization_id, j.repository_id, j.commit_sha, j.status,
                      j.attempt_count, j.max_attempts, j.last_error, j.files,
                      j.created_at, j.updated_at, j.started_at, j.completed_at;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query)
            if row:
                return self._row_to_job(row)
        return None

    async def mark_job_status(
        self, job_id: UUID, status: str, last_error: Optional[str] = None
    ) -> None:
        if status == "COMPLETED":
            query = """
                UPDATE ai_indexing_jobs
                SET status = 'COMPLETED',
                    completed_at = NOW(),
                    updated_at = NOW(),
                    last_error = NULL
                WHERE id = $1;
            """
            async with self.pool.acquire() as conn:
                await conn.execute(query, job_id)
        elif status == "FAILED":
            query = """
                UPDATE ai_indexing_jobs
                SET status = CASE WHEN attempt_count < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
                    last_error = $2,
                    updated_at = NOW(),
                    completed_at = CASE WHEN attempt_count >= max_attempts THEN NOW() ELSE NULL END
                WHERE id = $1;
            """
            async with self.pool.acquire() as conn:
                await conn.execute(query, job_id, last_error)
        else:
            query = """
                UPDATE ai_indexing_jobs
                SET status = $2,
                    last_error = $3,
                    updated_at = NOW()
                WHERE id = $1;
            """
            async with self.pool.acquire() as conn:
                await conn.execute(query, job_id, status, last_error)

    async def recover_stale_jobs(self) -> int:
        query = """
            UPDATE ai_indexing_jobs
            SET status = 'QUEUED', updated_at = NOW()
            WHERE status = 'PROCESSING';
        """
        async with self.pool.acquire() as conn:
            result = await conn.execute(query)
            try:
                return int(result.split()[-1])
            except Exception:
                return 0

    def _row_to_job(self, r: Any) -> IndexJob:
        files = r["files"]
        if isinstance(files, str):
            files = json.loads(files)
        from app.domain.models import IndexJobStatus
        return IndexJob(
            id=r["id"],
            organization_id=r["organization_id"],
            repository_id=r["repository_id"],
            commit_sha=r["commit_sha"],
            status=IndexJobStatus(r["status"]),
            attempt_count=r["attempt_count"],
            max_attempts=r["max_attempts"],
            last_error=r["last_error"],
            files=files,
            created_at=r["created_at"],
            updated_at=r["updated_at"],
            started_at=r["started_at"],
            completed_at=r["completed_at"],
        )


class AsyncpgMergeEvaluationRepository(MergeEvaluationRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_evaluation(self, evaluation: MergeEvaluation) -> MergeEvaluation:
        query = """
            INSERT INTO merge_evaluations (
                id, analysis_id, organization_id, repository_id, pr_number,
                commit_sha, decision, readiness_score, confidence, policy_version,
                blocking_reasons, warnings, required_actions, required_checks,
                required_tests, security_status, reliability_status, unified_risk_score,
                github_mode, decision_timestamp, created_at
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21);
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                evaluation.id,
                evaluation.analysis_id,
                evaluation.organization_id,
                evaluation.repository_id,
                evaluation.pr_number,
                evaluation.commit_sha,
                evaluation.decision,
                float(evaluation.readiness_score),
                evaluation.confidence,
                evaluation.policy_version,
                list(evaluation.blocking_reasons or []),
                list(evaluation.warnings or []),
                list(evaluation.required_actions or []),
                json.dumps(evaluation.required_checks),
                json.dumps(evaluation.required_tests),
                evaluation.security_status,
                getattr(evaluation, "reliability_status", "PASSED"),
                float(getattr(evaluation, "unified_risk_score", 0.0)),
                evaluation.github_mode,
                evaluation.decision_timestamp,
                evaluation.created_at,
            )
        return evaluation

    async def get_by_commit(
        self, org_id: UUID, repo_id: UUID, commit_sha: str
    ) -> Optional[MergeEvaluation]:
        query = """
            SELECT id, analysis_id, organization_id, repository_id, pr_number,
                   commit_sha, decision, readiness_score, confidence, policy_version,
                   blocking_reasons, warnings, required_actions, required_checks,
                   required_tests, security_status,
                   COALESCE(reliability_status, 'PASSED') AS reliability_status,
                   COALESCE(unified_risk_score, 0.0) AS unified_risk_score,
                   github_mode, decision_timestamp, created_at
            FROM merge_evaluations
            WHERE organization_id = $1 AND repository_id = $2 AND commit_sha = $3
            ORDER BY decision_timestamp DESC LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            r = await conn.fetchrow(query, org_id, repo_id, commit_sha)
        if not r:
            return None
        return self._row_to_evaluation(r)

    async def get_by_pr(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> List[MergeEvaluation]:
        query = """
            SELECT id, analysis_id, organization_id, repository_id, pr_number,
                   commit_sha, decision, readiness_score, confidence, policy_version,
                   blocking_reasons, warnings, required_actions, required_checks,
                   required_tests, security_status,
                   COALESCE(reliability_status, 'PASSED') AS reliability_status,
                   COALESCE(unified_risk_score, 0.0) AS unified_risk_score,
                   github_mode, decision_timestamp, created_at
            FROM merge_evaluations
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY decision_timestamp DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, repo_id, pr_number)
        return [self._row_to_evaluation(r) for r in rows]

    def _row_to_evaluation(self, r: Any) -> MergeEvaluation:
        def _to_list(val):
            if val is None:
                return []
            if isinstance(val, list):
                return val
            if isinstance(val, str):
                try:
                    return json.loads(val)
                except Exception:
                    return [val]
            return []

        b_reasons = _to_list(r.get("blocking_reasons"))
        warns = _to_list(r.get("warnings"))
        req_actions = _to_list(r.get("required_actions"))
        req_checks = _to_list(r.get("required_checks"))
        req_tests = _to_list(r.get("required_tests"))
        return MergeEvaluation(
            id=r["id"],
            analysis_id=r["analysis_id"],
            organization_id=r["organization_id"],
            repository_id=r["repository_id"],
            pr_number=r["pr_number"],
            commit_sha=r["commit_sha"],
            decision=r["decision"],
            readiness_score=float(r["readiness_score"]),
            confidence=r["confidence"],
            policy_version=r["policy_version"],
            blocking_reasons=b_reasons,
            warnings=warns,
            required_actions=req_actions,
            required_checks=req_checks,
            required_tests=req_tests,
            security_status=r.get("security_status", "SECURITY_EVIDENCE_UNAVAILABLE"),
            reliability_status=r.get("reliability_status", "PASSED"),
            unified_risk_score=float(r.get("unified_risk_score", 0.0)),
            github_mode=r.get("github_mode", "MODE_A_ADVISORY"),
            decision_timestamp=r["decision_timestamp"],
            created_at=r["created_at"],
        )


class AsyncpgRepositoryDependencyRepository(RepositoryDependencyRepository):
    """
    PostgreSQL persistence for cross-repository dependency topology.
    Enforces tenant isolation by scoping all queries to organization_id.
    """

    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def upsert_dependency(self, dep: RepositoryDependency) -> RepositoryDependency:
        query = """
            INSERT INTO repository_dependencies (
                id, organization_id, source_repository_id, target_repository_id,
                dependency_type, evidence_type, confidence, source_reference,
                target_reference, current_state, metadata, discovered_at,
                last_verified_at, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11::jsonb, $12, $13, $14
            )
            ON CONFLICT (organization_id, source_repository_id, target_repository_id, dependency_type, source_reference)
            DO UPDATE SET
                confidence = EXCLUDED.confidence,
                evidence_type = EXCLUDED.evidence_type,
                target_reference = EXCLUDED.target_reference,
                current_state = EXCLUDED.current_state,
                metadata = EXCLUDED.metadata,
                last_verified_at = EXCLUDED.last_verified_at
            RETURNING id;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                query,
                dep.id,
                dep.organization_id,
                dep.source_repository_id,
                dep.target_repository_id,
                dep.dependency_type,
                dep.evidence_type,
                dep.confidence,
                dep.source_reference,
                dep.target_reference,
                dep.current_state,
                json.dumps(dep.metadata),
                dep.discovered_at,
                dep.last_verified_at,
                dep.created_at,
            )
            if row:
                dep.id = row["id"]
        return dep

    async def get_dependencies_for_source(
        self, org_id: UUID, source_repo_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        query = """
            SELECT id, organization_id, source_repository_id, target_repository_id,
                   dependency_type, evidence_type, confidence, source_reference,
                   target_reference, current_state, metadata, discovered_at,
                   last_verified_at, created_at
            FROM repository_dependencies
            WHERE organization_id = $1 AND source_repository_id = $2
              AND ($3 = '' OR current_state = $3)
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, source_repo_id, state or "")
        return [self._row_to_dep(r) for r in rows]

    async def get_dependencies_for_target(
        self, org_id: UUID, target_repo_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        query = """
            SELECT id, organization_id, source_repository_id, target_repository_id,
                   dependency_type, evidence_type, confidence, source_reference,
                   target_reference, current_state, metadata, discovered_at,
                   last_verified_at, created_at
            FROM repository_dependencies
            WHERE organization_id = $1 AND target_repository_id = $2
              AND ($3 = '' OR current_state = $3)
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, target_repo_id, state or "")
        return [self._row_to_dep(r) for r in rows]

    async def get_all_org_dependencies(
        self, org_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        query = """
            SELECT id, organization_id, source_repository_id, target_repository_id,
                   dependency_type, evidence_type, confidence, source_reference,
                   target_reference, current_state, metadata, discovered_at,
                   last_verified_at, created_at
            FROM repository_dependencies
            WHERE organization_id = $1
              AND ($2 = '' OR current_state = $2)
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, state or "")
        return [self._row_to_dep(r) for r in rows]

    async def mark_stale(self, org_id: UUID, dependency_id: UUID) -> None:
        query = """
            UPDATE repository_dependencies
            SET current_state = 'STALE', last_verified_at = NOW()
            WHERE organization_id = $1 AND id = $2;
        """
        async with self.pool.acquire() as conn:
            await conn.execute(query, org_id, dependency_id)

    async def save_evidence(self, evidence: DependencyEvidence) -> DependencyEvidence:
        query = """
            INSERT INTO dependency_evidence (
                id, organization_id, dependency_id, evidence_kind, file_path,
                excerpt, commit_sha, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8
            ) RETURNING id;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(
                query,
                evidence.id,
                evidence.organization_id,
                evidence.dependency_id,
                evidence.evidence_kind,
                evidence.file_path,
                evidence.excerpt,
                evidence.commit_sha,
                evidence.created_at,
            )
            if row:
                evidence.id = row["id"]
        return evidence

    async def get_evidence_for_dependency(
        self, org_id: UUID, dependency_id: UUID
    ) -> List[DependencyEvidence]:
        query = """
            SELECT id, organization_id, dependency_id, evidence_kind, file_path,
                   excerpt, commit_sha, created_at
            FROM dependency_evidence
            WHERE organization_id = $1 AND dependency_id = $2
            ORDER BY created_at ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, dependency_id)
        return [
            DependencyEvidence(
                id=r["id"],
                organization_id=r["organization_id"],
                dependency_id=r["dependency_id"],
                evidence_kind=r["evidence_kind"],
                file_path=r["file_path"],
                excerpt=r["excerpt"],
                commit_sha=r["commit_sha"],
                created_at=r["created_at"],
            )
            for r in rows
        ]

    def _row_to_dep(self, r: Any) -> RepositoryDependency:
        meta = json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {})
        return RepositoryDependency(
            id=r["id"],
            organization_id=r["organization_id"],
            source_repository_id=r["source_repository_id"],
            target_repository_id=r["target_repository_id"],
            dependency_type=r["dependency_type"],
            evidence_type=r["evidence_type"],
            confidence=r["confidence"],
            source_reference=r["source_reference"],
            target_reference=r["target_reference"],
            current_state=r["current_state"],
            metadata=meta,
            discovered_at=r["discovered_at"],
            last_verified_at=r["last_verified_at"],
            created_at=r["created_at"],
        )


class AsyncpgSecurityAssessmentRepository(SecurityAssessmentRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_assessment(
        self,
        assessment: SecurityAssessment,
        organization_id: Optional[UUID] = None,
        repository_id: Optional[UUID] = None,
        pr_number: Optional[int] = None,
        commit_sha: Optional[str] = None,
        analysis_id: Optional[UUID] = None,
        **kwargs,
    ) -> SecurityAssessment:
        org_id = organization_id or kwargs.get("org_id")
        repo_id = repository_id or kwargs.get("repo_id")
        pr_num = pr_number if pr_number is not None else kwargs.get("pr_number")
        c_sha = commit_sha or kwargs.get("commit_sha")
        a_id = analysis_id or kwargs.get("analysis_id")

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                assessment_query = """
                    INSERT INTO security_assessments (
                        organization_id, repository_id, analysis_id, pr_number,
                        commit_sha, security_status, evidence_status, security_risk_score,
                        critical_count, high_count, medium_count, low_count, summary, metadata
                    ) VALUES (
                        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14::jsonb
                    ) RETURNING id;
                """
                await conn.fetchval(
                    assessment_query,
                    org_id,
                    repo_id,
                    a_id,
                    pr_num,
                    c_sha,
                    assessment.security_status,
                    assessment.evidence_status,
                    assessment.security_risk_score,
                    assessment.critical_count,
                    assessment.high_count,
                    assessment.medium_count,
                    assessment.low_count,
                    assessment.summary,
                    json.dumps(assessment.metadata),
                )

                finding_query = """
                    INSERT INTO security_findings (
                        id, organization_id, repository_id, analysis_id, pr_number,
                        commit_sha, category, rule_id, severity, confidence,
                        title, description, file_path, line_number, redacted_evidence,
                        remediation, is_blocker, metadata
                    ) VALUES (
                        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18::jsonb
                    );
                """
                for f in assessment.findings:
                    await conn.execute(
                        finding_query,
                        f.id,
                        org_id,
                        repo_id,
                        a_id,
                        pr_num,
                        c_sha,
                        f.category,
                        f.rule_id,
                        f.severity,
                        f.confidence,
                        f.title,
                        f.description,
                        f.file_path,
                        f.line_number,
                        f.redacted_evidence,
                        f.remediation,
                        f.is_blocker,
                        json.dumps(f.metadata),
                    )
        return assessment

    async def get_latest_assessment(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> Optional[SecurityAssessment]:
        assessment_query = """
            SELECT security_status, evidence_status, security_risk_score,
                   critical_count, high_count, medium_count, low_count, summary, metadata
            FROM security_assessments
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY created_at DESC LIMIT 1;
        """
        findings_query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, category, rule_id, severity, confidence,
                   title, description, file_path, line_number, redacted_evidence,
                   remediation, is_blocker, metadata, created_at
            FROM security_findings
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            a_row = await conn.fetchrow(assessment_query, org_id, repo_id, pr_number)
            if not a_row:
                return None
            f_rows = await conn.fetch(findings_query, org_id, repo_id, pr_number)

        findings = [
            SecurityFinding(
                id=r["id"],
                organization_id=r["organization_id"],
                repository_id=r["repository_id"],
                analysis_id=r["analysis_id"],
                pr_number=r["pr_number"],
                commit_sha=r["commit_sha"],
                category=r["category"],
                rule_id=r["rule_id"],
                severity=r["severity"],
                confidence=r["confidence"],
                title=r["title"],
                description=r["description"],
                file_path=r["file_path"],
                line_number=r["line_number"],
                redacted_evidence=r["redacted_evidence"],
                remediation=r["remediation"],
                is_blocker=r["is_blocker"],
                metadata=json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {}),
                created_at=r["created_at"],
            )
            for r in f_rows
        ]

        meta = json.loads(a_row["metadata"]) if isinstance(a_row["metadata"], str) else (a_row["metadata"] or {})
        return SecurityAssessment(
            security_status=a_row["security_status"],
            evidence_status=a_row["evidence_status"],
            security_risk_score=a_row["security_risk_score"],
            critical_count=a_row["critical_count"],
            high_count=a_row["high_count"],
            medium_count=a_row["medium_count"],
            low_count=a_row["low_count"],
            findings=findings,
            summary=a_row["summary"] or "",
            metadata=meta,
        )

    async def get_by_analysis_id(
        self, org_id: UUID, analysis_id: UUID
    ) -> Optional[Tuple[SecurityAssessment, List[SecurityFinding]]]:
        assessment_query = """
            SELECT security_status, evidence_status, security_risk_score,
                   critical_count, high_count, medium_count, low_count, summary, metadata
            FROM security_assessments
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY created_at DESC LIMIT 1;
        """
        findings_query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, category, rule_id, severity, confidence,
                   title, description, file_path, line_number, redacted_evidence,
                   remediation, is_blocker, metadata, created_at
            FROM security_findings
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            a_row = await conn.fetchrow(assessment_query, org_id, analysis_id)
            if not a_row:
                return None
            f_rows = await conn.fetch(findings_query, org_id, analysis_id)

        findings = [
            SecurityFinding(
                id=r["id"],
                organization_id=r["organization_id"],
                repository_id=r["repository_id"],
                analysis_id=r["analysis_id"],
                pr_number=r["pr_number"],
                commit_sha=r["commit_sha"],
                category=r["category"],
                rule_id=r["rule_id"],
                severity=r["severity"],
                confidence=r["confidence"],
                title=r["title"],
                description=r["description"],
                file_path=r["file_path"],
                line_number=r["line_number"],
                redacted_evidence=r["redacted_evidence"],
                remediation=r["remediation"],
                is_blocker=r["is_blocker"],
                metadata=json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {}),
                created_at=r["created_at"],
            )
            for r in f_rows
        ]

        meta = json.loads(a_row["metadata"]) if isinstance(a_row["metadata"], str) else (a_row["metadata"] or {})
        assessment = SecurityAssessment(
            security_status=a_row["security_status"],
            evidence_status=a_row["evidence_status"],
            security_risk_score=a_row["security_risk_score"],
            critical_count=a_row["critical_count"],
            high_count=a_row["high_count"],
            medium_count=a_row["medium_count"],
            low_count=a_row["low_count"],
            findings=findings,
            summary=a_row["summary"] or "",
            metadata=meta,
        )
        return assessment, findings


class AsyncpgReliabilityAssessmentRepository(ReliabilityAssessmentRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_assessment(
        self,
        assessment: ReliabilityAssessment,
        organization_id: Optional[UUID] = None,
        repository_id: Optional[UUID] = None,
        pr_number: Optional[int] = None,
        commit_sha: Optional[str] = None,
        analysis_id: Optional[UUID] = None,
        **kwargs,
    ) -> ReliabilityAssessment:
        org_id = organization_id or kwargs.get("org_id")
        repo_id = repository_id or kwargs.get("repo_id")
        pr_num = pr_number if pr_number is not None else kwargs.get("pr_number")
        c_sha = commit_sha or kwargs.get("commit_sha")
        a_id = analysis_id or kwargs.get("analysis_id")

        async with self.pool.acquire() as conn:
            async with conn.transaction():
                assessment_query = """
                    INSERT INTO reliability_assessments (
                        organization_id, repository_id, analysis_id, pr_number,
                        commit_sha, reliability_status, evidence_status, reliability_risk_score,
                        critical_count, high_count, medium_count, low_count, summary, metadata
                    ) VALUES (
                        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14::jsonb
                    ) RETURNING id;
                """
                await conn.fetchval(
                    assessment_query,
                    org_id,
                    repo_id,
                    a_id,
                    pr_num,
                    c_sha,
                    assessment.reliability_status,
                    assessment.evidence_status,
                    assessment.reliability_risk_score,
                    assessment.critical_count,
                    assessment.high_count,
                    assessment.medium_count,
                    assessment.low_count,
                    assessment.summary,
                    json.dumps(assessment.metadata),
                )

                finding_query = """
                    INSERT INTO reliability_findings (
                        id, organization_id, repository_id, analysis_id, pr_number,
                        commit_sha, category, rule_id, severity, confidence,
                        title, description, file_path, line_number, evidence,
                        remediation, is_blocker, metadata
                    ) VALUES (
                        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18::jsonb
                    );
                """
                for f in assessment.findings:
                    await conn.execute(
                        finding_query,
                        f.id,
                        org_id,
                        repo_id,
                        a_id,
                        pr_num,
                        c_sha,
                        f.category,
                        f.rule_id,
                        f.severity,
                        f.confidence,
                        f.title,
                        f.description,
                        f.file_path,
                        f.line_number,
                        f.evidence,
                        f.remediation,
                        f.is_blocker,
                        json.dumps(f.metadata),
                    )
        return assessment

    async def get_latest_assessment(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> Optional[ReliabilityAssessment]:
        assessment_query = """
            SELECT reliability_status, evidence_status, reliability_risk_score,
                   critical_count, high_count, medium_count, low_count, summary, metadata
            FROM reliability_assessments
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY created_at DESC LIMIT 1;
        """
        findings_query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, category, rule_id, severity, confidence,
                   title, description, file_path, line_number, evidence,
                   remediation, is_blocker, metadata, created_at
            FROM reliability_findings
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            a_row = await conn.fetchrow(assessment_query, org_id, repo_id, pr_number)
            if not a_row:
                return None
            f_rows = await conn.fetch(findings_query, org_id, repo_id, pr_number)

        findings = [
            ReliabilityFinding(
                id=r["id"],
                organization_id=r["organization_id"],
                repository_id=r["repository_id"],
                analysis_id=r["analysis_id"],
                pr_number=r["pr_number"],
                commit_sha=r["commit_sha"],
                category=r["category"],
                rule_id=r["rule_id"],
                severity=r["severity"],
                confidence=r["confidence"],
                title=r["title"],
                description=r["description"],
                file_path=r["file_path"],
                line_number=r["line_number"],
                evidence=r["evidence"],
                remediation=r["remediation"],
                is_blocker=r["is_blocker"],
                metadata=json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {}),
                created_at=r["created_at"],
            )
            for r in f_rows
        ]

        meta = json.loads(a_row["metadata"]) if isinstance(a_row["metadata"], str) else (a_row["metadata"] or {})
        return ReliabilityAssessment(
            reliability_status=a_row["reliability_status"],
            evidence_status=a_row["evidence_status"],
            reliability_risk_score=a_row["reliability_risk_score"],
            critical_count=a_row["critical_count"],
            high_count=a_row["high_count"],
            medium_count=a_row["medium_count"],
            low_count=a_row["low_count"],
            findings=findings,
            summary=a_row["summary"] or "",
            metadata=meta,
        )

    async def get_by_analysis_id(
        self, org_id: UUID, analysis_id: UUID
    ) -> Optional[Tuple[ReliabilityAssessment, List[ReliabilityFinding]]]:
        assessment_query = """
            SELECT reliability_status, evidence_status, reliability_risk_score,
                   critical_count, high_count, medium_count, low_count, summary, metadata
            FROM reliability_assessments
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY created_at DESC LIMIT 1;
        """
        findings_query = """
            SELECT id, organization_id, repository_id, analysis_id, pr_number,
                   commit_sha, category, rule_id, severity, confidence,
                   title, description, file_path, line_number, evidence,
                   remediation, is_blocker, metadata, created_at
            FROM reliability_findings
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            a_row = await conn.fetchrow(assessment_query, org_id, analysis_id)
            if not a_row:
                return None
            f_rows = await conn.fetch(findings_query, org_id, analysis_id)

        findings = [
            ReliabilityFinding(
                id=r["id"],
                organization_id=r["organization_id"],
                repository_id=r["repository_id"],
                analysis_id=r["analysis_id"],
                pr_number=r["pr_number"],
                commit_sha=r["commit_sha"],
                category=r["category"],
                rule_id=r["rule_id"],
                severity=r["severity"],
                confidence=r["confidence"],
                title=r["title"],
                description=r["description"],
                file_path=r["file_path"],
                line_number=r["line_number"],
                evidence=r["evidence"],
                remediation=r["remediation"],
                is_blocker=r["is_blocker"],
                metadata=json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {}),
                created_at=r["created_at"],
            )
            for r in f_rows
        ]

        meta = json.loads(a_row["metadata"]) if isinstance(a_row["metadata"], str) else (a_row["metadata"] or {})
        assessment = ReliabilityAssessment(
            reliability_status=a_row["reliability_status"],
            evidence_status=a_row["evidence_status"],
            reliability_risk_score=a_row["reliability_risk_score"],
            critical_count=a_row["critical_count"],
            high_count=a_row["high_count"],
            medium_count=a_row["medium_count"],
            low_count=a_row["low_count"],
            findings=findings,
            summary=a_row["summary"] or "",
            metadata=meta,
        )
        return assessment, findings


class AsyncpgEngineeringActionRepository(EngineeringActionRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_actions(self, actions: List[EngineeringAction]) -> int:
        if not actions:
            return 0
        query = """
            INSERT INTO engineering_actions (
                id, action_id, analysis_id, organization_id, repository_id,
                pr_number, commit_sha, action_type, title, description,
                reason, severity, confidence, evidence, affected_files,
                affected_symbols, provenance, status, source_finding_id,
                source_finding_type, metadata, created_at, updated_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
                $11, $12, $13, $14, $15::jsonb, $16::jsonb, $17::jsonb,
                $18, $19, $20, $21::jsonb, $22, $23
            )
            ON CONFLICT (action_id)
            DO UPDATE SET
                analysis_id = EXCLUDED.analysis_id,
                status = CASE
                    WHEN engineering_actions.status IN ('APPROVED', 'APPLIED') THEN engineering_actions.status
                    ELSE EXCLUDED.status
                END,
                severity = EXCLUDED.severity,
                confidence = EXCLUDED.confidence,
                evidence = EXCLUDED.evidence,
                affected_files = EXCLUDED.affected_files,
                affected_symbols = EXCLUDED.affected_symbols,
                provenance = EXCLUDED.provenance,
                metadata = EXCLUDED.metadata,
                updated_at = EXCLUDED.updated_at;
        """
        async with self.pool.acquire() as conn:
            for a in actions:
                await conn.execute(
                    query,
                    a.id,
                    a.action_id,
                    a.analysis_id,
                    a.organization_id,
                    a.repository_id,
                    a.pr_number,
                    a.commit_sha,
                    a.action_type,
                    a.title,
                    a.description,
                    a.reason,
                    a.severity,
                    a.confidence,
                    a.evidence,
                    json.dumps(a.affected_files),
                    json.dumps(a.affected_symbols),
                    json.dumps(a.provenance),
                    a.status,
                    a.source_finding_id,
                    a.source_finding_type,
                    json.dumps(a.metadata),
                    a.created_at,
                    a.updated_at,
                )
        return len(actions)

    async def get_by_id(self, org_id: UUID, action_id: str) -> Optional[EngineeringAction]:
        query = """
            SELECT id, action_id, analysis_id, organization_id, repository_id,
                   pr_number, commit_sha, action_type, title, description,
                   reason, severity, confidence, evidence, affected_files,
                   affected_symbols, provenance, status, source_finding_id,
                   source_finding_type, metadata, created_at, updated_at
            FROM engineering_actions
            WHERE organization_id = $1 AND action_id = $2;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, action_id)
            if not row:
                return None
            return self._map_row(row)

    async def get_by_analysis(self, org_id: UUID, analysis_id: UUID) -> List[EngineeringAction]:
        query = """
            SELECT id, action_id, analysis_id, organization_id, repository_id,
                   pr_number, commit_sha, action_type, title, description,
                   reason, severity, confidence, evidence, affected_files,
                   affected_symbols, provenance, status, source_finding_id,
                   source_finding_type, metadata, created_at, updated_at
            FROM engineering_actions
            WHERE organization_id = $1 AND analysis_id = $2
            ORDER BY created_at ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, analysis_id)
            return [self._map_row(r) for r in rows]

    async def get_by_pr(self, org_id: UUID, repo_id: UUID, pr_number: int) -> List[EngineeringAction]:
        query = """
            SELECT id, action_id, analysis_id, organization_id, repository_id,
                   pr_number, commit_sha, action_type, title, description,
                   reason, severity, confidence, evidence, affected_files,
                   affected_symbols, provenance, status, source_finding_id,
                   source_finding_type, metadata, created_at, updated_at
            FROM engineering_actions
            WHERE organization_id = $1 AND repository_id = $2 AND pr_number = $3
            ORDER BY created_at ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, repo_id, pr_number)
            return [self._map_row(r) for r in rows]

    async def update_status(
        self, org_id: UUID, action_id: str, status: str, actor_id: str = "system", details: Optional[Dict[str, Any]] = None
    ) -> bool:
        from app.domain.models import utc_now
        query = """
            UPDATE engineering_actions
            SET status = $3, updated_at = $4
            WHERE organization_id = $1 AND action_id = $2;
        """
        async with self.pool.acquire() as conn:
            res = await conn.execute(query, org_id, action_id, status, utc_now())
            return "UPDATE 1" in res

    def _map_row(self, r: Any) -> EngineeringAction:
        files = json.loads(r["affected_files"]) if isinstance(r["affected_files"], str) else (r["affected_files"] or [])
        symbols = json.loads(r["affected_symbols"]) if isinstance(r["affected_symbols"], str) else (r["affected_symbols"] or [])
        prov = json.loads(r["provenance"]) if isinstance(r["provenance"], str) else (r["provenance"] or {})
        meta = json.loads(r["metadata"]) if isinstance(r["metadata"], str) else (r["metadata"] or {})
        return EngineeringAction(
            id=r["id"],
            action_id=r["action_id"],
            analysis_id=r["analysis_id"],
            organization_id=r["organization_id"],
            repository_id=r["repository_id"],
            pr_number=r["pr_number"],
            commit_sha=r["commit_sha"],
            action_type=r["action_type"],
            title=r["title"],
            description=r["description"],
            reason=r["reason"],
            severity=r["severity"],
            confidence=r["confidence"],
            evidence=r["evidence"],
            affected_files=files,
            affected_symbols=symbols,
            provenance=prov,
            status=r["status"],
            source_finding_id=r["source_finding_id"],
            source_finding_type=r["source_finding_type"],
            metadata=meta,
            created_at=r["created_at"],
            updated_at=r["updated_at"],
        )


class AsyncpgRemediationPlanRepository(RemediationPlanRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_plan(self, plan: RemediationPlan) -> RemediationPlan:
        query = """
            INSERT INTO remediation_plans (
                id, action_id, organization_id, repository_id,
                title, summary, steps, is_multi_repo, coordinated_repos,
                expected_impact, provenance, created_at, updated_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9::jsonb, $10, $11::jsonb, $12, $13
            )
            ON CONFLICT (id) DO UPDATE SET
                steps = EXCLUDED.steps,
                expected_impact = EXCLUDED.expected_impact,
                updated_at = EXCLUDED.updated_at;
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                plan.id,
                plan.action_id,
                plan.organization_id,
                plan.repository_id,
                plan.title,
                plan.summary,
                json.dumps([s.model_dump() for s in plan.steps]),
                plan.is_multi_repo,
                json.dumps(plan.coordinated_repos),
                plan.expected_impact,
                json.dumps(plan.provenance),
                plan.created_at,
                plan.updated_at,
            )
        return plan

    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[RemediationPlan]:
        query = """
            SELECT id, action_id, organization_id, repository_id,
                   title, summary, steps, is_multi_repo, coordinated_repos,
                   expected_impact, provenance, created_at, updated_at
            FROM remediation_plans
            WHERE organization_id = $1 AND action_id = $2
            ORDER BY created_at DESC LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, action_id)
            if not row:
                return None
            steps_raw = json.loads(row["steps"]) if isinstance(row["steps"], str) else (row["steps"] or [])
            steps = [RemediationStep(**s) for s in steps_raw]
            coords = json.loads(row["coordinated_repos"]) if isinstance(row["coordinated_repos"], str) else (row["coordinated_repos"] or [])
            prov = json.loads(row["provenance"]) if isinstance(row["provenance"], str) else (row["provenance"] or {})
            return RemediationPlan(
                id=row["id"],
                action_id=row["action_id"],
                organization_id=row["organization_id"],
                repository_id=row["repository_id"],
                title=row["title"],
                summary=row["summary"],
                steps=steps,
                is_multi_repo=row["is_multi_repo"],
                coordinated_repos=coords,
                expected_impact=row["expected_impact"],
                provenance=prov,
                created_at=row["created_at"],
                updated_at=row["updated_at"],
            )


class AsyncpgPatchProposalRepository(PatchProposalRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_patch(self, patch: PatchProposal) -> PatchProposal:
        query = """
            INSERT INTO patch_proposals (
                id, action_id, organization_id, repository_id,
                target_file, target_symbol, original_context, proposed_replacement,
                unified_diff, rationale, source_finding, confidence, validation_plan,
                status, model, provider, fallback_used, prompt_version, trace_id,
                created_at, updated_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13::jsonb,
                $14, $15, $16, $17, $18, $19, $20, $21
            )
            ON CONFLICT (id) DO UPDATE SET
                status = EXCLUDED.status,
                unified_diff = EXCLUDED.unified_diff,
                validation_plan = EXCLUDED.validation_plan,
                updated_at = EXCLUDED.updated_at;
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                patch.id,
                patch.action_id,
                patch.organization_id,
                patch.repository_id,
                patch.target_file,
                patch.target_symbol,
                patch.original_context,
                patch.proposed_replacement,
                patch.unified_diff,
                patch.rationale,
                patch.source_finding,
                patch.confidence,
                json.dumps(patch.validation_plan),
                patch.status,
                patch.model,
                patch.provider,
                patch.fallback_used,
                patch.prompt_version,
                patch.trace_id,
                patch.created_at,
                patch.updated_at,
            )
        return patch

    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[PatchProposal]:
        query = """
            SELECT id, action_id, organization_id, repository_id,
                   target_file, target_symbol, original_context, proposed_replacement,
                   unified_diff, rationale, source_finding, confidence, validation_plan,
                   status, model, provider, fallback_used, prompt_version, trace_id,
                   created_at, updated_at
            FROM patch_proposals
            WHERE organization_id = $1 AND action_id = $2
            ORDER BY created_at DESC LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, action_id)
            if not row:
                return None
            val_plan = json.loads(row["validation_plan"]) if isinstance(row["validation_plan"], str) else (row["validation_plan"] or {})
            return PatchProposal(
                id=row["id"],
                action_id=row["action_id"],
                organization_id=row["organization_id"],
                repository_id=row["repository_id"],
                target_file=row["target_file"],
                target_symbol=row["target_symbol"],
                original_context=row["original_context"],
                proposed_replacement=row["proposed_replacement"],
                unified_diff=row["unified_diff"],
                rationale=row["rationale"],
                source_finding=row["source_finding"],
                confidence=row["confidence"],
                validation_plan=val_plan,
                status=row["status"],
                model=row["model"],
                provider=row["provider"],
                fallback_used=row["fallback_used"],
                prompt_version=row["prompt_version"],
                trace_id=row["trace_id"],
                created_at=row["created_at"],
                updated_at=row["updated_at"],
            )

    async def update_status(self, org_id: UUID, patch_id: UUID, status: str) -> bool:
        from app.domain.models import utc_now
        query = """
            UPDATE patch_proposals
            SET status = $3, updated_at = $4
            WHERE organization_id = $1 AND id = $2;
        """
        async with self.pool.acquire() as conn:
            res = await conn.execute(query, org_id, patch_id, status, utc_now())
            return "UPDATE 1" in res


class AsyncpgRemediationEffectivenessRepository(RemediationEffectivenessRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def save_effectiveness(self, record: RemediationEffectiveness) -> RemediationEffectiveness:
        query = """
            INSERT INTO remediation_effectiveness (
                id, action_id, organization_id, repository_id,
                before_risk_score, after_risk_score, risk_delta,
                before_merge_readiness, after_merge_readiness, merge_readiness_delta,
                findings_fixed, findings_persisted, new_findings_introduced,
                tests_added, tests_passed, is_effective, remediation_summary,
                metadata, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7, $8, $9, $10,
                $11::jsonb, $12::jsonb, $13::jsonb, $14, $15, $16, $17, $18::jsonb, $19
            )
            ON CONFLICT (id) DO NOTHING;
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                record.id,
                record.action_id,
                record.organization_id,
                record.repository_id,
                record.before_risk_score,
                record.after_risk_score,
                record.risk_delta,
                record.before_merge_readiness,
                record.after_merge_readiness,
                record.merge_readiness_delta,
                json.dumps(record.findings_fixed),
                json.dumps(record.findings_persisted),
                json.dumps(record.new_findings_introduced),
                record.tests_added,
                record.tests_passed,
                record.is_effective,
                record.remediation_summary,
                json.dumps(record.metadata),
                record.created_at,
            )
        return record

    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[RemediationEffectiveness]:
        query = """
            SELECT id, action_id, organization_id, repository_id,
                   before_risk_score, after_risk_score, risk_delta,
                   before_merge_readiness, after_merge_readiness, merge_readiness_delta,
                   findings_fixed, findings_persisted, new_findings_introduced,
                   tests_added, tests_passed, is_effective, remediation_summary,
                   metadata, created_at
            FROM remediation_effectiveness
            WHERE organization_id = $1 AND action_id = $2
            ORDER BY created_at DESC LIMIT 1;
        """
        async with self.pool.acquire() as conn:
            row = await conn.fetchrow(query, org_id, action_id)
            if not row:
                return None
            fixed = json.loads(row["findings_fixed"]) if isinstance(row["findings_fixed"], str) else (row["findings_fixed"] or [])
            persisted = json.loads(row["findings_persisted"]) if isinstance(row["findings_persisted"], str) else (row["findings_persisted"] or [])
            new_f = json.loads(row["new_findings_introduced"]) if isinstance(row["new_findings_introduced"], str) else (row["new_findings_introduced"] or [])
            meta = json.loads(row["metadata"]) if isinstance(row["metadata"], str) else (row["metadata"] or {})
            return RemediationEffectiveness(
                id=row["id"],
                action_id=row["action_id"],
                organization_id=row["organization_id"],
                repository_id=row["repository_id"],
                before_risk_score=row["before_risk_score"],
                after_risk_score=row["after_risk_score"],
                risk_delta=row["risk_delta"],
                before_merge_readiness=row["before_merge_readiness"],
                after_merge_readiness=row["after_merge_readiness"],
                merge_readiness_delta=row["merge_readiness_delta"],
                findings_fixed=fixed,
                findings_persisted=persisted,
                new_findings_introduced=new_f,
                tests_added=row["tests_added"],
                tests_passed=row["tests_passed"],
                is_effective=row["is_effective"],
                remediation_summary=row["remediation_summary"],
                metadata=meta,
                created_at=row["created_at"],
            )


class AsyncpgActionEventRepository(ActionEventRepository):
    def __init__(self, pool: asyncpg.Pool):
        self.pool = pool

    async def record_event(self, event: ActionEvent) -> ActionEvent:
        query = """
            INSERT INTO action_events (
                id, organization_id, repository_id, action_id,
                event_type, actor_id, details, trace_id, created_at
            ) VALUES (
                $1, $2, $3, $4, $5, $6, $7::jsonb, $8, $9
            );
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                query,
                event.id,
                event.organization_id,
                event.repository_id,
                event.action_id,
                event.event_type,
                event.actor_id,
                json.dumps(event.details),
                event.trace_id,
                event.created_at,
            )
        return event

    async def get_events_for_action(self, org_id: UUID, action_id: str) -> List[ActionEvent]:
        query = """
            SELECT id, organization_id, repository_id, action_id,
                   event_type, actor_id, details, trace_id, created_at
            FROM action_events
            WHERE organization_id = $1 AND action_id = $2
            ORDER BY created_at ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(query, org_id, action_id)
            return [
                ActionEvent(
                    id=r["id"],
                    organization_id=r["organization_id"],
                    repository_id=r["repository_id"],
                    action_id=r["action_id"],
                    event_type=r["event_type"],
                    actor_id=r["actor_id"],
                    details=json.loads(r["details"]) if isinstance(r["details"], str) else (r["details"] or {}),
                    trace_id=r["trace_id"],
                    created_at=r["created_at"],
                )
                for r in rows
            ]





