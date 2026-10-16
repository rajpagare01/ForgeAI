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
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getProjectTraceability(orgId, projectId));
    }

    @GetMapping("/health")
    public ResponseEntity<ProjectHealth> getHealth(@PathVariable UUID projectId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getProjectHealth(orgId, projectId));
    }

    @GetMapping("/gaps")
    public ResponseEntity<List<TraceabilityGap>> getGaps(@PathVariable UUID projectId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getProjectGaps(orgId, projectId));
    }

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncTraceability(@PathVariable UUID projectId) {
        UUID orgId = getRequiredOrgId();
        int synced = traceabilityAggregationService.syncTraceability(orgId, projectId);
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
        UUID orgId = getRequiredOrgId();
        TraceabilityLink link = traceabilityAggregationService.createLink(orgId, projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(TraceabilityLinkDto.from(link));
    }

    @DeleteMapping("/links/{linkId}")
    public ResponseEntity<Void> deleteLink(
            @PathVariable UUID projectId,
            @PathVariable UUID linkId) {
        UUID orgId = getRequiredOrgId();
        traceabilityAggregationService.deleteLink(orgId, linkId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/snapshots")
    public ResponseEntity<TraceabilitySnapshotResponse> createSnapshot(
            @PathVariable UUID projectId,
            @RequestBody(required = false) CreateSnapshotRequest request) {
        UUID orgId = getRequiredOrgId();
        String name = request != null ? request.snapshotName() : null;
        Integer prNumber = request != null ? request.prNumber() : null;
        String commitSha = request != null ? request.commitSha() : null;
        UUID repositoryId = request != null ? request.repositoryId() : null;
        TraceabilitySnapshot snapshot = traceabilityAggregationService.createSnapshot(
                orgId, projectId, name, prNumber, commitSha, repositoryId
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(TraceabilitySnapshotResponse.from(snapshot));
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<TraceabilitySnapshotResponse>> listSnapshots(@PathVariable UUID projectId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.listSnapshots(orgId, projectId));
    }

    @GetMapping("/snapshots/{snapshotId}")
    public ResponseEntity<TraceabilitySnapshotResponse> getSnapshot(
            @PathVariable UUID projectId,
            @PathVariable UUID snapshotId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getSnapshot(orgId, snapshotId));
    }

    private UUID getRequiredOrgId() {
        UUID orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new IllegalArgumentException("Missing required X-Org-Id tenant context header");
        }
        return orgId;
    }
}
