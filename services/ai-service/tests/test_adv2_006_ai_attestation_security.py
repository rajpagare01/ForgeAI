import pytest
from uuid import uuid4
from fastapi.testclient import TestClient
from app.main import app
from app.core.config import settings


@pytest.fixture
def test_client():
    return TestClient(app)


def test_adv2_006_direct_request_without_attestation_returns_403(test_client):
    """
    ADV2-006: Direct access to protected AI endpoints (e.g. /api/v1/ai/sync/push)
    without 'X-ForgeAI-Internal: attested' MUST return HTTP 403 Forbidden.
    """
    org_id = str(uuid4())
    payload = {
        "repository_id": str(uuid4()),
        "commit_sha": "a" * 40,
        "ref": "refs/heads/main",
        "files": []
    }

    # Attempt direct connection to AI service without internal marker
    response = test_client.post(
        "/api/v1/ai/sync/push",
        json=payload,
        headers={
            "X-Org-Id": org_id,
            "X-User-Id": str(uuid4()),
        }
    )

    assert response.status_code == 403
    data = response.json()
    assert "Forbidden" in data.get("detail", "")
    assert "attested gateway context" in data.get("detail", "")


def test_adv2_006_direct_request_with_invalid_attestation_returns_403(test_client):
    """
    ADV2-006: Direct access with invalid or spoofed marker MUST return HTTP 403 Forbidden.
    """
    org_id = str(uuid4())
    payload = {
        "repository_id": str(uuid4()),
        "commit_sha": "b" * 40,
        "ref": "refs/heads/main",
        "files": []
    }

    response = test_client.post(
        "/api/v1/ai/sync/push",
        json=payload,
        headers={
            "X-Org-Id": org_id,
            "X-ForgeAI-Internal": "spoofed-bypass-attempt",
        }
    )

    assert response.status_code == 403
    assert "Forbidden" in response.json().get("detail", "")


def test_adv2_006_attested_request_passes_attestation_gate(test_client):
    """
    ADV2-006: Attested requests with 'X-ForgeAI-Internal: attested' pass through
    the internal attestation gate to normal tenant processing.
    """
    # For a route with missing tenant header, it should now reach get_tenant_org_id (400)
    # instead of getting blocked by attestation check (403).
    response = test_client.post(
        "/api/v1/ai/search",
        json={"query": "test query"},
        headers={
            "X-ForgeAI-Internal": "attested",
            # deliberately omitting X-Org-Id to verify it passed the 403 check
        }
    )

    # 400 Bad Request indicates it successfully passed the 403 attestation gate
    # and failed at tenant org header validation
    assert response.status_code == 400
    assert "tenant header" in response.json().get("detail", "")


def test_adv2_006_public_health_and_metrics_remain_accessible_without_attestation(test_client):
    """
    ADV2-006: Infrastructure endpoints (/health, /healthz, /api/v1/ai/health, /metrics)
    must remain accessible without internal attestation for container orchestrator probes.
    """
    health_resp = test_client.get("/health")
    assert health_resp.status_code == 200

    healthz_resp = test_client.get("/healthz")
    assert healthz_resp.status_code == 200

    ai_health_resp = test_client.get("/api/v1/ai/health")
    assert ai_health_resp.status_code == 200

    metrics_resp = test_client.get("/metrics")
    assert metrics_resp.status_code == 200
