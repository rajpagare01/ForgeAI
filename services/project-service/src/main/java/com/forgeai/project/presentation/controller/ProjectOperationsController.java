package com.forgeai.project.presentation.controller;

import com.forgeai.project.application.service.AuditService;
import com.forgeai.project.infrastructure.client.CircuitBreakerRegistry;
import com.forgeai.project.infrastructure.client.SimpleCircuitBreaker;
import com.forgeai.project.infrastructure.metrics.ProjectMetrics;
import com.forgeai.project.infrastructure.persistence.entity.AuditEventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/operations")
public class ProjectOperationsController {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AuditService auditService;
    private final ProjectMetrics projectMetrics;

    public ProjectOperationsController(CircuitBreakerRegistry circuitBreakerRegistry,
                                       AuditService auditService) {
        this(circuitBreakerRegistry, auditService, null);
    }

    @Autowired
    public ProjectOperationsController(CircuitBreakerRegistry circuitBreakerRegistry,
                                       AuditService auditService,
                                       @Autowired(required = false) ProjectMetrics projectMetrics) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.auditService = auditService;
        this.projectMetrics = projectMetrics;
    }

    @GetMapping("/circuit-breakers")
    public ResponseEntity<List<Map<String, Object>>> getCircuitBreakers() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, SimpleCircuitBreaker> all = circuitBreakerRegistry.getAllBreakers();

        for (Map.Entry<String, SimpleCircuitBreaker> entry : all.entrySet()) {
            SimpleCircuitBreaker cb = entry.getValue();
            Map<String, Object> item = new HashMap<>();
            item.put("serviceName", cb.getServiceName());
            item.put("state", cb.currentState().name());
            item.put("isOpen", cb.isOpen());
            item.put("consecutiveFailures", cb.getConsecutiveFailures());
            item.put("openedAt", cb.getOpenedAt() != null ? cb.getOpenedAt().toString() : null);
            item.put("resetTimeoutSeconds", cb.getResetTimeout().toSeconds());
            result.add(item);
        }

        return ResponseEntity.ok(result);
    }

    @GetMapping("/traceability/stats")
    public ResponseEntity<Map<String, Object>> getTraceabilityStats() {
        Map<String, Object> stats = new HashMap<>();
        if (projectMetrics != null) {
            stats.put("syncTotal", projectMetrics.getTraceabilitySyncCount());
            stats.put("syncFailures", projectMetrics.getTraceabilitySyncFailureCount());
            stats.put("degradedTotal", projectMetrics.getTraceabilityDegradedCount());
            stats.put("projectsProcessed", projectMetrics.getTraceabilityProjectsProcessed());
            stats.put("gapsDetected", projectMetrics.getTraceabilityGapsDetected());
        } else {
            stats.put("syncTotal", 0);
            stats.put("syncFailures", 0);
            stats.put("degradedTotal", 0);
            stats.put("projectsProcessed", 0);
            stats.put("gapsDetected", 0);
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/audit-events")
    public ResponseEntity<List<AuditEventEntity>> getRecentAuditEvents(
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader,
            @RequestParam(defaultValue = "50") int limit) {

        if (!"attested".equals(internalHeader)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        if (rawOrg != null && !rawOrg.isBlank()) {
            try {
                UUID organizationId = UUID.fromString(rawOrg.trim());
                List<AuditEventEntity> events = auditService.getRecentEventsForTenant(organizationId, limit);
                return ResponseEntity.ok(events);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        if (wsHeader != null && !wsHeader.isBlank()) {
            try {
                UUID workspaceId = UUID.fromString(wsHeader.trim());
                List<AuditEventEntity> events = auditService.getRecentEventsForWorkspace(workspaceId, limit);
                return ResponseEntity.ok(events);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
        }

        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
}
