package com.forgeai.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveRedisOperations;

@Component
public class JwtRevocationGatewayFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtRevocationGatewayFilter.class);

    public static final String REVOCATION_KEY_PREFIX = "blacklist:session:";

    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveRedisOperations<String, String> redisOperations;

    public JwtRevocationGatewayFilter(ReactiveJwtDecoder jwtDecoder,
                                      ReactiveRedisOperations<String, String> redisOperations) {
        this.jwtDecoder = jwtDecoder;
        this.redisOperations = redisOperations;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Unauthenticated requests pass through to public routes
            return chain.filter(exchange);
        }

        String token = authHeader.substring(7).trim();

        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    // Extract session_id or jti claim
                    String rawSessionId = jwt.getClaimAsString("session_id");
                    final String sessionId = (rawSessionId != null && !rawSessionId.isBlank()) 
                            ? rawSessionId 
                            : jwt.getId();

                    if (sessionId == null || sessionId.isBlank()) {
                        return chain.filter(exchange);
                    }

                    String redisKey = REVOCATION_KEY_PREFIX + sessionId;

                    // O(1) Fast-Path lookup in Redis deny-list
                    return redisOperations.hasKey(redisKey)
                            .flatMap(isRevoked -> {
                                if (Boolean.TRUE.equals(isRevoked)) {
                                    log.warn("Blocked request with revoked session/JTI: {}", sessionId);
                                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                                    return exchange.getResponse().setComplete();
                                }
                                return chain.filter(exchange);
                            })
                            .onErrorResume(e -> {
                                String path = exchange.getRequest().getURI().getPath();
                                boolean isPrivileged = path.startsWith("/api/v1/operations") || path.startsWith("/api/v1/admin");
                                if (isPrivileged) {
                                    log.error("Redis unreachable during revocation check on privileged route {}, failing closed: {}", path, e.getMessage());
                                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                                    return exchange.getResponse().setComplete();
                                }
                                // Fail-open for user traffic if Redis partitions/fails
                                log.error("Redis unreachable during revocation check, failing open: {}", e.getMessage());
                                return chain.filter(exchange);
                            });
                })
                .onErrorResume(e -> {
                    // Token decoding errors are handled by GatewayTenantFilter / Security
                    return chain.filter(exchange);
                });
    }

    @Override
    public int getOrder() {
        // Run immediately after TraceCorrelationFilter and BEFORE GatewayTenantFilter routing
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
