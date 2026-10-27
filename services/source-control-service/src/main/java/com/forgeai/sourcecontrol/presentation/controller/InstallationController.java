package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.dto.InstallationDiscoveryResponse;
import com.forgeai.sourcecontrol.application.dto.ReconciliationReport;
import com.forgeai.sourcecontrol.application.service.GitHubInstallationDiscoveryService;
import com.forgeai.sourcecontrol.application.service.GitHubInstallationStateService;
import com.forgeai.sourcecontrol.application.service.InstallationReconciliationService;
import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * REST controller for GitHub App installation lifecycle.
 *
 * <p>Enables automatic installation discovery, setup URL onboarding with cryptographic
 * anti-spoofing validation, idempotent reconciliation, and multi-tenant workspace association.
 * Users never need to manually enter a GitHub installation ID.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/installations")
@RequiredArgsConstructor
public class InstallationController {

    private final RepositoryApplicationService repositoryApplicationService;
    private final GitHubInstallationDiscoveryService discoveryService;
    private final InstallationReconciliationService reconciliationService;
    private final GitHubInstallationStateService stateService;

    @Value("${forgeai.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * Automatically discovers available GitHub App installations for the current authenticated user and workspace.
     */
    @GetMapping("/discover")
    public ResponseEntity<InstallationDiscoveryResponse> discoverInstallations(
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID effectiveWsId = workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();
        return ResponseEntity.ok(discoveryService.discoverInstallations(
                TenantContext.getUserId(), effectiveWsId, organizationId));
    }

    /**
     * Automatically onboards a discovered installation for this workspace without requiring manual ID entry.
     */
    public record AutoOnboardRequest(Long installationId, UUID workspaceId) {}

    @PostMapping("/auto-onboard")
    public ResponseEntity<GitHubInstallation> autoOnboard(
            @RequestBody(required = false) AutoOnboardRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID effectiveWsId = (request != null && request.workspaceId() != null)
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();
        Long hintInstallationId = request != null ? request.installationId() : null;

        GitHubInstallation onboarded = discoveryService.autoOnboard(
                TenantContext.getUserId(), effectiveWsId, organizationId, hintInstallationId);
        return ResponseEntity.ok(onboarded);
    }

    /**
     * Handles the GitHub App setup redirect URL (?installation_id=...).
     * Strictly verifies ownership against GitHub and caller identity to prevent installation ID spoofing.
     */
    public record SetupCallbackRequest(Long installationId, UUID workspaceId) {}

    @PostMapping("/setup-callback")
    public ResponseEntity<GitHubInstallation> handleSetupCallback(
            @RequestBody SetupCallbackRequest request) {
        if (request == null || request.installationId() == null || request.installationId() <= 0) {
            throw new IllegalArgumentException("A valid installationId is required.");
        }
        UUID organizationId = TenantContext.getOrganizationId();
        UUID effectiveWsId = request.workspaceId() != null
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();

        GitHubInstallation claimed = discoveryService.handleSetupCallback(
                TenantContext.getUserId(), effectiveWsId, organizationId, request.installationId());
        return ResponseEntity.ok(claimed);
    }

    /**
     * Browser redirect handler for GitHub App setup callback.
     * Consumes cryptographic state if present and 302 redirects to frontend repositories page.
     */
    @GetMapping("/setup-callback")
    public ResponseEntity<Void> handleSetupCallbackGet(
            @RequestParam(name = "installation_id", required = false) Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(name = "state", required = false) String state) {

        log.info("[Setup Callback GET] installation_id={}, setup_action={}, statePresent={}",
                installationId, setupAction, (state != null && !state.isBlank()));

        if (installationId == null) {
            String target = frontendUrl + "/repositories?error=" + URLEncoder.encode("Missing installation_id", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
        }

        if (state != null && !state.isBlank()) {
            try {
                var stateEntity = stateService.validateAndConsumeState(state, null, null);
                discoveryService.validateAndAssociate(
                        stateEntity.getUserId(),
                        stateEntity.getWorkspaceId(),
                        stateEntity.getOrganizationId(),
                        installationId
                );
                String target = frontendUrl + "/repositories?installed=true&installation_id=" + installationId;
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
            } catch (Exception e) {
                log.error("[Setup Callback GET] State verification failed: {}", e.getMessage());
                String target = frontendUrl + "/repositories?error=" + URLEncoder.encode(e.getMessage() != null ? e.getMessage() : "Verification failed", StandardCharsets.UTF_8);
                return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
            }
        }

        String target = frontendUrl + "/repositories?installation_id=" + installationId + (setupAction != null ? "&setup_action=" + setupAction : "");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    /**
     * Reconciles repository access and synchronization status for the given installation.
     */
    @PostMapping("/{id}/reconcile")
    public ResponseEntity<ReconciliationReport> reconcileInstallation(
            @PathVariable Long id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        UUID effectiveWsId = workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();

        ReconciliationReport report = reconciliationService.reconcileInstallation(
                id, effectiveWsId, organizationId, TenantContext.getUserId());
        return ResponseEntity.ok(report);
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

    /**
     * Claims a quarantined GitHub App installation for the authenticated organization or workspace.
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
}
