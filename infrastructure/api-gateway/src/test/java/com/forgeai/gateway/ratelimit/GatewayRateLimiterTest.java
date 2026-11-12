package com.forgeai.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;

class GatewayRateLimiterTest {

    private RateLimiterConfig rateLimiterConfig;

    @BeforeEach
    void setUp() {
        rateLimiterConfig = new RateLimiterConfig();
    }

    @Test
    @DisplayName("userKeyResolver resolves key from X-User-Id header")
    void testUserKeyResolverFromHeader() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects")
                .header("X-User-Id", "usr-456")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(rateLimiterConfig.userKeyResolver().resolve(exchange))
                .expectNext("user:usr-456")
                .verifyComplete();
    }

    @Test
    @DisplayName("userKeyResolver resolves key from ReactiveSecurityContextHolder JWT principal")
    void testUserKeyResolverFromSecurityContext() {
        Jwt jwt = new Jwt(
                "token-abc",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "RS256"),
                Map.of("sub", "usr-jwt-789")
        );
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/projects").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(jwt, "n/a", java.util.Collections.emptyList());

        StepVerifier.create(
                rateLimiterConfig.userKeyResolver().resolve(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
        )
                .expectNext("user:usr-jwt-789")
                .verifyComplete();
    }

    @Test
    @DisplayName("userKeyResolver falls back to remote IP when no user context present")
    void testUserKeyResolverFallbackToIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/public")
                .remoteAddress(new InetSocketAddress("192.168.1.100", 54321))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(rateLimiterConfig.userKeyResolver().resolve(exchange))
                .expectNext("ip:192.168.1.100")
                .verifyComplete();
    }

    @Test
    @DisplayName("webhookKeyResolver ignores spoofable X-GitHub-Hook-ID header and keys by client IP")
    void testWebhookKeyResolverFromHookId() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/webhooks/github")
                .header("X-GitHub-Hook-ID", "12345678")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(rateLimiterConfig.webhookKeyResolver().resolve(exchange))
                .expectNext("webhook:ip:github-ingress")
                .verifyComplete();
    }

    @Test
    @DisplayName("webhookKeyResolver keys by client IP when X-GitHub-Delivery header is present")
    void testWebhookKeyResolverFromDelivery() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/webhooks/github")
                .header("X-GitHub-Delivery", "deliv-uuid-999")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 12345))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(rateLimiterConfig.webhookKeyResolver().resolve(exchange))
                .expectNext("webhook:ip:10.0.0.5")
                .verifyComplete();
    }
}
