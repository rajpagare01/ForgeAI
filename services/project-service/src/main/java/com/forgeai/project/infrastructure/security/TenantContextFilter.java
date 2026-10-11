package com.forgeai.project.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String orgIdHeader = request.getHeader("X-Org-Id");
        String userIdHeader = request.getHeader("X-User-Id");
        String permsHeader = request.getHeader("X-Permissions");

        UUID orgId = parseUuid(orgIdHeader);
        UUID userId = parseUuid(userIdHeader);
        List<String> permissions = Collections.emptyList();

        if (permsHeader != null && !permsHeader.isBlank()) {
            permissions = Arrays.stream(permsHeader.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        try {
            TenantContext.set(orgId, userId, permissions);
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private UUID parseUuid(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse UUID header: {}", headerValue);
            return null;
        }
    }
}
