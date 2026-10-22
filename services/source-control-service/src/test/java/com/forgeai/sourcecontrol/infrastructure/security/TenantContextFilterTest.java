package com.forgeai.sourcecontrol.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextFilterTest {

    private TenantContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TenantContextFilter();
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "enforceInternalMarker", true);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("TenantContextFilter: unattested caller with spoofed headers has identity headers neutralized")
    void unattestedCallerIdentityHeadersIgnored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID spoofedOrg = UUID.randomUUID();
        UUID spoofedUser = UUID.randomUUID();
        request.addHeader("X-Org-Id", spoofedOrg.toString());
        request.addHeader("X-User-Id", spoofedUser.toString());
        request.addHeader("X-User-Roles", "ROLE_ADMIN");
        request.addHeader("X-Permissions", "outbox:replay");
        // No X-ForgeAI-Internal header!

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> observedOrgId = new AtomicReference<>();
        AtomicReference<String> observedHeader = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            observedOrgId.set(TenantContext.getOrganizationId());
            observedHeader.set(((HttpServletRequest) req).getHeader("X-Org-Id"));
        };

        filter.doFilter(request, response, chain);

        assertThat(observedOrgId.get()).isNull();
        assertThat(observedHeader.get()).isNull();
        assertThat(TenantContext.getOrganizationId()).isNull();
    }

    @Test
    @DisplayName("TenantContextFilter: gateway-attested caller with X-ForgeAI-Internal populates TenantContext")
    void attestedCallerEstablishesTenantContext() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID validOrg = UUID.randomUUID();
        UUID validUser = UUID.randomUUID();
        request.addHeader("X-ForgeAI-Internal", "attested");
        request.addHeader("X-Org-Id", validOrg.toString());
        request.addHeader("X-User-Id", validUser.toString());
        request.addHeader("X-Permissions", "project:read,project:write");

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> observedOrgId = new AtomicReference<>();
        AtomicReference<UUID> observedUserId = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            observedOrgId.set(TenantContext.getOrganizationId());
            observedUserId.set(TenantContext.getUserId());
        };

        filter.doFilter(request, response, chain);

        assertThat(observedOrgId.get()).isEqualTo(validOrg);
        assertThat(observedUserId.get()).isEqualTo(validUser);
        assertThat(TenantContext.getOrganizationId()).isNull(); // Cleared in finally
    }

    @Test
    @DisplayName("TenantContextFilter: when enforcement disabled, legacy/test flow accepts headers without attestation")
    void enforcementDisabledAcceptsHeaders() throws ServletException, IOException {
        org.springframework.test.util.ReflectionTestUtils.setField(filter, "enforceInternalMarker", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        UUID validOrg = UUID.randomUUID();
        request.addHeader("X-Org-Id", validOrg.toString());

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<UUID> observedOrgId = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            observedOrgId.set(TenantContext.getOrganizationId());
        };

        filter.doFilter(request, response, chain);

        assertThat(observedOrgId.get()).isEqualTo(validOrg);
    }
}
