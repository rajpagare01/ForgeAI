package com.forgeai.project.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String HEADER_INTERNAL_ATTESTATION = "X-ForgeAI-Internal";
    public static final String ATTESTED_MARKER = "attested";

    @org.springframework.beans.factory.annotation.Value("${forgeai.security.enforce-internal-marker:false}")
    private boolean enforceInternalMarker;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String attestation = request.getHeader(HEADER_INTERNAL_ATTESTATION);
        boolean isAttested = ATTESTED_MARKER.equalsIgnoreCase(attestation);

        HttpServletRequest effectiveRequest = request;

        try {
            if (!enforceInternalMarker || isAttested) {
                String orgIdHeader = request.getHeader("X-Org-Id");
                if (orgIdHeader == null || orgIdHeader.isBlank()) {
                    orgIdHeader = request.getHeader("X-Organization-Id");
                }
                String userIdHeader = request.getHeader("X-User-Id");
                String permsHeader = request.getHeader("X-Permissions");
                String wsIdHeader = request.getHeader("X-Workspace-Id");

                UUID orgId = parseUuid(orgIdHeader);
                UUID userId = parseUuid(userIdHeader);
                UUID wsId = parseUuid(wsIdHeader);
                List<String> permissions = Collections.emptyList();

                if (permsHeader != null && !permsHeader.isBlank()) {
                    permissions = Arrays.stream(permsHeader.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                }

                TenantContext.set(orgId, userId, permissions);
                if (wsId != null) {
                    TenantContext.setWorkspaceId(wsId);
                }
            } else {
                // Untrusted caller: strip identity headers
                effectiveRequest = new HttpServletRequestWrapper(request) {
                    @Override
                    public String getHeader(String name) {
                        if (isIdentityHeader(name)) return null;
                        return super.getHeader(name);
                    }

                    @Override
                    public Enumeration<String> getHeaders(String name) {
                        if (isIdentityHeader(name)) return Collections.emptyEnumeration();
                        return super.getHeaders(name);
                    }

                    private boolean isIdentityHeader(String name) {
                        return "X-User-Id".equalsIgnoreCase(name) ||
                               "X-Org-Id".equalsIgnoreCase(name) ||
                               "X-Organization-Id".equalsIgnoreCase(name) ||
                               "X-Workspace-Id".equalsIgnoreCase(name) ||
                               "X-User-Roles".equalsIgnoreCase(name) ||
                               "X-Permissions".equalsIgnoreCase(name);
                    }
                };
            }

            filterChain.doFilter(effectiveRequest, response);
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
