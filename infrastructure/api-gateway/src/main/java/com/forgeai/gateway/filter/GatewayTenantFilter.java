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
import org.springframework.http.HttpMethod;
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

    public static boolean isPublicRoute(ServerHttpRequest request) {
        if (request.getMethod() == HttpMethod.OPTIONS) {
            return true;
        }
        String path = request.getURI().getPath();
        return path.equals("/actuator/health")
                || path.startsWith("/actuator/health/")
                || path.startsWith("/.well-known")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/register")
                || path.equals("/api/v1/auth/refresh")
                || path.equals("/api/v1/auth/github/config")
                || path.equals("/api/v1/auth/github/start")
                || path.equals("/api/v1/auth/github/callback")
                || path.startsWith("/api/v1/webhooks")
                || path.startsWith("/api/v1/github/installation-callback")
                || (request.getMethod() == HttpMethod.GET && path.startsWith("/api/v1/installations/setup-callback"))
                || path.equals("/api/v1/ai/health")
                || path.equals("/api/v1/ai/healthz");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientOrgId = exchange.getRequest().getHeaders().getFirst("X-Org-Id");
        if (clientOrgId == null || clientOrgId.isBlank()) {
            clientOrgId = exchange.getRequest().getHeaders().getFirst("X-Organization-Id");
        }
        String clientWorkspaceId = exchange.getRequest().getHeaders().getFirst("X-Workspace-Id");
        String clientWorkspaceScope = exchange.getRequest().getHeaders().getFirst("X-Workspace-Scope");

        // Step 1: Strip any inbound client-spoofed headers to guarantee boundary integrity
        ServerHttpRequest sanitizedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> {
                    httpHeaders.remove("X-User-Id");
                    httpHeaders.remove("X-Org-Id");
                    httpHeaders.remove("X-Organization-Id");
                    httpHeaders.remove("X-User-Roles");
                    httpHeaders.remove("X-Permissions");
                    httpHeaders.remove("X-Workspace-Id");
                    httpHeaders.remove("X-Workspace-Scope");
                    httpHeaders.remove("X-ForgeAI-Internal");
                })
                .build();

        boolean isPublic = isPublicRoute(sanitizedRequest);
        String authHeader = sanitizedRequest.getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (isPublic) {
                // Unauthenticated requests pass through to public endpoints (e.g. /auth/login, /webhooks/**, etc.)
                return chain.filter(exchange.mutate().request(sanitizedRequest).build());
            } else {
                // Missing JWT on protected route => 401 Unauthorized
                log.warn("Blocked unauthenticated request to protected route: {}", sanitizedRequest.getURI().getPath());
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty()) {
            if (isPublic) {
                return chain.filter(exchange.mutate().request(sanitizedRequest).build());
            }
            log.warn("Blocked empty Bearer token on route: {}", sanitizedRequest.getURI().getPath());
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        final String requestedClientOrgId = clientOrgId;
        final String requestedClientWsId = clientWorkspaceId;
        final String requestedClientWsScope = clientWorkspaceScope;

        // Step 2: Validate JWT claims via JWKS / asymmetric public key
        return jwtDecoder.decode(token)
                .flatMap(jwt -> {
                    String userId = jwt.getSubject();
                    String jwtOrgId = jwt.getClaimAsString("org_id");
                    String role = jwt.getClaimAsString("role");
                    List<String> permissions = jwt.getClaimAsStringList("permissions");


                    // If JWT already has an authoritative org_id claim:
                    if (jwtOrgId != null && !jwtOrgId.isBlank()) {
                        if (requestedClientOrgId != null && !requestedClientOrgId.isBlank() && !requestedClientOrgId.equalsIgnoreCase(jwtOrgId)) {
                            log.warn("Blocked spoofed X-Org-Id: client={}, jwt={}", requestedClientOrgId, jwtOrgId);
                            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                            return exchange.getResponse().setComplete();
                        }
                        // For org context, client-supplied workspaceId is stripped downstream
                        return forwardDownstream(exchange, chain, sanitizedRequest, userId, jwtOrgId, role, permissions, null, "ORGANIZATION");
                    }

                    String path = exchange.getRequest().getURI().getPath();
                    boolean isUserScopedRoute = path.startsWith("/api/v1/workspaces") ||
                                                path.startsWith("/api/v1/organizations") ||
                                                path.startsWith("/api/v1/users") ||
                                                path.startsWith("/api/v1/auth") ||
                                                path.startsWith("/.well-known") ||
                                                path.startsWith("/actuator");

                    if (isUserScopedRoute) {
                        return forwardDownstream(exchange, chain, sanitizedRequest, userId, null, role, permissions, null, null);
                    }

                    // If JWT does not have org_id claim, but client requested a specific org context:
                    if (requestedClientOrgId != null && !requestedClientOrgId.isBlank()) {
                        String targetOrg = requestedClientOrgId.trim();
                        return resolveUserOrgRole(userId, targetOrg, token)
                                .flatMap(resolvedRole -> {
                                    String effectiveRole = (role != null && !role.isBlank()) ? role : resolvedRole;
                                    return forwardDownstream(exchange, chain, sanitizedRequest, userId, targetOrg, effectiveRole, permissions, null, "ORGANIZATION");
                                })
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Access denied: user {} is NOT a member of requested org {}", userId, targetOrg);
                                    exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                    return exchange.getResponse().setComplete();
                                }));
                    }

                    // Personal workspace context on resource routes:
                    if (requestedClientWsId != null && !requestedClientWsId.isBlank()) {
                        String trimmedWsId = requestedClientWsId.trim();
                        try {
                            java.util.UUID.fromString(trimmedWsId);
                        } catch (IllegalArgumentException ex) {
                            log.warn("Blocked malformed X-Workspace-Id header: {}", requestedClientWsId);
                            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                            return exchange.getResponse().setComplete();
                        }
                        return verifyUserWorkspaceAccess(userId, trimmedWsId, token)
                                .defaultIfEmpty(Boolean.FALSE)
                                .flatMap(allowed -> {
                                    if (!Boolean.TRUE.equals(allowed)) {
                                        log.warn("Blocked unauthorized X-Workspace-Id {} for user {}", trimmedWsId, userId);
                                        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                                        return exchange.getResponse().setComplete();
                                    }
                                    return forwardDownstream(
                                            exchange, chain, sanitizedRequest, userId, null, role, permissions, trimmedWsId, "PERSONAL");
                                });
                    }

                    // No org and no workspace context
                    return forwardDownstream(exchange, chain, sanitizedRequest, userId, null, role, permissions, null, null);
                })
                .onErrorResume(e -> {
                    log.warn("Invalid JWT presented at Gateway: {}", e.getMessage());
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    private static final String REDIS_USER_ROLE_PREFIX = "user:role:";
    private static final String REDIS_USER_WS_PREFIX = "user:ws:";

    /**
     * SEC-01: Verifies that the authenticated user owns the requested personal workspace.
     * Checks Redis cache ("user:ws:<userId>:<workspaceId>") first; on miss queries identity-service
     * GET /api/v1/workspaces/{workspaceId} with the caller's Bearer token, confirms HTTP 200 with
     * a PERSONAL workspace owned by the user, and caches ALLOW for 5 minutes.
     */
    private Mono<Boolean> verifyUserWorkspaceAccess(String userId, String workspaceId, String token) {
        String cacheKey = REDIS_USER_WS_PREFIX + userId + ":" + workspaceId;
        org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOps =
                redisOperations != null ? redisOperations.opsForValue() : null;
        Mono<String> cacheLookup = valueOps != null
                ? valueOps.get(cacheKey).onErrorResume(ex -> Mono.empty())
                : Mono.empty();

        return cacheLookup
                .flatMap(cached -> "ALLOW".equals(cached) ? Mono.just(Boolean.TRUE) : Mono.<Boolean>empty())
                .switchIfEmpty(Mono.defer(() -> webClient.get()
                        .uri(identityServiceUri + "/api/v1/workspaces/" + workspaceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(new org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>() {})
                                        .flatMap(body -> {
                                            Object typeObj = body.get("type");
                                            String wsType = typeObj != null ? typeObj.toString() : "";
                                            Object ownerObj = body.get("ownerUserId");
                                            if (!"PERSONAL".equalsIgnoreCase(wsType)) {
                                                log.warn("Rejected non-PERSONAL workspace {} (type={}) via X-Workspace-Id for user {}",
                                                        workspaceId, wsType, userId);
                                                return Mono.<Boolean>empty();
                                            }
                                            if (ownerObj != null && !userId.equals(ownerObj.toString())) {
                                                log.warn("Workspace {} owner {} does not match authenticated user {}",
                                                        workspaceId, ownerObj, userId);
                                                return Mono.<Boolean>empty();
                                            }
                                            if (valueOps == null) {
                                                return Mono.just(Boolean.TRUE);
                                            }
                                            return valueOps
                                                    .set(cacheKey, "ALLOW", java.time.Duration.ofMinutes(5))
                                                    .thenReturn(Boolean.TRUE)
                                                    .onErrorReturn(Boolean.TRUE);
                                        });
                            }
                            log.warn("Identity-service denied workspace {} verification for user {}: status={}",
                                    workspaceId, userId, response.statusCode());
                            return response.releaseBody().then(Mono.<Boolean>empty());
                        })
                        .onErrorResume(ex -> {
                            log.warn("Failed workspace {} verification for user {}: {}", workspaceId, userId, ex.getMessage());
                            return Mono.empty();
                        })));
    }

    private Mono<String> resolveUserOrgRole(String userId, String orgId, String token) {
        String cacheKey = REDIS_USER_ROLE_PREFIX + userId + ":" + orgId;

        return redisOperations.opsForValue().get(cacheKey)
                .onErrorResume(e -> Mono.empty())
                .flatMap(cachedRole -> {
                    if (cachedRole != null && !cachedRole.isBlank()) {
                        return Mono.just(cachedRole);
                    }
                    return fetchRoleFromIdentityService(userId, orgId, token, cacheKey);
                })
                .switchIfEmpty(Mono.defer(() -> fetchRoleFromIdentityService(userId, orgId, token, cacheKey)));
    }

    private Mono<String> fetchRoleFromIdentityService(String userId, String orgId, String token, String cacheKey) {
        return webClient.get()
                .uri(identityServiceUri + "/api/v1/organizations/" + orgId + "/members")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(json -> {
                    try {
                        JsonNode root = objectMapper.readTree(json);
                        if (root.isArray()) {
                            for (JsonNode node : root) {
                                String memberUserId = node.path("userId").asText(null);
                                String memberRole = node.path("role").asText(null);
                                String status = node.path("status").asText("ACTIVE");
                                if (memberUserId != null && memberUserId.equalsIgnoreCase(userId) && "ACTIVE".equalsIgnoreCase(status)) {
                                    String roleToCache = memberRole != null ? memberRole : "MEMBER";
                                    return redisOperations.opsForValue()
                                            .set(cacheKey, roleToCache, Duration.ofMinutes(5))
                                            .onErrorResume(e -> Mono.just(true))
                                            .thenReturn(roleToCache);
                                }
                            }
                        }
                        return Mono.empty();
                    } catch (Exception ex) {
                        log.error("Failed to parse organization members JSON: {}", ex.getMessage());
                        return Mono.empty();
                    }
                })
                .onErrorResume(ex -> {
                    log.error("Failed to fetch organization members from identity-service: {}", ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> forwardDownstream(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        ServerHttpRequest sanitizedRequest,
                                        String userId,
                                        String orgId,
                                        String role,
                                        List<String> permissions,
                                        String workspaceId,
                                        String workspaceScope) {
        ServerHttpRequest.Builder requestBuilder = sanitizedRequest.mutate();

        if (userId != null && !userId.isBlank()) {
            requestBuilder.header("X-User-Id", userId);
        }
        if (orgId != null && !orgId.isBlank()) {
            requestBuilder.header("X-Org-Id", orgId);
            requestBuilder.header("X-Organization-Id", orgId);
        }
        if (workspaceId != null && !workspaceId.isBlank()) {
            requestBuilder.header("X-Workspace-Id", workspaceId);
        }
        if (workspaceScope != null && !workspaceScope.isBlank()) {
            requestBuilder.header("X-Workspace-Scope", workspaceScope);
        }
        if (role != null && !role.isBlank()) {
            requestBuilder.header("X-User-Roles", role);
        }
        if (permissions != null && !permissions.isEmpty()) {
            requestBuilder.header("X-Permissions", String.join(",", permissions));
        }
        requestBuilder.header("X-ForgeAI-Internal", "attested");

        ServerHttpRequest downstreamRequest = requestBuilder.build();
        log.debug("Attested tenant context propagated downstream: user={}, org={}, ws={}, scope={}, role={}", 
                userId, orgId, workspaceId, workspaceScope, role);

        return chain.filter(exchange.mutate().request(downstreamRequest).build());
    }

    private Mono<Void> forwardDownstream(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        ServerHttpRequest sanitizedRequest,
                                        String userId,
                                        String orgId,
                                        String role,
                                        List<String> permissions) {
        return forwardDownstream(exchange, chain, sanitizedRequest, userId, orgId, role, permissions, orgId, orgId != null ? "ORGANIZATION" : null);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
