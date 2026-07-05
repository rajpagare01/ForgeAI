package com.forgeai.identity.presentation.factory;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Factory for creating standard RFC 7807 ProblemDetail responses.
 */
@Component
public class ProblemDetailFactory {

    private static final String CORRELATION_ID_KEY = "correlationId";
    private static final String TRACE_ID_KEY = "traceId";

    public ProblemDetail create(HttpStatus status, String title, String detail, URI type, String errorCode) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        if (type != null) {
            problemDetail.setType(type);
        }
        
        problemDetail.setProperty("timestamp", Instant.now().toString());
        if (errorCode != null) {
            problemDetail.setProperty("errorCode", errorCode);
        }

        String correlationId = MDC.get(CORRELATION_ID_KEY);
        if (correlationId != null) {
            problemDetail.setProperty(CORRELATION_ID_KEY, correlationId);
        }

        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId != null) {
            problemDetail.setProperty(TRACE_ID_KEY, traceId);
        }

        return problemDetail;
    }

    public ProblemDetail createValidationProblem(String detail, Map<String, String> fieldErrors) {
        ProblemDetail problemDetail = create(HttpStatus.BAD_REQUEST, "Validation Failed", detail, URI.create("about:blank"), "VALIDATION_ERROR");
        problemDetail.setProperty("validationErrors", fieldErrors);
        return problemDetail;
    }
}
