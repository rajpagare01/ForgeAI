import logging
from typing import Callable
from uuid import uuid4
from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware
from app.core.logging import setup_logging

logger = logging.getLogger(__name__)


class TraceContextMiddleware(BaseHTTPMiddleware):
    """
    W3C TraceContext middleware for FastAPI.
    Extracts incoming 'traceparent' header (00-<trace_id>-<span_id>-<flags>)
    or 'X-Correlation-Id' and binds trace_id to request state and response headers.
    """
    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        traceparent = request.headers.get("traceparent")
        correlation_id = request.headers.get("x-correlation-id")

        if traceparent and traceparent.startswith("00-"):
            parts = traceparent.split("-")
            trace_id = parts[1] if len(parts) > 1 else uuid4().hex
            span_id = parts[2] if len(parts) > 2 else uuid4().hex[:16]
        elif correlation_id:
            clean = correlation_id.replace("-", "")
            trace_id = clean.ljust(32, "0")[:32]
            span_id = uuid4().hex[:16]
        else:
            trace_id = uuid4().hex
            span_id = uuid4().hex[:16]

        request.state.trace_id = trace_id
        request.state.span_id = span_id

        logger.debug(f"Inbound request trace context: trace_id={trace_id}, span_id={span_id}")

        response = await call_next(request)

        # Inject correlated tracing headers in outbound HTTP response
        response.headers["x-correlation-id"] = correlation_id if correlation_id else trace_id
        response.headers["traceparent"] = f"00-{trace_id}-{span_id}-01"

        return response
