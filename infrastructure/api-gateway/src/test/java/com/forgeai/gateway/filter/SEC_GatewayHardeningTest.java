package com.forgeai.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SEC_GatewayHardeningTest {

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    @Mock
    private ReactiveRedisOperations<String, String> redisOperations;

    @Mock
    private ReactiveValueOperations<String, String> valueOps;

    @Mock
    private GatewayFilterChain filterChain;

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String VICTIM_WS_ID = UUID.randomUUID().toString();
    private static final String OWN_WS_ID = UUID.randomUUID().toString();

    private GatewayTenantFilter buildFilter(ExchangeFunction exchangeFunction) {
        WebClient.Builder builder = WebClient.builder().exchangeFunction(exchangeFunction);
        return new GatewayTenantFilter(jwtDecoder, redisOperations, builder);
    }

    private Jwt createUserJwt(String token) {
        return new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", USER_ID, "role", "ROLE_USER")
        );
    }

    @BeforeEach
    void initMocks() {
        lenient().when(redisOperations.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("SEC-01: Cache miss calls identity-service, verifies PERSONAL workspace ownership, caches ALLOW, and forwards PERSONAL scope")
    void personalWorkspaceVerifiedViaIdentityServiceAndCached() {
        String token = "user.jwt.token";
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(createUserJwt(token)));
        when(valueOps.get("user:ws:" + USER_ID + ":" + OWN_WS_ID)).thenReturn(Mono.empty());
        when(valueOps.set(eq("user:ws:" + USER_ID + ":" + OWN_WS_ID), eq("ALLOW"), eq(Duration.ofMinutes(5))))
                .thenReturn(Mono.just(Boolean.TRUE));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        AtomicReference<String> seenUri = new AtomicReference<>();
        AtomicReference<String> seenAuth = new AtomicReference<>();
        GatewayTenantFilter filter = buildFilter(clientReq -> {
            seenUri.set(clientReq.url().toString());
            seenAuth.set(clientReq.headers().getFirst(HttpHeaders.AUTHORIZATION));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("{\"id\":\"" + OWN_WS_ID + "\",\"type\":\"PERSONAL\",\"ownerUserId\":\"" + USER_ID + "\"}")
                    .build());
        });

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Workspace-Id", OWN_WS_ID)
                .header("X-Workspace-Scope", "ORGANIZATION")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        assertThat(seenUri.get()).endsWith("/api/v1/workspaces/" + OWN_WS_ID);
        assertThat(seenAuth.get()).isEqualTo("Bearer " + token);
        verify(valueOps).set("user:ws:" + USER_ID + ":" + OWN_WS_ID, "ALLOW", Duration.ofMinutes(5));

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange downstream = captor.getValue();
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Workspace-Id")).isEqualTo(OWN_WS_ID);
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Workspace-Scope")).isEqualTo("PERSONAL");
        assertThat(downstream.getRequest().getHeaders().getFirst("X-ForgeAI-Internal")).isEqualTo("attested");
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("SEC-01: Cross-user X-Workspace-Id returning 403 from identity-service is rejected with 403 Forbidden")
    void crossUserWorkspaceHijackRejectedWith403() {
        String token = "attacker.jwt.token";
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(createUserJwt(token)));
        when(valueOps.get("user:ws:" + USER_ID + ":" + VICTIM_WS_ID)).thenReturn(Mono.empty());

        GatewayTenantFilter filter = buildFilter(clientReq -> Mono.just(
                ClientResponse.create(HttpStatus.FORBIDDEN).build()
        ));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Workspace-Id", VICTIM_WS_ID)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(valueOps).set("user:ws:" + USER_ID + ":" + VICTIM_WS_ID, "DENY", Duration.ofMinutes(1));
        verify(valueOps, never()).set(any(), eq("ALLOW"), any(Duration.class));
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("SEC-01: ORGANIZATION workspace passed via X-Workspace-Id is rejected with 403 Forbidden")
    void organizationWorkspaceViaWorkspaceHeaderRejectedWith403() {
        String token = "user.jwt.token";
        String orgWsId = UUID.randomUUID().toString();
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(createUserJwt(token)));
        when(valueOps.get("user:ws:" + USER_ID + ":" + orgWsId)).thenReturn(Mono.empty());

        GatewayTenantFilter filter = buildFilter(clientReq -> Mono.just(
                ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"id\":\"" + orgWsId + "\",\"type\":\"ORGANIZATION\",\"organizationId\":\"" + UUID.randomUUID() + "\"}")
                        .build()
        ));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/ai/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Workspace-Id", orgWsId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("SEC-04: Sensitive actuator, authenticated auth endpoints, and POST setup-callback are not public")
    void publicRouteClassificationRestrictsSensitiveEndpoints() {
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/actuator/health").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/actuator/health/readiness").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/actuator/prometheus").build())).isFalse();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/actuator/env").build())).isFalse();

        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/login").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/register").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/refresh").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/api/v1/auth/github/config").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/api/v1/auth/github/start").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/api/v1/auth/github/callback").build())).isTrue();

        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/logout").build())).isFalse();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/logout-all").build())).isFalse();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/github/link").build())).isFalse();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/auth/github/disconnect").build())).isFalse();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/api/v1/auth/github/status").build())).isFalse();

        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.get("/api/v1/installations/setup-callback").build())).isTrue();
        assertThat(GatewayTenantFilter.isPublicRoute(MockServerHttpRequest.post("/api/v1/installations/setup-callback").build())).isFalse();
    }

    @Test
    @DisplayName("SEC-04: Unauthenticated calls to restricted actuator and auth endpoints return 401 Unauthorized")
    void unauthenticatedCallsToRestrictedRoutesReturn401() {
        GatewayTenantFilter filter = buildFilter(clientReq -> Mono.empty());

        for (MockServerHttpRequest req : new MockServerHttpRequest[]{
                MockServerHttpRequest.get("/actuator/prometheus").build(),
                MockServerHttpRequest.post("/api/v1/auth/logout").build(),
                MockServerHttpRequest.post("/api/v1/auth/github/link").build(),
                MockServerHttpRequest.post("/api/v1/installations/setup-callback").build()
        }) {
            MockServerWebExchange exchange = MockServerWebExchange.from(req);
            StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();
            assertThat(exchange.getResponse().getStatusCode())
                    .as("Expected 401 for %s %s", req.getMethod(), req.getURI().getPath())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("PERF-BUG-06: Cached DENY workspace verdict short-circuits with 403 without calling identity-service")
    void cachedWorkspaceDenyShortCircuitsWith403WithoutIdentityServiceCall() {
        String token = "user.jwt.token";
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(createUserJwt(token)));
        when(valueOps.get("user:ws:" + USER_ID + ":" + VICTIM_WS_ID)).thenReturn(Mono.just("DENY"));

        AtomicInteger identityCalls = new AtomicInteger(0);
        GatewayTenantFilter filter = buildFilter(clientReq -> {
            identityCalls.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).build());
        });

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/test-runs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Workspace-Id", VICTIM_WS_ID)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(identityCalls.get()).isZero();
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("PERF-BUG-06: Organization request completes downstream without falsely triggering switchIfEmpty 403")
    void orgRequestForwardsWithoutPostCompletionForbidden() {
        String token = "user.jwt.token";
        String orgId = UUID.randomUUID().toString();
        when(jwtDecoder.decode(token)).thenReturn(Mono.just(createUserJwt(token)));
        when(valueOps.get("user:role:" + USER_ID + ":" + orgId)).thenReturn(Mono.just("OWNER"));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        GatewayTenantFilter filter = buildFilter(clientReq -> Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Org-Id", orgId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain)).verifyComplete();

        verify(filterChain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FORBIDDEN);
    }
}
