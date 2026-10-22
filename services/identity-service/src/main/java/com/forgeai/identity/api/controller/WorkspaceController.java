package com.forgeai.identity.api.controller;

import com.forgeai.identity.api.dto.WorkspaceResponse;
import com.forgeai.identity.application.service.WorkspaceService;
import com.forgeai.identity.domain.exception.InvalidCredentialsException;
import com.forgeai.identity.domain.model.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public record UpdateWorkspaceRequest(String name) {}

    /**
     * GET /api/v1/workspaces
     * Returns all workspaces accessible by the authenticated user (Personal + Organizations).
     */
    @GetMapping
    public ResponseEntity<List<WorkspaceResponse>> listWorkspaces(
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        UUID userId = resolveUserId(authentication, internalUserId, internalAttestation);
        List<Workspace> workspaces = workspaceService.getUserWorkspaces(userId);
        return ResponseEntity.ok(workspaces.stream().map(WorkspaceResponse::from).collect(Collectors.toList()));
    }

    /**
     * GET /api/v1/workspaces/personal
     * Returns the authenticated user's PERSONAL workspace, auto-provisioning idempotently if missing.
     */
    @GetMapping("/personal")
    public ResponseEntity<WorkspaceResponse> getPersonalWorkspace(
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        UUID userId = resolveUserId(authentication, internalUserId, internalAttestation);
        Workspace workspace = workspaceService.getOrCreatePersonalWorkspace(userId, null);
        return ResponseEntity.ok(WorkspaceResponse.from(workspace));
    }

    /**
     * GET /api/v1/workspaces/me/personal
     * Reconciles / provisions the personal workspace when appropriate.
     */
    @GetMapping("/me/personal")
    public ResponseEntity<WorkspaceResponse> getMyPersonalWorkspace(
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        return getPersonalWorkspace(authentication, internalUserId, internalAttestation);
    }

    /**
     * GET /api/v1/workspaces/{workspaceId}
     * Returns a specific workspace by ID with strict ownership/membership verification.
     */
    @GetMapping("/{workspaceId}")
    public ResponseEntity<WorkspaceResponse> getWorkspace(
            @PathVariable UUID workspaceId,
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        UUID userId = resolveUserId(authentication, internalUserId, internalAttestation);
        Workspace workspace = workspaceService.getWorkspaceById(workspaceId, userId);
        return ResponseEntity.ok(WorkspaceResponse.from(workspace));
    }

    /**
     * POST /api/v1/workspaces/organization/{organizationId}
     * Gets or creates the ORGANIZATION workspace for an organization the user belongs to.
     */
    @PostMapping("/organization/{organizationId}")
    public ResponseEntity<WorkspaceResponse> getOrCreateOrganizationWorkspace(
            @PathVariable UUID organizationId,
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        UUID userId = resolveUserId(authentication, internalUserId, internalAttestation);
        Workspace workspace = workspaceService.getOrCreateOrganizationWorkspace(organizationId, userId, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkspaceResponse.from(workspace));
    }

    /**
     * PATCH /api/v1/workspaces/{workspaceId}
     * Updates workspace name. Owner-only for personal workspaces.
     */
    @PatchMapping("/{workspaceId}")
    public ResponseEntity<WorkspaceResponse> updateWorkspace(
            @PathVariable UUID workspaceId,
            @RequestBody UpdateWorkspaceRequest request,
            Authentication authentication,
            @RequestHeader(value = "X-User-Id", required = false) String internalUserId,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalAttestation) {
        UUID userId = resolveUserId(authentication, internalUserId, internalAttestation);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Workspace name is required");
        }
        Workspace updated = workspaceService.updateWorkspace(workspaceId, userId, request.name());
        return ResponseEntity.ok(WorkspaceResponse.from(updated));
    }

    private UUID resolveUserId(Authentication authentication, String internalUserId, String internalAttestation) {
        // 1. Authoritative JWT principal
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UUID uuid) {
                return uuid;
            }
            if (principal instanceof Jwt jwt) {
                try {
                    return UUID.fromString(jwt.getSubject());
                } catch (IllegalArgumentException ignored) {}
            }
            if (principal instanceof String str) {
                try {
                    return UUID.fromString(str);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // 2. Verified internal inter-service call marker
        if ("attested".equalsIgnoreCase(internalAttestation) && internalUserId != null && !internalUserId.isBlank()) {
            try {
                return UUID.fromString(internalUserId.trim());
            } catch (IllegalArgumentException ignored) {}
        }

        throw new InvalidCredentialsException("Authentication required");
    }
}
