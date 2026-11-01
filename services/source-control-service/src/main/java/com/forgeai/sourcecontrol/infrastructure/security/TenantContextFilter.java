package com.forgeai.sourcecontrol.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Slf4j
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String HEADER_INTERNAL_ATTESTATION = "X-ForgeAI-Internal";
    public static final String ATTESTED_MARKER = "attested";

    @org.springframework.beans.factory.annotation.Value("${forgeai.security.enforce-internal-marker:true}")
    private boolean enforceInternalMarker;

    public static final String HEADER_ORG_ID = "X-Org-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_PERMISSIONS = "X-Permissions";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String attestation = request.getHeader(HEADER_INTERNAL_ATTESTATION);
            boolean isAttested = ATTESTED_MARKER.equalsIgnoreCase(attestation);

            HttpServletRequest effectiveRequest = request;

            if (!enforceInternalMarker || isAttested) {
                String orgIdHeader = request.getHeader(HEADER_ORG_ID);
                if (orgIdHeader == null || orgIdHeader.isBlank()) {
                    orgIdHeader = request.getHeader("X-Organization-Id");
                }
                if (orgIdHeader != null && !orgIdHeader.isBlank()) {
                    try {
                        TenantContext.setOrganizationId(UUID.fromString(orgIdHeader.trim()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid UUID format in org header: {}", orgIdHeader);
                    }
                }

                String wsIdHeader = request.getHeader("X-Workspace-Id");
                if (wsIdHeader != null && !wsIdHeader.isBlank()) {
                    try {
                        TenantContext.setWorkspaceId(UUID.fromString(wsIdHeader.trim()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid UUID format in X-Workspace-Id: {}", wsIdHeader);
                    }
                }

                String wsScopeHeader = request.getHeader("X-Workspace-Scope");
                if (wsScopeHeader != null && !wsScopeHeader.isBlank()) {
                    TenantContext.setWorkspaceScope(wsScopeHeader.trim().toUpperCase());
                } else if (TenantContext.getOrganizationId() != null) {
                    TenantContext.setWorkspaceScope("ORGANIZATION");
                } else if (TenantContext.getWorkspaceId() != null) {
                    TenantContext.setWorkspaceScope("PERSONAL");
                }

                String userIdHeader = request.getHeader(HEADER_USER_ID);
                if (userIdHeader != null && !userIdHeader.isBlank()) {
                    try {
                        TenantContext.setUserId(UUID.fromString(userIdHeader.trim()));
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid UUID format in {}: {}", HEADER_USER_ID, userIdHeader);
                    }
                }

                String permissionsHeader = request.getHeader(HEADER_PERMISSIONS);
                if (permissionsHeader != null && !permissionsHeader.isBlank()) {
                    List<String> permissions = Arrays.stream(permissionsHeader.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
                    TenantContext.setPermissions(permissions);
                } else {
                    TenantContext.setPermissions(Collections.emptyList());
                }
            } else {
                // Untrusted caller without internal gateway attestation: strip identity headers from downstream inspection
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
                               "X-Workspace-Scope".equalsIgnoreCase(name) ||
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
}
