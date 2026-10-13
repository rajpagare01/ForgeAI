package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

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

    @GetMapping("/{id}/pull-requests")
    public ResponseEntity<List<com.forgeai.sourcecontrol.domain.model.PullRequest>> listPullRequestsForRepo(@PathVariable UUID id) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listPullRequests(organizationId, id));
    }

    public record ConnectRepositoryRequest(
            Long githubRepoId,
            Long installationId,
            String name,
            String fullName,
            String defaultBranch,
            Boolean isPrivate
    ) {}

    @PostMapping("/connect")
    public ResponseEntity<Repository> connectRepository(@RequestBody ConnectRepositoryRequest request) {
        UUID organizationId = TenantContext.getOrganizationId();
        Repository repo = repositoryApplicationService.connectRepository(
                organizationId,
                request.githubRepoId(),
                request.installationId() != null ? request.installationId() : 1001L,
                request.name(),
                request.fullName(),
                request.defaultBranch(),
                request.isPrivate()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(repo);
    }
}
