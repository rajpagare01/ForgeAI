package com.forgeai.identity.api.exception;

import java.time.Instant;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        String correlationId
) {
    public ApiErrorResponse(Instant timestamp, int status, String code, String message, String path) {
        this(timestamp, status, code, message, path, null, null);
    }
}
