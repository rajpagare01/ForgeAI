package com.forgeai.identity.presentation.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that intercepts incoming HTTP requests to ensure a Correlation ID is present.
 * If present in the 'X-Correlation-ID' header, it uses it; otherwise, generates a new UUID.
 * The Correlation ID is then added to the MDC (Mapped Diagnostic Context) for structured logging
 * and returned in the HTTP response headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String TRACE_ID_HEADER = "traceparent";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = UUID.randomUUID().toString();
        }
        
        String traceId = request.getHeader(TRACE_ID_HEADER);

        try {
            MDC.put(MDC_CORRELATION_ID, correlationId);
            if (traceId != null) {
                MDC.put(MDC_TRACE_ID, traceId);
            }
            
            response.setHeader(CORRELATION_ID_HEADER, correlationId);
            
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
