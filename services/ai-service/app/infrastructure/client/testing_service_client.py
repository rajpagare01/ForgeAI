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
