from abc import ABC, abstractmethod
from typing import List, Optional, Dict, Any
from uuid import UUID
from app.domain.models import CodeChunk, GraphEdge, Analysis, IndexJob, RepositoryDependency, DependencyEvidence


class CodeChunkRepository(ABC):
    @abstractmethod
    async def save_chunk(self, chunk: CodeChunk) -> CodeChunk:
        pass

    @abstractmethod
    async def batch_save_chunks(self, chunks: List[CodeChunk]) -> int:
        pass

    @abstractmethod
    async def find_by_content_hash(self, org_id: UUID, content_hash: str) -> Optional[CodeChunk]:
        pass

    @abstractmethod
    async def similarity_search(
        self, org_id: UUID, query_embedding: List[float], limit: int = 5, repo_id: Optional[UUID] = None, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        pass

    @abstractmethod
    async def deactivate_file_chunks(self, org_id: UUID, repo_id: UUID, file_path: str) -> int:
        pass

    @abstractmethod
    async def deactivate_all_repo_chunks(self, org_id: UUID, repo_id: UUID) -> int:
        pass


class GraphEdgeRepository(ABC):
    @abstractmethod
    async def save_edge(self, edge: GraphEdge) -> GraphEdge:
        pass

    @abstractmethod
    async def batch_save_edges(self, edges: List[GraphEdge]) -> int:
        pass

    @abstractmethod
    async def find_impact_tree(
        self, org_id: UUID, repository_id: UUID, target_symbol: str, max_depth: int = 3, only_current: bool = True
    ) -> List[Dict[str, Any]]:
        pass

    @abstractmethod
    async def deactivate_edges_for_symbols(self, org_id: UUID, repo_id: UUID, symbols: List[str]) -> int:
        pass


class AnalysisRepository(ABC):
    @abstractmethod
    async def save_analysis(self, analysis: Analysis) -> Analysis:
        pass

    @abstractmethod
    async def get_by_entity(
        self, org_id: UUID, entity_type: str, entity_id: str
    ) -> List[Analysis]:
        pass

    @abstractmethod
    async def get_by_id(self, org_id: UUID, analysis_id: UUID) -> Optional[Analysis]:
        pass


class TestRecommendationRepository(ABC):
    @abstractmethod
    async def save_recommendations(
        self, recommendations: List[Any]
    ) -> int:
        pass

    @abstractmethod
    async def get_by_analysis(
        self, org_id: UUID, analysis_id: UUID
    ) -> List[Any]:
        pass

    @abstractmethod
    async def get_by_commit(
        self, org_id: UUID, repo_id: UUID, commit_sha: str
    ) -> List[Any]:
        pass


class TestValidationFeedbackRepository(ABC):
    @abstractmethod
    async def save_feedback(self, feedback: Any) -> Any:
        pass

    @abstractmethod
    async def get_by_commit(
        self, org_id: UUID, repo_id: UUID, commit_sha: str
    ) -> Optional[Any]:
        pass


class IndexJobRepository(ABC):
    @abstractmethod
    async def create_job(self, job: IndexJob) -> IndexJob:
        pass

    @abstractmethod
    async def get_job_by_id(self, job_id: UUID) -> Optional[IndexJob]:
        pass

    @abstractmethod
    async def get_job_by_commit(
        self, organization_id: UUID, repository_id: UUID, commit_sha: str
    ) -> Optional[IndexJob]:
        pass

    @abstractmethod
    async def acquire_next_queued_job() -> Optional[IndexJob]:
        pass

    @abstractmethod
    async def mark_job_status(
        self, job_id: UUID, status: str, last_error: Optional[str] = None
    ) -> None:
        pass

    @abstractmethod
    async def recover_stale_jobs(self) -> int:
        pass


class MergeEvaluationRepository(ABC):
    @abstractmethod
    async def save_evaluation(self, evaluation: Any) -> Any:
        pass

    @abstractmethod
    async def get_by_commit(
        self, org_id: UUID, repo_id: UUID, commit_sha: str
    ) -> Optional[Any]:
        pass

    @abstractmethod
    async def get_by_pr(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> List[Any]:
        pass


class RepositoryDependencyRepository(ABC):
    """
    Repository interface for cross-repository dependency persistence.
    Every method enforces tenant isolation via organization_id.
    """

    @abstractmethod
    async def upsert_dependency(self, dep: RepositoryDependency) -> RepositoryDependency:
        """Insert or update a dependency relationship (upsert by unique key)."""
        pass

    @abstractmethod
    async def get_dependencies_for_source(
        self, org_id: UUID, source_repo_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        """Returns all dependencies where source_repository_id = source_repo_id."""
        pass

    @abstractmethod
    async def get_dependencies_for_target(
        self, org_id: UUID, target_repo_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        """Returns all repos that depend on target_repo_id (reverse lookup)."""
        pass

    @abstractmethod
    async def get_all_org_dependencies(
        self, org_id: UUID, state: str = "ACTIVE"
    ) -> List[RepositoryDependency]:
        """Returns all dependencies for an organization (for architecture view)."""
        pass

    @abstractmethod
    async def mark_stale(self, org_id: UUID, dependency_id: UUID) -> None:
        """Marks a dependency as STALE without destroying the historical record."""
        pass

    @abstractmethod
    async def save_evidence(self, evidence: DependencyEvidence) -> DependencyEvidence:
        """Saves a dependency evidence record."""
        pass

    @abstractmethod
    async def get_evidence_for_dependency(
        self, org_id: UUID, dependency_id: UUID
    ) -> List[DependencyEvidence]:
        pass


class SecurityAssessmentRepository(ABC):
    @abstractmethod
    async def save_assessment(
        self,
        assessment: Any,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        analysis_id: Optional[UUID] = None,
    ) -> Any:
        pass

    @abstractmethod
    async def get_latest_assessment(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> Optional[Any]:
        pass


class ReliabilityAssessmentRepository(ABC):
    @abstractmethod
    async def save_assessment(
        self,
        assessment: Any,
        organization_id: UUID,
        repository_id: UUID,
        pr_number: int,
        commit_sha: str,
        analysis_id: Optional[UUID] = None,
    ) -> Any:
        pass

    @abstractmethod
    async def get_latest_assessment(
        self, org_id: UUID, repo_id: UUID, pr_number: int
    ) -> Optional[Any]:
        pass


class EngineeringActionRepository(ABC):
    @abstractmethod
    async def save_actions(self, actions: List[Any]) -> int:
        pass

    @abstractmethod
    async def get_by_id(self, org_id: UUID, action_id: str) -> Optional[Any]:
        pass

    @abstractmethod
    async def get_by_analysis(self, org_id: UUID, analysis_id: UUID) -> List[Any]:
        pass

    @abstractmethod
    async def get_by_pr(self, org_id: UUID, repo_id: UUID, pr_number: int) -> List[Any]:
        pass

    @abstractmethod
    async def update_status(
        self, org_id: UUID, action_id: str, status: str, actor_id: str = "system", details: Optional[Dict[str, Any]] = None
    ) -> bool:
        pass


class RemediationPlanRepository(ABC):
    @abstractmethod
    async def save_plan(self, plan: Any) -> Any:
        pass

    @abstractmethod
    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[Any]:
        pass


class PatchProposalRepository(ABC):
    @abstractmethod
    async def save_patch(self, patch: Any) -> Any:
        pass

    @abstractmethod
    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[Any]:
        pass

    @abstractmethod
    async def update_status(self, org_id: UUID, patch_id: UUID, status: str) -> bool:
        pass


class RemediationEffectivenessRepository(ABC):
    @abstractmethod
    async def save_effectiveness(self, record: Any) -> Any:
        pass

    @abstractmethod
    async def get_by_action_id(self, org_id: UUID, action_id: str) -> Optional[Any]:
        pass


class ActionEventRepository(ABC):
    @abstractmethod
    async def record_event(self, event: Any) -> Any:
        pass

    @abstractmethod
    async def get_events_for_action(self, org_id: UUID, action_id: str) -> List[Any]:
        pass





