import logging
from typing import Optional
from uuid import UUID, uuid4
from fastapi import Request, HTTPException, Depends
from app.core.config import settings
from app.infrastructure.resilience.redis_rate_limiter import RedisAiRateLimiter

logger = logging.getLogger(__name__)

# Global rate limiter instance
ai_rate_limiter = RedisAiRateLimiter()


def get_tenant_org_id(request: Request) -> UUID:
    """
    Extracts mandatory gateway-sanitized X-Org-Id or X-Workspace-Id header.
    Query-parameter fallback (workspaceId / organizationId) is restricted strictly
    to SSE stream requests (/events/stream) accompanied by matching attested headers
    or a Bearer / query token.
    Raises HTTP 400 Bad Request if missing or invalid UUID, 401/403 on unauthorized SSE fallback.
    """
    header_org = request.headers.get(settings.ORG_HEADER_NAME)
    header_ws = request.headers.get("X-Workspace-Id")
    header_val = header_org or header_ws

    path = request.url.path.rstrip("/")
    is_sse_stream = path.endswith("/events/stream")

    if header_val:
        if is_sse_stream:
            query_org = request.query_params.get("organizationId")
            query_ws = request.query_params.get("workspaceId")
            if query_org and header_org and query_org.strip() != header_org.strip():
                raise HTTPException(
                    status_code=403,
                    detail="Tenant query parameter organizationId does not match attested gateway header.",
                )
            if query_ws and header_ws and query_ws.strip() != header_ws.strip():
                raise HTTPException(
                    status_code=403,
                    detail="Tenant query parameter workspaceId does not match attested gateway header.",
                )
            if query_org and not header_org and query_org.strip() != header_val.strip():
                raise HTTPException(
                    status_code=403,
                    detail="Tenant query parameter organizationId does not match attested gateway header.",
                )
            if query_ws and not header_ws and query_ws.strip() != header_val.strip():
                raise HTTPException(
                    status_code=403,
                    detail="Tenant query parameter workspaceId does not match attested gateway header.",
                )
        raw_val = header_val
    elif is_sse_stream:
        query_val = request.query_params.get("workspaceId") or request.query_params.get("organizationId")
        if not query_val:
            raise HTTPException(
                status_code=400,
                detail=f"Missing mandatory tenant header '{settings.ORG_HEADER_NAME}', 'X-Workspace-Id', or SSE query parameter 'workspaceId' / 'organizationId'",
            )
        has_internal_attestation = (
            request.headers.get(settings.INTERNAL_MARKER_HEADER) == settings.INTERNAL_MARKER_EXPECTED
        )
        auth_header = request.headers.get("Authorization", "")
        has_bearer = auth_header.startswith("Bearer ") and len(auth_header[7:].strip()) > 0
        has_query_token = bool(request.query_params.get("token", "").strip())
        if not (has_internal_attestation or has_bearer or has_query_token):
            raise HTTPException(
                status_code=401,
                detail="SSE stream query tenant fallback requires attested gateway headers or an authentication token.",
            )
        raw_val = query_val
    else:
        raise HTTPException(
            status_code=400,
            detail=f"Missing mandatory tenant header '{settings.ORG_HEADER_NAME}' or 'X-Workspace-Id'",
        )

    try:
        return UUID(raw_val)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid UUID format in tenant identifier: {raw_val}",
        )


def get_tenant_user_id(request: Request) -> Optional[UUID]:
    header_val = (
        request.headers.get(settings.USER_HEADER_NAME)
        or request.query_params.get("userId")
    )
    if not header_val:
        return None
    try:
        return UUID(header_val)
    except ValueError:
        return None


def get_trace_id(request: Request) -> str:
    """
    Extracts W3C traceparent (00-<trace_id>-<span_id>-01) or X-Correlation-Id.
    Defaults to a new 32-hex trace ID.
    """
    traceparent = request.headers.get("traceparent")
    correlation_id = request.headers.get("X-Correlation-Id")

    if traceparent and traceparent.startswith("00-"):
        parts = traceparent.split("-")
        if len(parts) > 1 and len(parts[1]) == 32:
            return parts[1]
    
    if correlation_id:
        clean = correlation_id.replace("-", "")
        if len(clean) == 32:
            return clean
        return clean.ljust(32, "0")[:32]

    # Generate W3C 32-hex trace ID
    return uuid4().hex


async def check_ai_quota(org_id: UUID = Depends(get_tenant_org_id)):
    """
    FastAPI dependency enforcing daily token quota and rate limiting.
    Rejects with HTTP 429 Too Many Requests and Retry-After header if exceeded.
    """
    try:
        allowed, retry_after = await ai_rate_limiter.check_and_consume_quota(
            org_id=org_id,
            estimated_tokens=500,
            max_requests_per_minute=60,
            daily_token_limit=100_000,
        )
        if not allowed:
            raise HTTPException(
                status_code=429,
                detail="AI organizational quota or rate limit exceeded",
                headers={"Retry-After": str(retry_after or 60)},
            )
    except HTTPException:
        raise
    except Exception as e:
        logger.warning(f"Quota check encountered unexpected error: {e}; failing open for availability")


EXEMPT_INTERNAL_PATHS = {
    "/health",
    "/healthz",
    "/api/v1/ai/health",
    "/metrics",
    "/api/v1/ai/metrics",
    "/docs",
    "/openapi.json",
    "/redoc",
}


def verify_internal_attestation(request: Request) -> None:
    """
    Enforces internal service attestation from the API Gateway or trusted internal services (ADV2-006).
    If ENFORCE_INTERNAL_MARKER is enabled, requests lacking 'X-ForgeAI-Internal: attested'
    are rejected with HTTP 403 Forbidden to protect direct service port exposure.
    Health and observability endpoints are exempted.
    """
    if not settings.ENFORCE_INTERNAL_MARKER:
        return

    path = request.url.path
    if path in EXEMPT_INTERNAL_PATHS or path.endswith("/health") or path.endswith("/healthz"):
        return

    attestation = request.headers.get(settings.INTERNAL_MARKER_HEADER)
    if not attestation or attestation.strip().lower() != settings.INTERNAL_MARKER_EXPECTED.lower():
        client_ip = request.client.host if request.client else "unknown"
        logger.warning(
            f"Blocked unattested direct request to {path} from {client_ip} "
            f"(missing or invalid {settings.INTERNAL_MARKER_HEADER})"
        )
        raise HTTPException(
            status_code=403,
            detail="Forbidden: direct service invocation without attested gateway context",
        )

