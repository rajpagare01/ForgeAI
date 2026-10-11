package com.forgeai.sourcecontrol.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String HEADER_ORG_ID = "X-Org-Id";
    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_PERMISSIONS = "X-Permissions";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String orgIdHeader = request.getHeader(HEADER_ORG_ID);
            if (orgIdHeader != null && !orgIdHeader.isBlank()) {
                try {
                    TenantContext.setOrganizationId(UUID.fromString(orgIdHeader.trim()));
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid UUID format in {}: {}", HEADER_ORG_ID, orgIdHeader);
                }
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

            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
