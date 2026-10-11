package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.domain.model.PermissionCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
@Aspect
@Component
public class PermissionAspect {

    @Around("@annotation(requirePermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequirePermission requirePermission) throws Throwable {
        PermissionCode requiredCode = requirePermission.value();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Permission check failed: Unauthenticated request for permission {}", requiredCode);
            throw new AccessDeniedException("Access denied: Unauthenticated request");
        }

        boolean hasPermission = hasRequiredPermission(authentication, requiredCode);
        if (!hasPermission) {
            log.warn("Access Denied: Principal '{}' lacks required permission '{}'",
                    authentication.getName(), requiredCode.getValue());
            throw new AccessDeniedException("Access denied: Missing required permission " + requiredCode.getValue());
        }

        return joinPoint.proceed();
    }

    public boolean hasRequiredPermission(Authentication authentication, PermissionCode requiredCode) {
        String expectedPermission = requiredCode.getValue();

        // 1. Check Spring Security GrantedAuthorities
        Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
        if (authorities != null) {
            for (GrantedAuthority authority : authorities) {
                if (authority.getAuthority().equalsIgnoreCase(expectedPermission)) {
                    return true;
                }
                // Also check if OWNER / ADMIN authority implies full access
                if (authority.getAuthority().equalsIgnoreCase("ROLE_OWNER")) {
                    return true;
                }
            }
        }

        // 2. Check JWT claims if principal / credentials contains Jwt
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            if (checkJwtPermissions(jwt, expectedPermission)) {
                return true;
            }
        } else if (authentication.getCredentials() instanceof Jwt jwt) {
            if (checkJwtPermissions(jwt, expectedPermission)) {
                return true;
            }
        }

        return false;
    }

    private boolean checkJwtPermissions(Jwt jwt, String expectedPermission) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions != null && permissions.stream().anyMatch(p -> p.equalsIgnoreCase(expectedPermission))) {
            return true;
        }
        String role = jwt.getClaimAsString("role");
        return "OWNER".equalsIgnoreCase(role);
    }
}
