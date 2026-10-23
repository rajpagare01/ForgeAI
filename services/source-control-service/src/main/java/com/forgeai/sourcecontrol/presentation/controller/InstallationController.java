package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller for GitHub App installation lifecycle.
 *
 * <p>Provides the secure flow for an authenticated ForgeAI organization owner to claim
 * an unmapped GitHub installation that has been quarantined after receiving an
 * {@code installation} webhook event.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/installations")
@RequiredArgsConstructor
public class InstallationController {

    private final RepositoryApplicationService repositoryApplicationService;

    /**
     * Claims a quarantined GitHub App installation for the authenticated organization.
     *
     * <p>The installation must exist in quarantine (received via webhook but not yet
     * claimed) or must exist on GitHub (installation exists but webhook not yet received).
     *
     * <p>Request body: {@code { "installationId": 12345678 }}
     */
    public record ClaimInstallationRequest(Long installationId, UUID workspaceId) {
        public ClaimInstallationRequest(Long installationId) {
            this(installationId, null);
        }
    }

    @PostMapping("/claim")
    public ResponseEntity<GitHubInstallation> claimInstallation(
            @RequestBody ClaimInstallationRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID workspaceId = (request != null && request.workspaceId() != null)
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();
        if (request == null || request.installationId() == null || request.installationId() <= 0) {
            throw new IllegalArgumentException("A valid installationId is required.");
        }
        GitHubInstallation claimed = repositoryApplicationService.claimInstallation(
                organizationId, workspaceId, request.installationId(), TenantContext.getUserId());
        return ResponseEntity.ok(claimed);
    }

    /**
     * Returns the GitHub App installation currently claimed by this organization or workspace.
     * Returns 404 if no installation has been claimed.
     */
    @GetMapping("/current")
    public ResponseEntity<GitHubInstallation> getCurrentInstallation(
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID effectiveWsId = workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();
        return ResponseEntity.ok(repositoryApplicationService.getClaimedInstallation(
                organizationId, effectiveWsId, TenantContext.getUserId()));
    }
}

