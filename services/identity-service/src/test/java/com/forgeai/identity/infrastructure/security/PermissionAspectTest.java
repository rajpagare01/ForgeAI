package com.forgeai.identity.infrastructure.security;

import com.forgeai.identity.domain.model.PermissionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionAspectTest {

    private PermissionAspect permissionAspect;

    @BeforeEach
    void setUp() {
        permissionAspect = new PermissionAspect();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Permission Check: Passes when user has the specific GrantedAuthority")
    void shouldPassWhenGrantedAuthorityPresent() {
        Authentication auth = new TestingAuthenticationToken(
                "user-1",
                "creds",
                List.of(new SimpleGrantedAuthority("PROJECT_READ"))
        );
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(PermissionCode.PROJECT_READ);

        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);

        assertDoesNotThrow(() -> permissionAspect.checkPermission(pjp, annotation));
    }

    @Test
    @DisplayName("Permission Check: Fails with AccessDeniedException when permission is missing")
    void shouldThrowWhenPermissionMissing() {
        Authentication auth = new TestingAuthenticationToken(
                "user-1",
                "creds",
                List.of(new SimpleGrantedAuthority("PROJECT_READ"))
        );
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(PermissionCode.PROJECT_DELETE);

        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);

        assertThrows(AccessDeniedException.class, () -> permissionAspect.checkPermission(pjp, annotation));
    }

    @Test
    @DisplayName("Permission Check: Role OWNER automatically bypasses permission checks")
    void shouldAllowOwnerRoleAutomatically() {
        Authentication auth = new TestingAuthenticationToken(
                "user-1",
                "creds",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(PermissionCode.ORGANIZATION_DELETE);

        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);

        assertDoesNotThrow(() -> permissionAspect.checkPermission(pjp, annotation));
    }

    @Test
    @DisplayName("Permission Check: Unauthenticated request throws AccessDeniedException")
    void shouldThrowWhenUnauthenticated() {
        RequirePermission annotation = mock(RequirePermission.class);
        when(annotation.value()).thenReturn(PermissionCode.PROJECT_READ);

        org.aspectj.lang.ProceedingJoinPoint pjp = mock(org.aspectj.lang.ProceedingJoinPoint.class);

        assertThrows(AccessDeniedException.class, () -> permissionAspect.checkPermission(pjp, annotation));
    }

    @Test
    @DisplayName("Permission Check: Evaluates JWT permissions claim correctly")
    void shouldPassWhenJwtPermissionsClaimContainsCode() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                Map.of("permissions", List.of("MEMBER_INVITE", "PROJECT_READ"), "sub", "user-uuid")
        );

        Authentication auth = new TestingAuthenticationToken(jwt, "creds");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        boolean hasInvite = permissionAspect.hasRequiredPermission(auth, PermissionCode.MEMBER_INVITE);
        boolean hasDelete = permissionAspect.hasRequiredPermission(auth, PermissionCode.PROJECT_DELETE);

        assertThat(hasInvite).isTrue();
        assertThat(hasDelete).isFalse();
    }
}
