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
    Extracts mandatory X-Org-Id or X-Workspace-Id header from request.
    Raises HTTP 400 Bad Request if missing or invalid UUID.
    """
    header_val = request.headers.get(settings.ORG_HEADER_NAME) or request.headers.get("X-Workspace-Id")
    if not header_val:
        raise HTTPException(
            status_code=400,
            detail=f"Missing mandatory tenant header '{settings.ORG_HEADER_NAME}' or 'X-Workspace-Id'",
        )
    try:
        return UUID(header_val)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid UUID format in tenant header: {header_val}",
        )


def get_tenant_user_id(request: Request) -> Optional[UUID]:
    header_val = request.headers.get(settings.USER_HEADER_NAME)
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

