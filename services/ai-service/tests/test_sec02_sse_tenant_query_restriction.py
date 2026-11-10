import pytest
from uuid import UUID, uuid4
from fastapi import HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request
from app.main import app
from app.presentation.dependencies import get_tenant_org_id


def _make_request(path: str, headers: dict[str, str] | None = None, query: str = "") -> Request:
    raw_headers = [
        (k.lower().encode("latin-1"), v.encode("latin-1"))
        for k, v in (headers or {}).items()
    ]
    scope = {
        "type": "http",
        "method": "GET",
        "path": path,
        "query_string": query.encode("latin-1"),
        "headers": raw_headers,
    }
    return Request(scope)


@pytest.fixture
def test_client():
    return TestClient(app)


def test_sec02_rest_endpoint_rejects_query_param_tenant_bypass(test_client):
    """SEC-02: Non-SSE REST endpoints must reject workspaceId/organizationId query fallback with 400."""
    ws_id = str(uuid4())
    org_id = str(uuid4())

    resp_ws = test_client.post(
        f"/api/v1/ai/search?workspaceId={ws_id}",
        json={"query": "auth"},
        headers={"X-ForgeAI-Internal": "attested"},
    )
    assert resp_ws.status_code == 400
    assert "tenant header" in resp_ws.json().get("detail", "")

    resp_org = test_client.post(
        f"/api/v1/ai/search?organizationId={org_id}",
        json={"query": "auth"},
        headers={"X-ForgeAI-Internal": "attested"},
    )
    assert resp_org.status_code == 400
    assert "tenant header" in resp_org.json().get("detail", "")


def test_sec02_sse_endpoint_without_attestation_returns_403(test_client):
    """SEC-02: SSE stream without internal attestation marker is blocked with 403."""
    ws_id = str(uuid4())
    resp = test_client.get(f"/api/v1/ai/events/stream?workspaceId={ws_id}")
    assert resp.status_code == 403


def test_sec02_sse_query_fallback_requires_attestation_or_token():
    """SEC-02: Direct query-param fallback on /events/stream rejects unattested/tokenless requests."""
    ws_id = str(uuid4())
    req_unauth = _make_request("/api/v1/ai/events/stream", query=f"workspaceId={ws_id}")
    with pytest.raises(HTTPException) as exc:
        get_tenant_org_id(req_unauth)
    assert exc.value.status_code == 401

    req_attested = _make_request(
        "/api/v1/ai/events/stream",
        headers={"X-ForgeAI-Internal": "attested"},
        query=f"workspaceId={ws_id}",
    )
    assert get_tenant_org_id(req_attested) == UUID(ws_id)

    req_bearer = _make_request(
        "/api/v1/ai/events/stream",
        headers={"Authorization": "Bearer valid.jwt.token"},
        query=f"workspaceId={ws_id}",
    )
    assert get_tenant_org_id(req_bearer) == UUID(ws_id)

    req_query_token = _make_request(
        "/api/v1/ai/events/stream",
        query=f"workspaceId={ws_id}&token=valid.jwt.token",
    )
    assert get_tenant_org_id(req_query_token) == UUID(ws_id)


def test_sec02_sse_rejects_header_and_query_tenant_mismatch(test_client):
    """SEC-02: SSE stream rejects mismatched attested header vs query parameter with 403."""
    attested_ws = str(uuid4())
    victim_ws = str(uuid4())

    resp = test_client.get(
        f"/api/v1/ai/events/stream?workspaceId={victim_ws}",
        headers={
            "X-ForgeAI-Internal": "attested",
            "X-Workspace-Id": attested_ws,
        },
    )
    assert resp.status_code == 403


def test_sec02_sse_accepts_matching_org_header_and_workspace_query():
    """SEC-02: SSE org context with matching X-Workspace-Id + workspaceId returns attested X-Org-Id."""
    org_id = str(uuid4())
    ws_id = str(uuid4())
    req = _make_request(
        "/api/v1/ai/events/stream",
        headers={
            "X-ForgeAI-Internal": "attested",
            "X-Org-Id": org_id,
            "X-Workspace-Id": ws_id,
        },
        query=f"workspaceId={ws_id}",
    )
    assert get_tenant_org_id(req) == UUID(org_id)

