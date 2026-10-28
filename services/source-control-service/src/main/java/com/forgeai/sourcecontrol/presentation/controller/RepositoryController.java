package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubRepositoryMetadata;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for repository management.
 *
 * <p>All repository connections require a real GitHub App installation. No caller-supplied
 * GitHub IDs or repository metadata are accepted — everything is fetched from GitHub.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryApplicationService repositoryApplicationService;

    private UUID resolveWorkspaceId(UUID workspaceId) {
        return workspaceId != null ? workspaceId : TenantContext.getWorkspaceId();
    }

    @GetMapping
    public ResponseEntity<List<Repository>> listRepositories(
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listRepositories(
                organizationId, resolveWorkspaceId(workspaceId), TenantContext.getUserId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Repository> getRepository(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getRepository(
                organizationId, resolveWorkspaceId(workspaceId), id, TenantContext.getUserId()));
    }

    @GetMapping("/{id}/sync-status")
    public ResponseEntity<com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO> getRepositorySyncStatus(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getSyncStatus(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    @PostMapping("/{id}/reconcile")
    public ResponseEntity<com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO> reconcileRepository(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.reconcileRepository(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    @GetMapping("/{id}/pull-requests")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.PullRequest>> listPullRequestsForRepo(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listPullRequests(
                organizationId, resolveWorkspaceId(workspaceId), id, TenantContext.getUserId()));
    }

    @PostMapping("/{id}/sync/pull-requests")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.PullRequest>> syncPullRequests(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.syncPullRequests(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    @GetMapping("/{id}/commits")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.Commit>> listCommits(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listCommits(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    @PostMapping("/{id}/sync/commits")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.Commit>> syncCommits(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.syncCommits(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    @GetMapping("/{id}/branches")
    public ResponseEntity<List<com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata>> listBranches(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listBranches(
                organizationId, resolveWorkspaceId(workspaceId), id));
    }

    /**
     * Lists all GitHub repositories available to this organization or workspace's GitHub App installation.
     * All data is fetched live from GitHub — nothing is cached or fabricated.
     */
    @GetMapping("/available")
    public ResponseEntity<List<GitHubRepositoryMetadata>> listAvailableRepositories(
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listAvailableRepositories(
                organizationId, resolveWorkspaceId(workspaceId), TenantContext.getUserId()));
    }

    /**
     * Connects a GitHub repository to this ForgeAI organization or workspace.
     */
    public record ConnectRepositoryRequest(String fullName, UUID workspaceId) {
        public ConnectRepositoryRequest(String fullName) {
            this(fullName, null);
        }
    }

    @PostMapping("/connect")
    public ResponseEntity<Repository> connectRepository(@RequestBody ConnectRepositoryRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        if (request == null || request.fullName() == null || request.fullName().isBlank()) {
            throw new IllegalArgumentException("fullName is required (format: owner/repo)");
        }
        UUID effectiveWsId = request.workspaceId() != null ? request.workspaceId() : TenantContext.getWorkspaceId();
        Repository repo = repositoryApplicationService.connectRepository(
                organizationId, effectiveWsId, request.fullName(), TenantContext.getUserId());
        return ResponseEntity.ok(repo);
    }

    /**
     * Connects multiple GitHub repositories in a single batch operation.
     */
    public record BatchConnectRequest(List<String> repositoryNames, UUID workspaceId) {}

    @PostMapping("/batch-connect")
    public ResponseEntity<List<Repository>> batchConnectRepositories(@RequestBody BatchConnectRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        if (request == null || request.repositoryNames() == null || request.repositoryNames().isEmpty()) {
            throw new IllegalArgumentException("repositoryNames list is required");
        }
        UUID effectiveWsId = request.workspaceId() != null ? request.workspaceId() : TenantContext.getWorkspaceId();
        List<Repository> results = new java.util.ArrayList<>();
        for (String fullName : request.repositoryNames()) {
            if (fullName != null && !fullName.isBlank()) {
                results.add(repositoryApplicationService.connectRepository(
                        organizationId, effectiveWsId, fullName.trim(), TenantContext.getUserId()));
            }
        }
        return ResponseEntity.ok(results);
    }

    @GetMapping("/{id}/contents/**")
    public ResponseEntity<String> getFileContent(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID workspaceId,
            @RequestParam(required = false) String ref,
            jakarta.servlet.http.HttpServletRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        String fullPath = (String) request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchingPattern = (String) request.getAttribute(
                org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        org.springframework.util.AntPathMatcher apm = new org.springframework.util.AntPathMatcher();
        String path = apm.extractPathWithinPattern(bestMatchingPattern, fullPath);
        return ResponseEntity.ok(repositoryApplicationService.getFileContent(
                organizationId, resolveWorkspaceId(workspaceId), id, path, ref));
    }
}

