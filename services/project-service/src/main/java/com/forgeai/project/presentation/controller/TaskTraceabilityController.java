package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.service.TraceabilityAggregationService;
import com.forgeai.project.infrastructure.security.TenantContext;
import com.forgeai.project.presentation.dto.TaskTraceabilityResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * TaskTraceabilityController
 * <p>
 * Exposes task-centric continuous delivery traceability at:
 * GET /api/v1/tasks/{taskId}/traceability
 * <p>
 * Enforces fail-closed multi-tenant scoping via {@link TenantContext}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskTraceabilityController {

    private final TraceabilityAggregationService traceabilityAggregationService;

    @GetMapping("/{taskId}/traceability")
    public ResponseEntity<TaskTraceabilityResponse> getTaskTraceability(@PathVariable UUID taskId) {
        UUID orgId = getRequiredOrgId();
        return ResponseEntity.ok(traceabilityAggregationService.getTaskTraceability(orgId, taskId));
    }

    private UUID getRequiredOrgId() {
        UUID orgId = TenantContext.getOrganizationId();
        if (orgId == null) {
            throw new IllegalArgumentException("Missing required X-Org-Id tenant context header");
        }
        return orgId;
    }
}
