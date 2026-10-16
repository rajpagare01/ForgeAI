import logging
from typing import Dict, Any, Optional
from uuid import UUID
import httpx
from app.core.config import settings

logger = logging.getLogger(__name__)


class SourceControlClient:
    def __init__(self, base_url: Optional[str] = None):
        self.base_url = (base_url or settings.SOURCE_CONTROL_SERVICE_URL).rstrip("/")

    async def get_pull_request_changes(
        self, organization_id: UUID, repository_id: UUID, pr_number: int
    ) -> Optional[Dict[str, Any]]:
        """
        Retrieves authoritative PR metadata, file list, patches, and raw file contents
        from source-control-service scoped by organization_id and repository_id.
        """
        url = f"{self.base_url}/api/v1/pull-requests/{pr_number}/changes"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        params = {"repositoryId": str(repository_id)}

        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                resp = await client.get(url, headers=headers, params=params)
                if resp.status_code == 200:
                    return resp.json()
                elif resp.status_code == 404:
                    logger.warning(
                        "PR #%d or repository %s not found in source-control-service (status=404)",
                        pr_number,
                        repository_id,
                    )
                    return None
                else:
                    logger.error(
                        "Source control service returned status %d for PR #%d: %s",
                        resp.status_code,
                        pr_number,
                        resp.text,
                    )
                    return None
        except httpx.RequestError as e:
            logger.warning(
                "Could not connect to source-control-service at %s (%s). Will rely on request payload if present.",
                url,
                e,
            )
            return None

    async def get_repository_commits(
        self, organization_id: UUID, repository_id: UUID
    ) -> Optional[list]:
        """
        Retrieves stored commit history for the repository to compute historical change frequency.
        """
        url = f"{self.base_url}/api/v1/repositories/{repository_id}/commits"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    return resp.json()
                return []
        except httpx.RequestError as e:
            logger.warning("Could not fetch commits from source-control-service: %s", e)
            return []

    async def get_repository_sync_status(
        self, organization_id: UUID, repository_id: UUID
    ) -> Optional[Dict[str, Any]]:
        """
        Retrieves repository synchronization status (SYNCED, SYNCING, STALE, FAILED).
        """
        url = f"{self.base_url}/api/v1/repositories/{repository_id}/sync-status"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    return resp.json()
                return None
        except httpx.RequestError as e:
            logger.warning("Could not fetch sync-status from source-control-service: %s", e)
            return None

    async def get_organization_repositories(
        self, organization_id: UUID
    ) -> list:
        """
        Retrieves all repositories registered under the organization.
        Enforces tenant isolation by passing organization_id in headers.
        """
        url = f"{self.base_url}/api/v1/repositories"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "application/json",
        }
        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers)
                if resp.status_code == 200:
                    return resp.json()
                logger.warning(
                    "Source control service returned status %d for org repos: %s",
                    resp.status_code,
                    resp.text,
                )
                return []
        except httpx.RequestError as e:
            logger.warning("Could not fetch repositories from source-control-service: %s", e)
            return []

    async def get_file_content(
        self, organization_id: UUID, repository_id: UUID, path: str, ref: Optional[str] = None
    ) -> Optional[str]:
        """
        Retrieves raw content of a specific file from a repository at an optional git ref.
        """
        url = f"{self.base_url}/api/v1/repositories/{repository_id}/contents/{path.lstrip('/')}"
        headers = {
            settings.ORG_HEADER_NAME: str(organization_id),
            "Accept": "text/plain, application/json",
        }
        params = {}
        if ref:
            params["ref"] = ref

        try:
            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.get(url, headers=headers, params=params)
                if resp.status_code == 200:
                    data = resp.text
                    return data
                return None
        except httpx.RequestError as e:
            logger.warning("Could not fetch file content from source-control-service: %s", e)
            return None

