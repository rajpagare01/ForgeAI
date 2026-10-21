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
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayTenantFilterTest {

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    @Mock
    private ReactiveRedisOperations<String, String> redisOperations;

    @Mock
    private ReactiveSetOperations<String, String> setOperations;

    @Mock
    private GatewayFilterChain filterChain;

    private GatewayTenantFilter filter;

    @BeforeEach
    void setUp() {
        WebClient.Builder webClientBuilder = WebClient.builder();
        filter = new GatewayTenantFilter(jwtDecoder, redisOperations, webClientBuilder);
    }

    @Test
    @DisplayName("Regression Test 1: No Authorization header on protected route returns HTTP 401")
    void missingAuthorizationHeaderReturns401() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("Regression Test 2: Malformed JWT token on protected route returns HTTP 401")
    void malformedJwtReturns401() {
        String token = "malformed.invalid.token";
        when(jwtDecoder.decode(token)).thenReturn(Mono.error(new BadJwtException("Malformed JWT")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("Regression Test 3: Expired JWT on protected route returns HTTP 401")
    void expiredJwtReturns401() {
        String token = "expired.jwt.token";
        when(jwtDecoder.decode(token)).thenReturn(Mono.error(new BadJwtException("Jwt expired at 2026-10-01T00:00:00Z")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("Regression Test 4: Valid JWT forwards downstream with authenticated identity context")
    void validJwtProceedsNormally() {
        String token = "valid.jwt.token";
        String userId = "user-123";
        String orgId = "org-456";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", userId, "org_id", orgId, "role", "ROLE_USER", "permissions", List.of("project:read"))
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstreamExchange = captor.getValue();
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(userId);
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Org-Id")).isEqualTo(orgId);
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Organization-Id")).isEqualTo(orgId);
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("ROLE_USER");
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Permissions")).isEqualTo("project:read");
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-ForgeAI-Internal")).isEqualTo("attested");
    }

    @Test
    @DisplayName("Regression Test 5: Client-supplied fake X-Org-Id conflicting with JWT org_id returns HTTP 403")
    void fakeOrgIdMismatchReturns403() {
        String token = "valid.jwt.token";
        String jwtOrgId = "authentic-org-111";
        String fakeClientOrgId = "attacker-org-999";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-victim", "org_id", jwtOrgId)
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-Org-Id", fakeClientOrgId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("Regression Test 6: Inbound client-supplied X-User-Id is stripped and replaced with JWT subject")
    void clientSuppliedUserIdIsIgnored() {
        String token = "valid.jwt.token";
        String authenticUserId = "authentic-user-123";
        String spoofedUserId = "spoofed-admin-999";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", authenticUserId, "org_id", "org-456")
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Id", spoofedUserId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstreamExchange = captor.getValue();
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Id")).isEqualTo(authenticUserId);
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Id")).isNotEqualTo(spoofedUserId);
    }

    @Test
    @DisplayName("Regression Test 7: Inbound client-supplied X-Permissions and X-User-Roles are stripped and overridden")
    void clientSuppliedPermissionsAreIgnored() {
        String token = "valid.jwt.token";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-1", "org_id", "org-1", "role", "ROLE_USER", "permissions", List.of("project:read"))
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .header("X-User-Roles", "ROLE_SUPER_ADMIN")
                .header("X-Permissions", "outbox:replay,admin:all,*")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstreamExchange = captor.getValue();
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Roles")).isEqualTo("ROLE_USER");
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Permissions")).isEqualTo("project:read");
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-Permissions")).doesNotContain("outbox:replay");
    }

    @Test
    @DisplayName("Public route without Authorization header passes through safely")
    void publicRouteWithoutAuthPassesThrough() {
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/webhooks/github")
                .header("X-Hub-Signature-256", "sha256=abcdef123456")
                .header("X-User-Id", "spoofed-user") // Should be stripped even on public routes
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());

        ServerWebExchange downstreamExchange = captor.getValue();
        assertThat(downstreamExchange.getRequest().getHeaders().getFirst("X-User-Id")).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
