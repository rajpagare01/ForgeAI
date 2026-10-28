package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.service.TraceabilityAggregationService;
import com.forgeai.project.domain.model.ProjectHealth;
import com.forgeai.project.domain.model.TraceabilityGap;
import com.forgeai.project.domain.model.TraceabilityLink;
import com.forgeai.project.domain.model.TraceabilitySnapshot;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{projectId}/traceability")
@RequiredArgsConstructor
public class TraceabilityController {

    private final TraceabilityAggregationService traceabilityAggregationService;

    @GetMapping
    public ResponseEntity<ProjectTraceabilityResponse> getTraceability(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        return ResponseEntity.ok(traceabilityAggregationService.getProjectTraceability(orgId, projectId, userId));
    }

    @GetMapping("/health")
    public ResponseEntity<ProjectHealth> getHealth(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        return ResponseEntity.ok(traceabilityAggregationService.getProjectHealth(orgId, projectId, userId));
    }

    @GetMapping("/gaps")
    public ResponseEntity<List<TraceabilityGap>> getGaps(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        return ResponseEntity.ok(traceabilityAggregationService.getProjectGaps(orgId, projectId, userId));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncTraceability(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        int synced = traceabilityAggregationService.syncTraceability(orgId, projectId, userId);
        return ResponseEntity.ok(Map.of(
                "projectId", projectId,
                "syncedLinks", synced,
                "status", "SUCCESS"
        ));
    }

    @PostMapping("/links")
    public ResponseEntity<TraceabilityLinkDto> createLink(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateTraceabilityLinkRequest request) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        TraceabilityLink link = traceabilityAggregationService.createLink(orgId, projectId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TraceabilityLinkDto.from(link));
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> deleteLink(
            @PathVariable UUID projectId,
            @PathVariable UUID linkId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        traceabilityAggregationService.deleteLink(orgId, linkId, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshots")
    public ResponseEntity<TraceabilitySnapshotResponse> createSnapshot(
            @PathVariable UUID projectId,
            @RequestBody(required = false) CreateSnapshotRequest request) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        String name = request != null ? request.snapshotName() : null;
        Integer prNumber = request != null ? request.prNumber() : null;
        String commitSha = request != null ? request.commitSha() : null;
        UUID repositoryId = request != null ? request.repositoryId() : null;
        TraceabilitySnapshot snapshot = traceabilityAggregationService.createSnapshot(
                orgId, projectId, userId, name, prNumber, commitSha, repositoryId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(TraceabilitySnapshotResponse.from(snapshot));
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<TraceabilitySnapshotResponse>> listSnapshots(@PathVariable UUID projectId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        return ResponseEntity.ok(traceabilityAggregationService.listSnapshots(orgId, projectId, userId));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public ResponseEntity<TraceabilitySnapshotResponse> getSnapshot(
            @PathVariable UUID projectId,
            @PathVariable UUID snapshotId) {
        UUID orgId = TenantContext.getOrganizationId();
        UUID userId = TenantContext.getUserId();
        validateTenantContext(orgId, userId);
        return ResponseEntity.ok(traceabilityAggregationService.getSnapshot(orgId, projectId, snapshotId, userId));
    }

    private void validateTenantContext(UUID orgId, UUID userId) {
        if (orgId == null && userId == null) {
            throw new IllegalArgumentException("Missing required tenant context: either X-Org-Id or X-User-Id must be provided");
        }
    }
}
