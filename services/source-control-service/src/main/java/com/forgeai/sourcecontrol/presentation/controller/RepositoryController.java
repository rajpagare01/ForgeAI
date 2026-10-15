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

    @GetMapping
    public ResponseEntity<List<Repository>> listRepositories() {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listRepositories(organizationId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Repository> getRepository(@PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getRepository(organizationId, id));
    }

    @GetMapping("/{id}/sync-status")
    public ResponseEntity<com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO> getRepositorySyncStatus(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getSyncStatus(organizationId, id));
    }

    @PostMapping("/{id}/reconcile")
    public ResponseEntity<com.forgeai.sourcecontrol.domain.model.RepositorySyncStatusDTO> reconcileRepository(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.reconcileRepository(organizationId, id));
    }

    @GetMapping("/{id}/pull-requests")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.PullRequest>> listPullRequestsForRepo(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listPullRequests(organizationId, id));
    }

    @PostMapping("/{id}/sync/pull-requests")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.PullRequest>> syncPullRequests(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.syncPullRequests(organizationId, id));
    }

    @GetMapping("/{id}/commits")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.Commit>> listCommits(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listCommits(organizationId, id));
    }

    @PostMapping("/{id}/sync/commits")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.Commit>> syncCommits(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.syncCommits(organizationId, id));
    }

    @GetMapping("/{id}/branches")
    public ResponseEntity<List<com.forgeai.sourcecontrol.infrastructure.github.GitHubBranchMetadata>> listBranches(
            @PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listBranches(organizationId, id));
    }

    /**
     * Lists all GitHub repositories available to this organization's GitHub App installation.
     * All data is fetched live from GitHub — nothing is cached or fabricated.
     */
    @GetMapping("/available")
    public ResponseEntity<List<GitHubRepositoryMetadata>> listAvailableRepositories() {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listAvailableRepositories(organizationId));
    }

    /**
     * Connects a GitHub repository to this ForgeAI organization.
     *
     * <p>Only {@code fullName} ("owner/repo") is accepted from the caller.
     * All other metadata (GitHub repo ID, default branch, visibility) is fetched
     * authoritatively from GitHub and cannot be overridden by the client.
     */
    public record ConnectRepositoryRequest(String fullName) {}

    @PostMapping("/connect")
    public ResponseEntity<Repository> connectRepository(@RequestBody ConnectRepositoryRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        if (request == null || request.fullName() == null || request.fullName().isBlank()) {
            throw new IllegalArgumentException("fullName is required (format: owner/repo)");
        }
        Repository repo = repositoryApplicationService.connectRepository(organizationId, request.fullName());
        return ResponseEntity.ok(repo);
    }
}
