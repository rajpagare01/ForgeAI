package com.forgeai.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class TraceCorrelationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TraceCorrelationFilter.class);

    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_TRACESTATE = "tracestate";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        
        String incomingTraceparent = request.getHeaders().getFirst(HEADER_TRACEPARENT);
        String incomingCorrelationId = request.getHeaders().getFirst(HEADER_CORRELATION_ID);

        String traceId;
        String spanId = generateRandomHex(16);
        String traceparent;

        if (incomingTraceparent != null && incomingTraceparent.matches("^00-[0-9a-fA-F]{32}-[0-9a-fA-F]{16}-[0-9a-fA-F]{2}$")) {
            // Valid W3C traceparent format: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
            String[] parts = incomingTraceparent.split("-");
            traceId = parts[1];
            traceparent = String.format("00-%s-%s-01", traceId, spanId);
        } else if (incomingCorrelationId != null && !incomingCorrelationId.isBlank()) {
            // Strip any hyphens or format to 32-hex
            String clean = incomingCorrelationId.replace("-", "");
            traceId = clean.length() == 32 ? clean : String.format("%032x", UUID.randomUUID().hashCode() & 0xFFFFFFFFL);
            traceparent = String.format("00-%s-%s-01", traceId, spanId);
        } else {
            // Generate clean W3C 32-hex traceId
            traceId = generateRandomHex(32);
            traceparent = String.format("00-%s-%s-01", traceId, spanId);
        }

        String correlationId = (incomingCorrelationId != null && !incomingCorrelationId.isBlank())
                ? incomingCorrelationId
                : traceId;

        // Downstream request mutation
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER_CORRELATION_ID, correlationId)
                .header(HEADER_TRACEPARENT, traceparent)
                .build();

        // Mutate response header to return X-Correlation-Id and traceparent
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(HEADER_CORRELATION_ID, correlationId);
        response.getHeaders().set(HEADER_TRACEPARENT, traceparent);

        log.debug("Trace context bound at API Gateway: traceId={}, spanId={}, correlationId={}",
                traceId, spanId, correlationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    private String generateRandomHex(int length) {
        String uuidStr = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        return uuidStr.substring(0, length).toLowerCase();
    }

    @Override
    public int getOrder() {
        // Run BEFORE authentication and tenant extraction
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
