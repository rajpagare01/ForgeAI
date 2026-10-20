import json
import logging
import os
import re
import sys
from datetime import datetime, timezone
from contextvars import ContextVar

trace_id_cv: ContextVar[str] = ContextVar("trace_id", default="")
span_id_cv: ContextVar[str] = ContextVar("span_id", default="")
correlation_id_cv: ContextVar[str] = ContextVar("correlation_id", default="")
org_id_cv: ContextVar[str] = ContextVar("org_id", default="")

SECRET_PATTERN = re.compile(
    r"(bearer\s+[\w\-._]+|token[=:]\s*[\w\-._]+|key[=:]\s*[\w\-._]+|password[=:]\s*[\w\-._]+)",
    re.IGNORECASE,
)


def mask_sensitive_data(message: str) -> str:
    if not message:
        return message
    return SECRET_PATTERN.sub("[REDACTED]", str(message))


class StructuredJsonFormatter(logging.Formatter):
    def __init__(self, service_name: str = "ai-service"):
        super().__init__()
        self.service_name = service_name
        self.environment = os.getenv("ENVIRONMENT", "production")

    def format(self, record: logging.LogRecord) -> str:
        trace_id = trace_id_cv.get()
        span_id = span_id_cv.get()
        correlation_id = correlation_id_cv.get()
        org_id = org_id_cv.get()

        log_data = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "service": self.service_name,
            "environment": self.environment,
            "level": record.levelname,
            "logger": record.name,
            "message": mask_sensitive_data(record.getMessage()),
            "traceId": trace_id if trace_id else None,
            "spanId": span_id if span_id else None,
            "correlationId": correlation_id if correlation_id else None,
        }

        if org_id:
            log_data["organizationId"] = org_id

        if hasattr(record, "event_type"):
            log_data["eventType"] = record.event_type
        if hasattr(record, "operation"):
            log_data["operation"] = record.operation
        if hasattr(record, "outcome"):
            log_data["outcome"] = record.outcome
        if hasattr(record, "latency_ms"):
            log_data["latency"] = record.latency_ms
        if hasattr(record, "error_class"):
            log_data["errorClass"] = record.error_class

        if record.exc_info:
            log_data["errorClass"] = record.exc_info[0].__name__ if record.exc_info[0] else None
            # Do not log raw full trace with potential tokens in production
            log_data["errorMessage"] = mask_sensitive_data(str(record.exc_info[1]))

        return json.dumps({k: v for k, v in log_data.items() if v is not None})


def setup_logging():
    root_logger = logging.getLogger()
    root_logger.setLevel(logging.INFO)
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(StructuredJsonFormatter())
    root_logger.handlers = [handler]
