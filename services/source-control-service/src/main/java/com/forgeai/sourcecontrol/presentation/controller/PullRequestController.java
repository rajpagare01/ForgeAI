package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.RepositoryApplicationService;
import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.infrastructure.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/pull-requests")
@RequiredArgsConstructor
public class PullRequestController {

    private final RepositoryApplicationService repositoryApplicationService;

    @GetMapping
    public ResponseEntity<List<PullRequest>> listPullRequests(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.listPullRequests(organizationId, workspaceId, repositoryId));
    }

    @GetMapping("/{number}")
    public ResponseEntity<PullRequest> getPullRequest(
            @PathVariable int number,
            @RequestParam UUID repositoryId,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getPullRequest(organizationId, workspaceId, repositoryId, number));
    }

    @GetMapping("/{number}/changes")
    public ResponseEntity<com.forgeai.sourcecontrol.domain.model.PullRequestChangeSet> getPullRequestChanges(
            @PathVariable int number,
            @RequestParam UUID repositoryId,
            @RequestParam(required = false) UUID workspaceId) {
        UUID organizationId = TenantContext.getOrganizationId();
        return ResponseEntity.ok(repositoryApplicationService.getPullRequestChanges(organizationId, workspaceId, repositoryId, number));
    }
}
