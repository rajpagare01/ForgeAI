package com.forgeai.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import org.springframework.data.redis.core.ReactiveRedisOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtRevocationGatewayTest {

    @Mock
    private ReactiveJwtDecoder jwtDecoder;

    @Mock
    private ReactiveRedisOperations<String, String> redisOperations;

    @Mock
    private GatewayFilterChain filterChain;

    private JwtRevocationGatewayFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtRevocationGatewayFilter(jwtDecoder, redisOperations);
    }

    @Test
    @DisplayName("Revoked session ID present in Redis deny-list aborts with HTTP 401 Unauthorized")
    void revokedSessionAbortsWith401() {
        String token = "valid-revoked-token";
        String sessionId = "sess-revoked-999";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-1", "session_id", sessionId)
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(redisOperations.hasKey("blacklist:session:" + sessionId)).thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Active non-revoked session passes through to gateway filter chain")
    void activeSessionPassesThrough() {
        String token = "valid-active-token";
        String sessionId = "sess-active-111";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-1", "session_id", sessionId)
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(redisOperations.hasKey("blacklist:session:" + sessionId)).thenReturn(Mono.just(false));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verify(filterChain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("Fail-open: If Redis is partitioned or throws an exception, user traffic is allowed through")
    void redisErrorFailsOpen() {
        String token = "valid-active-token";
        String sessionId = "sess-error-555";

        Jwt jwt = new Jwt(
                token,
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "user-1", "session_id", sessionId)
        );

        when(jwtDecoder.decode(token)).thenReturn(Mono.just(jwt));
        when(redisOperations.hasKey("blacklist:session:" + sessionId))
                .thenReturn(Mono.error(new RuntimeException("Redis cluster timeout")));
        when(filterChain.filter(any())).thenReturn(Mono.empty());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(filter.filter(exchange, filterChain))
                .verifyComplete();

        verify(filterChain).filter(exchange);
    }
}
