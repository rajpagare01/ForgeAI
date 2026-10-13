package com.forgeai.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class GatewayTenantFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTenantFilter.class);

    private final ReactiveJwtDecoder jwtDecoder;

    public GatewayTenantFilter(ReactiveJwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientOrgId = exchange.getRequest().getHeaders().getFirst("X-Org-Id");

        // Step 1: Strip any inbound client-spoofed headers to guarantee boundary integrity
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> {
                    httpHeaders.remove("X-User-Id");
                    httpHeaders.remove("X-Org-Id");
                    httpHeaders.remove("X-User-Roles");
                    httpHeaders.remove("X-Permissions");
                })
                .build();

        String authHeader = sanitizedRequest.getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Unauthenticated requests pass through to downstream / public endpoints (e.g., /auth/login, /jwks.json)
            return chain.filter(exchange.mutate().request(sanitizedRequest).build());
        }

        String token = authHeader.substring(7).trim();

        // Step 2: Validate JWT claims via JWKS / asymmetric public key
        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    String userId = jwt.getSubject();
                    String orgId = jwt.getClaimAsString("org_id");
                    if ((orgId == null || orgId.isBlank()) && clientOrgId != null && !clientOrgId.isBlank()) {
                        orgId = clientOrgId.trim();
                    }
                    String role = jwt.getClaimAsString("role");
                    List<String> permissions = jwt.getClaimAsStringList("permissions");

                    // Step 3: Forward validated tenant context downstream via attested headers
                    ServerHttpRequest.Builder requestBuilder = sanitizedRequest.mutate();

                    if (userId != null && !userId.isBlank()) {
                        requestBuilder.header("X-User-Id", userId);
                    }
                    if (orgId != null && !orgId.isBlank()) {
                        requestBuilder.header("X-Org-Id", orgId);
                    }
                    if (role != null && !role.isBlank()) {
                        requestBuilder.header("X-User-Roles", role);
                    }
                    if (permissions != null && !permissions.isEmpty()) {
                        requestBuilder.header("X-Permissions", String.join(",", permissions));
                    }

                    ServerHttpRequest downstreamRequest = requestBuilder.build();
                    log.debug("Attested tenant context propagated downstream: user={}, org={}, role={}", userId, orgId, role);

                    return chain.filter(exchange.mutate().request(downstreamRequest).build());
                })
                .onErrorResume(e -> {
                    log.warn("Invalid JWT presented at Gateway: {}", e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        // Execute early in the gateway filter chain
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
