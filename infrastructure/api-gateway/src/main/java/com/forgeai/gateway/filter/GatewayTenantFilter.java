package com.forgeai.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class GatewayTenantFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayTenantFilter.class);
    private static final String REDIS_USER_ORGS_PREFIX = "user:orgs:";

    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveRedisOperations<String, String> redisOperations;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${IDENTITY_SERVICE_URI:http://identity-service:8081}")
    private String identityServiceUri;

    public GatewayTenantFilter(ReactiveJwtDecoder jwtDecoder,
                               ReactiveRedisOperations<String, String> redisOperations,
                               WebClient.Builder webClientBuilder) {
        this.jwtDecoder = jwtDecoder;
        this.redisOperations = redisOperations;
        this.webClient = webClientBuilder.build();
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
                    String jwtOrgId = jwt.getClaimAsString("org_id");
                    String role = jwt.getClaimAsString("role");
                    List<String> permissions = jwt.getClaimAsStringList("permissions");

                    // If JWT already has an authoritative org_id claim:
                    if (jwtOrgId != null && !jwtOrgId.isBlank()) {
                        if (clientOrgId != null && !clientOrgId.isBlank() && !clientOrgId.equalsIgnoreCase(jwtOrgId)) {
                            log.warn("Blocked spoofed X-Org-Id: client={}, jwt={}", clientOrgId, jwtOrgId);
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }
                        return forwardDownstream(exchange, chain, sanitizedRequest, userId, jwtOrgId, role, permissions);
                    }

                    String path = exchange.getRequest().getURI().getPath();
                    boolean isUserScopedRoute = path.startsWith("/api/v1/organizations") ||
                                                path.startsWith("/api/v1/users") ||
                                                path.startsWith("/api/v1/auth") ||
                                                path.startsWith("/.well-known") ||
                                                path.startsWith("/actuator");

                    if (isUserScopedRoute) {
                        return forwardDownstream(exchange, chain, sanitizedRequest, userId, null, role, permissions);
                    }

                    // If JWT does not have org_id claim, but client requested a specific org context:
                    if (clientOrgId != null && !clientOrgId.isBlank()) {
                        return verifyUserBelongsToOrg(userId, clientOrgId.trim(), token)
                                .flatMap(isMember -> {
                                    if (Boolean.FALSE.equals(isMember)) {
                                        log.warn("Access denied: user {} is NOT a member of requested org {}", userId, clientOrgId);
                                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                        return exchange.getResponse().setComplete();
                                    }
                                    return forwardDownstream(exchange, chain, sanitizedRequest, userId, clientOrgId.trim(), role, permissions);
                                });
                    }

                    // No org context requested
                    return forwardDownstream(exchange, chain, sanitizedRequest, userId, null, role, permissions);
                })
                .onErrorResume(e -> {
                    log.warn("Invalid JWT presented at Gateway: {}", e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private Mono<Boolean> verifyUserBelongsToOrg(String userId, String orgId, String token) {
        String cacheKey = REDIS_USER_ORGS_PREFIX + userId;

        return redisOperations.opsForSet().isMember(cacheKey, orgId)
                .flatMap(isCachedMember -> {
                    if (Boolean.TRUE.equals(isCachedMember)) {
                        return Mono.just(true);
                    }
                    return webClient.get()
                            .uri(identityServiceUri + "/api/v1/organizations")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .retrieve()
                            .bodyToMono(String.class)
                            .flatMap(json -> {
                                try {
                                    JsonNode root = objectMapper.readTree(json);
                                    List<String> userOrgs = new ArrayList<>();
                                    if (root.isArray()) {
                                        for (JsonNode node : root) {
                                            String id = node.path("id").asText(null);
                                            if (id != null) {
                                                userOrgs.add(id);
                                            }
                                        }
                                    }
                                    if (!userOrgs.isEmpty()) {
                                        return redisOperations.opsForSet().add(cacheKey, userOrgs.toArray(new String[0]))
                                                .then(redisOperations.expire(cacheKey, Duration.ofMinutes(5)))
                                                .thenReturn(userOrgs.contains(orgId));
                                    }
                                    return Mono.just(false);
                                } catch (Exception ex) {
                                    log.error("Failed to parse organizations JSON: {}", ex.getMessage());
                                    return Mono.just(false);
                                }
                            })
                            .onErrorResume(ex -> {
                                log.error("Failed to verify org membership with identity-service: {}", ex.getMessage());
                                return Mono.just(false);
                            });
                });
    }

    private Mono<Void> forwardDownstream(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        ServerHttpRequest sanitizedRequest,
                                        String userId,
                                        String orgId,
                                        String role,
                                        List<String> permissions) {
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
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
