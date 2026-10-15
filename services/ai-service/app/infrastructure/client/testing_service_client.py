import logging
from typing import List, Dict, Any, Optional
from uuid import UUID
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)


class TestingServiceClient:
    def __init__(self, base_url: Optional[str] = None):
        self.base_url = (base_url or settings.TESTING_SERVICE_URL).rstrip("/")

    async def list_test_runs(
        self, organization_id: UUID, repository_id: Optional[UUID] = None, commit_sha: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Retrieves recorded test runs from testing-service scoped by organization_id.
        """
        url = f"{self.base_url}/api/v1/test-runs"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        params: Dict[str, str] = {}
        if repository_id:
            params["repositoryId"] = str(repository_id)
        if commit_sha:
            params["commitSha"] = commit_sha

        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers, params=params)
                if resp.status_code == 200:
                    data = resp.json()
                    return data if isinstance(data, list) else []
                return []
        except httpx.RequestError as e:
            logger.debug("Testing service unavailable at %s: %s", url, e)
            return []

    async def get_test_results(
        self, organization_id: UUID, run_id: UUID
    ) -> List[Dict[str, Any]]:
        """
        Retrieves detailed test results for a specific test run.
        """
        url = f"{self.base_url}/api/v1/test-runs/{run_id}/results"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    data = resp.json()
                    return data if isinstance(data, list) else []
                return []
        except httpx.RequestError as e:
            logger.debug("Testing service results query failed: %s", e)
            return []

    async def get_recent_workflow_runs(
        self, organization_id: UUID, repository_id: UUID, limit: int = 10
    ) -> List[Dict[str, Any]]:
        """
        Retrieves recent test runs and GitHub workflow runs for the repository.
        """
        runs = await self.list_test_runs(organization_id=organization_id, repository_id=repository_id)
        return runs[:limit] if runs else []

    async def get_historical_failures(
        self, organization_id: UUID, repository_id: UUID, limit: int = 20
    ) -> List[Dict[str, Any]]:
        """
        Retrieves historical test failure records from recent test runs to detect recurring instability.
        """
        runs = await self.list_test_runs(organization_id=organization_id, repository_id=repository_id)
        failed_runs = [r for r in runs if (r.get("failedTests") or r.get("failed_tests") or 0) > 0 or r.get("status") == "FAILED" or r.get("conclusion") == "failure"]

        historical_failures: List[Dict[str, Any]] = []
        seen = set()

        for run in failed_runs[:5]:
            run_id = run.get("id")
            if not run_id:
                continue
            try:
                run_uuid = UUID(str(run_id))
                results = await self.get_test_results(organization_id, run_uuid)
                for res in results:
                    if res.get("status") == "FAILED":
                        t_name = res.get("name") or res.get("testName") or "UnknownTest"
                        cls_name = res.get("classname") or ""
                        key = f"{t_name}::{cls_name}"
                        if key not in seen:
                            seen.add(key)
                            historical_failures.append({
                                "test_name": t_name,
                                "classname": cls_name,
                                "failure_count": 1,
                                "error_message": res.get("errorMessage") or res.get("error_message"),
                                "commit_sha": run.get("commitSha") or run.get("commit_sha"),
                            })
                            if len(historical_failures) >= limit:
                                break
            except Exception as e:
                logger.debug("Could not inspect run results for historical failures: %s", e)

        return historical_failures

