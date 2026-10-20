import logging
import re
from typing import Callable
from uuid import uuid4
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from app.core.logging import (
    trace_id_cv,
    span_id_cv,
    correlation_id_cv,
    org_id_cv,
)

logger = logging.getLogger(__name__)

W3C_TRACEPARENT_REGEX = re.compile(r"^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$")
ALL_ZEROS_TRACE = "00000000000000000000000000000000"


class TraceContextMiddleware(BaseHTTPMiddleware):
    """
    W3C TraceContext middleware for FastAPI.
    Extracts incoming 'traceparent' header (00-<trace_id>-<span_id>-<flags>)
    or 'X-Correlation-Id' and binds trace context to request state, contextvars,
    and outbound response headers.
    """
    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        traceparent = request.headers.get("traceparent")
        incoming_correlation_id = request.headers.get("x-correlation-id")
        org_id = request.headers.get("x-org-id") or request.headers.get("x-organization-id")
        span_id = uuid4().hex[:16]

        is_valid_w3c = (
            traceparent is not None
            and bool(W3C_TRACEPARENT_REGEX.match(traceparent))
            and ALL_ZEROS_TRACE not in traceparent
        )

        if is_valid_w3c:
            parts = traceparent.split("-")
            trace_id = parts[1].lower()
            out_traceparent = f"00-{trace_id}-{span_id}-01"
            correlation_id = (
                incoming_correlation_id.strip()
                if incoming_correlation_id and incoming_correlation_id.strip()
                else trace_id
            )
        else:
            correlation_id = (
                incoming_correlation_id.strip()
                if incoming_correlation_id and incoming_correlation_id.strip()
                else str(uuid4())
            )
            clean = correlation_id.replace("-", "").lower()
            trace_id = clean.ljust(32, "0")[:32] if len(clean) >= 32 else uuid4().hex
            out_traceparent = f"00-{trace_id}-{span_id}-01"

        # Bind to request state
        request.state.trace_id = trace_id
        request.state.span_id = span_id
        request.state.correlation_id = correlation_id
        if org_id:
            request.state.organization_id = org_id.strip()

        # Bind to contextvars for structured JSON logging
        t_token = trace_id_cv.set(trace_id)
        s_token = span_id_cv.set(span_id)
        c_token = correlation_id_cv.set(correlation_id)
        o_token = org_id_cv.set(org_id.strip() if org_id else "")

        try:
            response = await call_next(request)
            response.headers["x-correlation-id"] = correlation_id
            response.headers["traceparent"] = out_traceparent
            return response
        finally:
            trace_id_cv.reset(t_token)
            span_id_cv.reset(s_token)
            correlation_id_cv.reset(c_token)
            org_id_cv.reset(o_token)
