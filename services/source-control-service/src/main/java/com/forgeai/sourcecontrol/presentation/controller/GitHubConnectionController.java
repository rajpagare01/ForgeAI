package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.dto.GitHubConnectionResponse;
import com.forgeai.sourcecontrol.application.service.GitHubConnectionService;
import com.forgeai.sourcecontrol.application.service.GitHubInstallationDiscoveryService;
import com.forgeai.sourcecontrol.application.service.GitHubInstallationStateService;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubAppConfig;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationStateJpaEntity;
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
 * Unified orchestration REST controller for single-click "Connect GitHub" flow.
 * Provides consolidated connection state, single-action connect/setup handling,
 * secure server-side state generation, dual callback processing, and live repository
 * discovery without exposing manual numeric installation IDs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
public class GitHubConnectionController {

    private final GitHubConnectionService connectionService;
    private final GitHubInstallationDiscoveryService discoveryService;
    private final GitHubInstallationStateService stateService;
    private final GitHubAppConfig gitHubAppConfig;

    @Value("${forgeai.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public record ConnectRequest(UUID workspaceId, Long installationId) {}
    public record ReconcileRequest(UUID workspaceId) {}
    public record InstallationStartRequest(UUID workspaceId) {}
    public record InstallationStartResponse(String state, String installUrl) {}
    public record InstallationCallbackRequest(Long installationId, String state, UUID workspaceId) {}

    /**
     * Consolidates GitHub OAuth status, GitHub App installation status,
     * and repository metrics into a single unified payload.
     */
    @GetMapping("/connection")
    public ResponseEntity<GitHubConnectionResponse> getConnection(
            @RequestParam(required = false) UUID workspaceId) {

        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        UUID effectiveWsId = workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();

        GitHubConnectionResponse response = connectionService.getConnectionStatus(userId, effectiveWsId, orgId);
        return ResponseEntity.ok(response);
    }

    /**
     * Generates a cryptographically secure, single-use server-side state token
     * bound to the caller's authenticated user and active workspace, then returns
     * the authoritative GitHub App install URL with state parameter appended.
     */
    @PostMapping("/installation-start")
    public ResponseEntity<InstallationStartResponse> startInstallation(
            @RequestBody(required = false) InstallationStartRequest request) {

        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        UUID effectiveWsId = (request != null && request.workspaceId() != null)
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();

        if (userId == null) {
            throw new IllegalStateException("Authenticated user required to initiate GitHub App installation");
        }
        if (effectiveWsId == null) {
            throw new IllegalStateException("Active workspace required to initiate GitHub App installation");
        }

        String scope = (orgId == null) ? "PERSONAL" : "ORGANIZATION";
        String stateToken = stateService.createInstallationState(userId, effectiveWsId, orgId, scope);

        String baseInstallUrl = gitHubAppConfig.getAppInstallUrl();
        String installUrl = baseInstallUrl + (baseInstallUrl.contains("?") ? "&" : "?") + "state=" + stateToken;

        log.info("[GitHub] Initiated installation flow for user={}, workspace={}, state={}",
                userId, effectiveWsId, stateToken);

        return ResponseEntity.ok(new InstallationStartResponse(stateToken, installUrl));
    }

    /**
     * Dual Callback (Mode A - Direct Browser Redirect from GitHub):
     * Handles GitHub App setup URL redirect (?installation_id=...&state=...).
     * Validates and single-use consumes server-side state, strictly validates ownership against GitHub,
     * associates the installation with the initiating user's workspace, and performs HTTP 302 redirect
     * back to the ForgeAI frontend repositories page.
     */
    @GetMapping("/installation-callback")
    public ResponseEntity<Void> handleInstallationCallbackGet(
            @RequestParam(name = "installation_id", required = false) Long installationId,
            @RequestParam(name = "setup_action", required = false) String setupAction,
            @RequestParam(name = "state", required = false) String state) {

        log.info("[GitHub Callback GET] Received setup redirect: installation_id={}, setup_action={}, statePresent={}",
                installationId, setupAction, (state != null && !state.isBlank()));

        if (installationId == null || state == null || state.isBlank()) {
            log.warn("[GitHub Callback GET] Missing installation_id or state parameter in callback redirect");
            String target = frontendUrl + "/repositories?error=" + URLEncoder.encode("Missing required callback parameters from GitHub", StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
        }

        try {
            // Validate and atomically consume single-use state token
            GitHubInstallationStateJpaEntity stateEntity = stateService.validateAndConsumeState(state, null, null);

            // Authoritatively validate ownership with GitHub API and bind to initiating user and workspace
            GitHubInstallation claimed = discoveryService.validateAndAssociate(
                    stateEntity.getUserId(),
                    stateEntity.getWorkspaceId(),
                    stateEntity.getOrganizationId(),
                    installationId
            );

            log.info("[GitHub Callback GET] Successfully onboarded installation {} for user={}, workspace={}",
                    claimed.getInstallationId(), stateEntity.getUserId(), stateEntity.getWorkspaceId());

            String target = frontendUrl + "/repositories?installed=true&installation_id=" + installationId;
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();

        } catch (Exception ex) {
            log.error("[GitHub Callback GET] Installation verification failed: {}", ex.getMessage());
            String errorMsg = URLEncoder.encode(ex.getMessage() != null ? ex.getMessage() : "Installation verification failed", StandardCharsets.UTF_8);
            String target = frontendUrl + "/repositories?error=" + errorMsg;
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
        }
    }

    /**
     * Dual Callback (Mode B - Frontend Post-Back Forwarding):
     * Accepts callback payload from frontend or API client, consumes single-use state,
     * validates ownership, associates the installation, and returns HTTP 200 with updated connection status.
     */
    @PostMapping("/installation-callback")
    public ResponseEntity<GitHubConnectionResponse> handleInstallationCallbackPost(
            @RequestBody InstallationCallbackRequest request) {

        if (request == null || request.installationId() == null || request.installationId() <= 0) {
            throw new IllegalArgumentException("A valid installationId is required.");
        }
        if (request.state() == null || request.state().isBlank()) {
            throw new IllegalArgumentException("A valid state token is required.");
        }

        UUID callerUserId = TenantContext.getUserId();
        UUID targetWsId = request.workspaceId() != null ? request.workspaceId() : TenantContext.getWorkspaceId();

        // Validate and atomically consume state (enforcing caller and workspace matching if present)
        GitHubInstallationStateJpaEntity stateEntity = stateService.validateAndConsumeState(
                request.state(), callerUserId, targetWsId);

        UUID effectiveUserId = callerUserId != null ? callerUserId : stateEntity.getUserId();
        UUID effectiveWsId = stateEntity.getWorkspaceId() != null ? stateEntity.getWorkspaceId() : targetWsId;
        UUID orgId = stateEntity.getOrganizationId();

        discoveryService.validateAndAssociate(effectiveUserId, effectiveWsId, orgId, request.installationId());

        GitHubConnectionResponse response = connectionService.getConnectionStatus(effectiveUserId, effectiveWsId, orgId);
        return ResponseEntity.ok(response);
    }

    /**
     * Unified connect endpoint: automatically discovers and associates the user's
     * GitHub App installation, validates ownership, and triggers initial repository sync.
     */
    @PostMapping("/connect")
    public ResponseEntity<GitHubConnectionResponse> connect(
            @RequestBody(required = false) ConnectRequest request) {

        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        UUID effectiveWsId = (request != null && request.workspaceId() != null)
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();
        Long installationIdHint = request != null ? request.installationId() : null;

        GitHubConnectionResponse response = connectionService.connectGitHub(userId, effectiveWsId, orgId, installationIdHint);
        return ResponseEntity.ok(response);
    }

    /**
     * Reconciles repository access and synchronizes latest metadata from GitHub.
     */
    @PostMapping("/reconcile")
    public ResponseEntity<GitHubConnectionResponse> reconcile(
            @RequestBody(required = false) ReconcileRequest request) {

        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        UUID effectiveWsId = (request != null && request.workspaceId() != null)
                ? request.workspaceId()
                : TenantContext.getWorkspaceId();

        GitHubConnectionResponse response = connectionService.reconcileGitHub(userId, effectiveWsId, orgId);
        return ResponseEntity.ok(response);
    }
}
