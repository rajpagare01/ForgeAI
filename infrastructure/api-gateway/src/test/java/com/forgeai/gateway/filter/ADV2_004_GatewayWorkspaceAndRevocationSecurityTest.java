package com.forgeai.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ADV2-004 & ADV2-007 Regression Test Suite:
 * - ADV2-004: Workspace context propagation and spoofing prevention in GatewayTenantFilter.
 * - ADV2-007: Fail-closed session revocation enforcement during Redis outages in JwtRevocationGatewayFilter.
 */
@ExtendWith(MockitoExtension.class)
class ADV2_004_GatewayWorkspaceAndRevocationSecurityTest {

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    @Mock
    private ReactiveRedisOperations<String, String> redisOperations;

    @Mock
    private GatewayFilterChain filterChain;

    private GatewayTenantFilter tenantFilter;
    private JwtRevocationGatewayFilter revocationFilter;

    private static final String USER_ID = UUID.randomUUID().toString();
    private static final String ORG_ID = UUID.randomUUID().toString();
    private static final String WS_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        tenantFilter = new GatewayTenantFilter(jwtDecoder, redisOperations, WebClient.builder());
        revocationFilter = new JwtRevocationGatewayFilter(jwtDecoder, redisOperations);
        revocationFilter.setFailClosed(true);
    }

    @Test
    @DisplayName("ADV2-004: Personal workspace request with valid X-Workspace-Id forwards workspace headers downstream")
    void personalWorkspaceContextForwardsWorkspaceHeadersDownstream() {
        String token = "valid.personal.jwt";
        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", USER_ID, "role", "ROLE_USER")
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", WS_ID)
                .header("X-Workspace-Scope", "PERSONAL")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(tenantFilter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstream = captor.getValue();
        assertThat(downstream.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(USER_ID);
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Workspace-Id")).isEqualTo(WS_ID);
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Workspace-Scope")).isEqualTo("PERSONAL");
        assertThat(downstream.getRequest().getHeaders().getFirst("X-ForgeAI-Internal")).isEqualTo("attested");
    }

    @Test
    @DisplayName("ADV2-004: Malformed X-Workspace-Id is rejected with HTTP 400 Bad Request")
    void malformedWorkspaceIdReturns400() {
        String token = "valid.personal.jwt";
        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", USER_ID, "role", "ROLE_USER")
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", "not-a-valid-uuid-12345")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(tenantFilter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("ADV2-004: In organization context, client-supplied X-Workspace-Id is stripped and never forwarded downstream")
    void organizationContextStripsClientWorkspaceId() {
        String token = "valid.org.jwt";
        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", USER_ID, "org_id", ORG_ID, "role", "ROLE_USER")
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        String conflictingWsId = UUID.randomUUID().toString();
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-Workspace-Id", conflictingWsId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(tenantFilter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstream = captor.getValue();
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Org-Id")).isEqualTo(ORG_ID);
        assertThat(downstream.getRequest().getHeaders().getFirst("X-Workspace-Id")).isNull();
        assertThat(downstream.getRequest().getHeaders().getFirst("X-ForgeAI-Internal")).isEqualTo("attested");
    }

    @Test
    @DisplayName("ADV2-007: Redis connection failure during token revocation check fails closed with HTTP 503")
    void redisOutageDuringRevocationCheckFailsClosed() {
        String token = "valid.user.jwt";
        String sessionId = "session-under-revocation-check";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", USER_ID, "session_id", sessionId)
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(redisOperations.hasKey("blacklist:session:" + sessionId))
                .thenReturn(Mono.error(new RedisConnectionFailureException("Connection reset by peer")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(revocationFilter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(filterChain);
    }
}
