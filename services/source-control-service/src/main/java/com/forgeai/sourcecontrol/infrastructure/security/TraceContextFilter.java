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
import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_ORG_ID = "organizationId";

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_ORG_ID = "X-Organization-Id";
    public static final String HEADER_ALT_ORG_ID = "X-Org-Id";

    private static final Pattern W3C_TRACEPARENT_PATTERN =
            Pattern.compile("^00-([0-9a-fA-F]{32})-([0-9a-fA-F]{16})-([0-9a-fA-F]{2})$");
    private static final String ALL_ZEROS_TRACE = "00000000000000000000000000000000";

    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incomingTraceparent = request.getHeader(HEADER_TRACEPARENT);
        String incomingCorrelationId = request.getHeader(HEADER_CORRELATION_ID);
        String incomingOrgId = request.getHeader(HEADER_ORG_ID);
        if (incomingOrgId == null || incomingOrgId.isBlank()) {
            incomingOrgId = request.getHeader(HEADER_ALT_ORG_ID);
        }

        String correlationId = (incomingCorrelationId != null && !incomingCorrelationId.isBlank())
                ? incomingCorrelationId.trim()
                : UUID.randomUUID().toString();

        String traceId;
        String spanId = generateRandomHex(16);
        String traceparent;

        boolean isValidW3c = incomingTraceparent != null
                && W3C_TRACEPARENT_PATTERN.matcher(incomingTraceparent).matches()
                && !incomingTraceparent.contains(ALL_ZEROS_TRACE);

        if (isValidW3c) {
            String[] parts = incomingTraceparent.split("-");
            traceId = parts[1].toLowerCase();
            traceparent = String.format("00-%s-%s-01", traceId, spanId);
        } else {
            String clean = correlationId.replace("-", "").toLowerCase();
            traceId = clean.length() == 32 ? clean : generateRandomHex(32);
            traceparent = String.format("00-%s-%s-01", traceId, spanId);
        }

        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_SPAN_ID, spanId);
        MDC.put(MDC_CORRELATION_ID, correlationId);
        if (incomingOrgId != null && !incomingOrgId.isBlank()) {
            MDC.put(MDC_ORG_ID, incomingOrgId.trim());
        }

        response.setHeader(HEADER_CORRELATION_ID, correlationId);
        response.setHeader(HEADER_TRACEPARENT, traceparent);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            MDC.remove(MDC_CORRELATION_ID);
            MDC.remove(MDC_ORG_ID);
        }
    }

    private String generateRandomHex(int length) {
        byte[] bytes = new byte[length / 2];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
