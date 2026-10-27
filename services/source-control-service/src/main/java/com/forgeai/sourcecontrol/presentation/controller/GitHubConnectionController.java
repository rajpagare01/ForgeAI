 b package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.dto.GitHubConnectionResponse;
import com.forgeai.sourcecontrol.application.service.GitHubConnectionService;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Unified orchestration REST controller for single-click "Connect GitHub" flow.
 * Provides consolidated connection state, single-action connect/setup handling,
 * and live repository discovery without exposing manual numeric installation IDs.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/github")
@RequiredArgsConstructor
public class GitHubConnectionController {

    private final GitHubConnectionService connectionService;

    public record ConnectRequest(UUID workspaceId, Long installationId) {}
    public record ReconcileRequest(UUID workspaceId) {}

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
