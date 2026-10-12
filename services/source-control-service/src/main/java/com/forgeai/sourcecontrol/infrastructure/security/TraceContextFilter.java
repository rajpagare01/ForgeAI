package com.forgeai.sourcecontrol.infrastructure.security;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceparent = request.getHeader(HEADER_TRACEPARENT);
        String correlationId = request.getHeader(HEADER_CORRELATION_ID);

        String traceId;
        String spanId;

        if (traceparent != null && traceparent.startsWith("00-")) {
            String[] parts = traceparent.split("-");
            traceId = parts.length > 1 ? parts[1] : UUID.randomUUID().toString().replace("-", "");
            spanId = parts.length > 2 ? parts[2] : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } else if (correlationId != null && !correlationId.isBlank()) {
            traceId = correlationId.replace("-", "");
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } else {
            traceId = UUID.randomUUID().toString().replace("-", "");
            spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);

        response.setHeader(HEADER_CORRELATION_ID, correlationId != null ? correlationId : traceId);
        response.setHeader(HEADER_TRACEPARENT, String.format("00-%s-%s-01", traceId, spanId));

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }
}
