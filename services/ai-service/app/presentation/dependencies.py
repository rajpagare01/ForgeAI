import logging
from typing import Optional
from uuid import UUID, uuid4
from fastapi import Request, HTTPException
from app.core.config import settings

logger = logging.getLogger(__name__)


def get_tenant_org_id(request: Request) -> UUID:
    """
    Extracts mandatory X-Org-Id header from request.
    Raises HTTP 400 Bad Request if missing or invalid UUID.
    """
    header_val = request.headers.get(settings.ORG_HEADER_NAME)
    if not header_val:
        raise HTTPException(
            status_code=400,
            detail=f"Missing mandatory tenant header '{settings.ORG_HEADER_NAME}'",
        )
    try:
        return UUID(header_val)
    except ValueError:
        raise HTTPException(
            status_code=400,
            detail=f"Invalid UUID format in tenant header '{settings.ORG_HEADER_NAME}': {header_val}",
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
