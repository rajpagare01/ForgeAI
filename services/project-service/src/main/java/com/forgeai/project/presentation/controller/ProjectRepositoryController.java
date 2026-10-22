package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.service.TraceabilityAggregationService;
import com.forgeai.project.domain.model.ProjectRepositoryLink;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.CreateProjectRepositoryLinkRequest;
import com.forgeai.project.presentation.dto.ProjectRepositoryLinkDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/repositories")
@RequiredArgsConstructor
public class ProjectRepositoryController {

    private final TraceabilityAggregationService traceabilityAggregationService;

    @GetMapping
    public ResponseEntity<List<ProjectRepositoryLinkDto>> listRepositories(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        return ResponseEntity.ok(traceabilityAggregationService.listProjectRepositories(orgId, projectId, userId));
    }

    @PostMapping
    public ResponseEntity<ProjectRepositoryLinkDto> linkRepository(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateProjectRepositoryLinkRequest request) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        ProjectRepositoryLink link = traceabilityAggregationService.linkRepository(
                orgId, projectId, userId, request.repositoryId(), request.repositoryName()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectRepositoryLinkDto.from(link));
    }

    @DeleteMapping("/{repositoryId}")
    public ResponseEntity<Void> unlinkRepository(
            @PathVariable UUID projectId,
            @PathVariable UUID repositoryId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        traceabilityAggregationService.unlinkRepository(orgId, projectId, userId, repositoryId);
        return ResponseEntity.noContent().build();
    }
}
