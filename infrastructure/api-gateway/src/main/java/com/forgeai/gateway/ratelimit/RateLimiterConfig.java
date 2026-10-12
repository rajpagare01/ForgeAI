package com.forgeai.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimiterConfig {

    /**
     * Resolves authenticated user identity via X-User-Id, JWT sub, or remote IP address.
     * Used for rate-limiting authenticated User APIs (e.g. 300 req/min).
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userIdHeader = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                return Mono.just("user:" + userIdHeader.trim());
            }

            return ReactiveSecurityContextHolder.getContext()
                    .map(ctx -> ctx.getAuthentication().getPrincipal())
                    .flatMap(principal -> {
                        if (principal instanceof Jwt jwt) {
                            String sub = jwt.getSubject();
                            if (sub != null && !sub.isBlank()) {
                                return Mono.just("user:" + sub);
                            }
                        }
                        return Mono.empty();
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
                        String ip = (remoteAddress != null && remoteAddress.getAddress() != null)
                                ? remoteAddress.getAddress().getHostAddress()
                                : "anonymous";
                        return Mono.just("ip:" + ip);
                    }));
        };
    }

    /**
     * Dedicated partition KeyResolver for GitHub Webhook Ingress (/api/v1/webhooks/**).
     * Extracts repository ID or GitHub delivery header or remote IP to allow bursts up to 100 req/sec.
     */
    @Bean
    public KeyResolver webhookKeyResolver() {
        return exchange -> {
            String deliveryId = exchange.getRequest().getHeaders().getFirst("X-GitHub-Delivery");
            String repoHeader = exchange.getRequest().getHeaders().getFirst("X-GitHub-Hook-ID");
            
            if (repoHeader != null && !repoHeader.isBlank()) {
                return Mono.just("webhook:repo:" + repoHeader.trim());
            }
            if (deliveryId != null && !deliveryId.isBlank()) {
                // Shared webhook ingress bucket partitioned by sender IP or webhook host
                InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
                String ip = (remoteAddress != null && remoteAddress.getAddress() != null)
                        ? remoteAddress.getAddress().getHostAddress()
                        : "github-ingress";
                return Mono.just("webhook:ingress:" + ip);
            }

            InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
            String ip = (remoteAddress != null && remoteAddress.getAddress() != null)
                    ? remoteAddress.getAddress().getHostAddress()
                    : "github-ingress";
            return Mono.just("webhook:ip:" + ip);
        };
    }
}
