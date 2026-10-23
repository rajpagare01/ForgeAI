package com.forgeai.sourcecontrol.presentation.controller;

import com.forgeai.sourcecontrol.application.service.AuditService;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.AuditEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/operations")
@RequiredArgsConstructor
public class SourceControlOperationsController {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final SourceControlMetrics metrics;
    private final AuditService auditService;

    @GetMapping("/outbox/stats")
    public ResponseEntity<Map<String, Object>> getOutboxStats(
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader) {
        if (internalHeader == null || !internalHeader.equals("attested")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Untrusted internal context"));
        }

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        String rawWs = wsHeader != null && !wsHeader.isBlank() ? wsHeader : null;
        if ((rawOrg == null || rawOrg.isBlank()) && (rawWs == null || rawWs.isBlank())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Tenant context (X-Organization-Id or X-Workspace-Id) is required for operational stats; global fallback disabled"));
        }

        try {
            UUID organizationId = (rawOrg != null && !rawOrg.isBlank()) ? UUID.fromString(rawOrg.trim()) : null;
            UUID workspaceId = (rawWs != null && !rawWs.isBlank()) ? UUID.fromString(rawWs.trim()) : null;
            Map<String, Object> stats = new HashMap<>();
            long pending;
            long retrying;
            long deadLetter;
            if (organizationId != null) {
                pending = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "PENDING");
                retrying = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "RETRYING");
                deadLetter = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "DEAD_LETTER");
            } else {
                pending = outboxEventRepository.countByWorkspaceIdAndStatus(workspaceId, "PENDING");
                retrying = outboxEventRepository.countByWorkspaceIdAndStatus(workspaceId, "RETRYING");
                deadLetter = outboxEventRepository.countByWorkspaceIdAndStatus(workspaceId, "DEAD_LETTER");
            }
            stats.put("pending", pending);
            stats.put("retrying", retrying);
            stats.put("deadLetter", deadLetter);
            stats.put("totalUnpublished", pending + retrying);
            stats.put("rateLimitRemaining", metrics.getRateLimitRemaining());
            return ResponseEntity.ok(stats);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Invalid tenant/workspace UUID"));
        }
    }

    @GetMapping("/github/stats")
    public ResponseEntity<Map<String, Object>> getGitHubStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("activeInstallations", metrics.getActiveInstallations());
        stats.put("quarantinedInstallations", metrics.getQuarantinedInstallations());
        stats.put("suspendedInstallations", metrics.getSuspendedInstallations());
        stats.put("rateLimitRemaining", metrics.getRateLimitRemaining());
        stats.put("rateLimitReset", metrics.getRateLimitReset());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/outbox/dead-letter")
    public ResponseEntity<List<OutboxEventEntity>> getDeadLetterEvents(
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (internalHeader == null || !internalHeader.equals("attested")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        String rawWs = wsHeader != null && !wsHeader.isBlank() ? wsHeader : null;
        if ((rawOrg == null || rawOrg.isBlank()) && (rawWs == null || rawWs.isBlank())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID organizationId = null;
        UUID workspaceId = null;
        try {
            if (rawOrg != null && !rawOrg.isBlank()) {
                organizationId = UUID.fromString(rawOrg.trim());
            }
            if (rawWs != null && !rawWs.isBlank()) {
                workspaceId = UUID.fromString(rawWs.trim());
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        int pageSize = Math.min(size, 50);
        List<OutboxEventEntity> deadLetters;
        if (organizationId != null) {
            deadLetters = outboxEventRepository.findByOrganizationIdAndStatusOrderByDeadLetterAtDesc(
                    organizationId, "DEAD_LETTER", PageRequest.of(page, pageSize));
        } else {
            deadLetters = outboxEventRepository.findByWorkspaceIdAndStatusOrderByDeadLetterAtDesc(
                    workspaceId, "DEAD_LETTER", PageRequest.of(page, pageSize));
        }
        return ResponseEntity.ok(deadLetters);
    }

    @PostMapping("/outbox/events/{id}/replay")
    public ResponseEntity<Map<String, Object>> replayOutboxEvent(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader) {

        if (internalHeader == null || !internalHeader.equals("attested")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Forbidden: untrusted internal context"));
        }

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        String rawWs = wsHeader != null && !wsHeader.isBlank() ? wsHeader : null;
        if ((rawOrg == null || rawOrg.isBlank()) && (rawWs == null || rawWs.isBlank())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Tenant context (X-Organization-Id or X-Workspace-Id) is required for operational replay"));
        }
        UUID organizationId = null;
        UUID workspaceId = null;
        try {
            if (rawOrg != null && !rawOrg.isBlank()) {
                organizationId = UUID.fromString(rawOrg.trim());
            }
            if (rawWs != null && !rawWs.isBlank()) {
                workspaceId = UUID.fromString(rawWs.trim());
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Invalid organization or workspace UUID"));
        }

        UUID auditOrgId = organizationId;
        UUID auditWsId = workspaceId != null ? workspaceId : organizationId;

        // Privileged operation authorization check (requires operator or admin role/permission)
        if (!isAuthorizedOperatorOrAdmin(rolesHeader, permissionsHeader)) {
            log.warn("Unauthorized replay attempt: user {} lacks operator/admin privileges (roles={}, permissions={})",
                    userId, rolesHeader, permissionsHeader);
            recordAudit(
                    organizationId,
                    workspaceId,
                    userId != null ? userId : "unauthorized_user",
                    "OUTBOX_REPLAY_DENIED",
                    "OUTBOX_EVENT",
                    id.toString(),
                    "REPLAY",
                    "DENIED",
                    Map.of("reason", "Insufficient operator/admin permissions")
            );
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Forbidden: operational replay requires operator or organization admin privileges"));
        }

        OutboxEventEntity entity = outboxEventRepository.findById(id).orElse(null);
        if (entity == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Outbox event not found with id: " + id));
        }

        // Check tenant / workspace ownership
        if (organizationId != null) {
            if (entity.getOrganizationId() == null) {
                log.warn("Replay denied for event {} with null organizationId (caller org {})", id, organizationId);
                recordAudit(
                        organizationId,
                        workspaceId,
                        userId != null ? userId : "operator",
                        "OUTBOX_REPLAY_DENIED",
                        "OUTBOX_EVENT",
                        id.toString(),
                        "REPLAY",
                        "DENIED",
                        Map.of("reason", "Outbox event has no tenant ownership; replay denied")
                );
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "Forbidden: outbox event has no tenant ownership"));
            }
            if (!entity.getOrganizationId().equals(organizationId)) {
                log.warn("Cross-tenant replay attempt blocked: caller org {} does not match event org {}",
                        organizationId, entity.getOrganizationId());
                recordAudit(
                        organizationId,
                        workspaceId,
                        userId != null ? userId : "operator",
                        "OUTBOX_REPLAY_DENIED",
                        "OUTBOX_EVENT",
                        id.toString(),
                        "REPLAY",
                        "DENIED",
                        Map.of("reason", "Cross-tenant access attempted")
                );
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "Forbidden: outbox event does not belong to your organization"));
            }
        } else if (workspaceId != null) {
            if (entity.getWorkspaceId() == null || entity.getOrganizationId() != null || !entity.getWorkspaceId().equals(workspaceId)) {
                log.warn("Cross-workspace replay attempt blocked: caller ws {} does not match event ws {}",
                        workspaceId, entity.getWorkspaceId());
                recordAudit(
                        null,
                        workspaceId,
                        userId != null ? userId : "operator",
                        "OUTBOX_REPLAY_DENIED",
                        "OUTBOX_EVENT",
                        id.toString(),
                        "REPLAY",
                        "DENIED",
                        Map.of("reason", "Cross-tenant or cross-workspace access attempted")
                );
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "error", "Forbidden: outbox event does not belong to your organization or workspace"));
            }
        }

        // Validate state
        if (!"DEAD_LETTER".equalsIgnoreCase(entity.getStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Event is not in DEAD_LETTER state (current: " + entity.getStatus() + ")"));
        }

        // Safe replay reset
        entity.setStatus("PENDING");
        entity.setAttemptCount(0);
        entity.setNextRetryAt(Instant.now());
        entity.setDeadLetterAt(null);
        entity.setLastError(null);
        outboxEventRepository.save(entity);

        // Record audit event for compliance
        AuditEventEntity auditEvent = recordAudit(
                organizationId,
                workspaceId,
                userId != null ? userId : "operator",
                "OUTBOX_REPLAY_REQUESTED",
                "OUTBOX_EVENT",
                id.toString(),
                "REPLAY",
                "SUCCESS",
                Map.of("previousStatus", "DEAD_LETTER", "resetTo", "PENDING")
        );

        return ResponseEntity.ok(Map.of(
                "status", "REPLAY_SCHEDULED",
                "eventId", entity.getId(),
                "auditEventId", auditEvent != null ? auditEvent.getId() : UUID.randomUUID(),
                "message", "Outbox event successfully reset to PENDING and scheduled for immediate processing"
        ));
    }

    private AuditEventEntity recordAudit(UUID organizationId, UUID workspaceId, String actorUserId, String eventType, String entityType, String entityId, String action, String outcome, Map<String, Object> metadata) {
        if (organizationId != null) {
            return auditService.recordEvent(organizationId, actorUserId, eventType, entityType, entityId, action, outcome, metadata);
        } else {
            return auditService.recordEvent(null, workspaceId, actorUserId, eventType, entityType, entityId, action, outcome, metadata);
        }
    }

    private boolean isAuthorizedOperatorOrAdmin(String rolesHeader, String permissionsHeader) {
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            for (String role : rolesHeader.split(",")) {
                String trimmed = role.trim().toUpperCase();
                if (trimmed.equals("ROLE_ADMIN") || trimmed.equals("ROLE_OPERATOR") ||
                    trimmed.equals("ADMIN") || trimmed.equals("OPERATOR") || trimmed.equals("OWNER")) {
                    return true;
                }
            }
        }
        if (permissionsHeader != null && !permissionsHeader.isBlank()) {
            for (String perm : permissionsHeader.split(",")) {
                String trimmed = perm.trim().toLowerCase();
                if (trimmed.equals("outbox:replay") || trimmed.equals("org:admin") ||
                    trimmed.equals("org:owner") || trimmed.equals("operations:write") ||
                    trimmed.equals("admin") || trimmed.equals("*")) {
                    return true;
                }
            }
        }
        return false;
    }

    @GetMapping("/audit-events")
    public ResponseEntity<List<AuditEventEntity>> getRecentAuditEvents(
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-Workspace-Id", required = false) String wsHeader,
            @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader,
            @RequestHeader(value = "X-ForgeAI-Internal", required = false) String internalHeader,
            @RequestParam(defaultValue = "50") int limit) {

        if (internalHeader == null || !internalHeader.equals("attested")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        String rawWs = wsHeader != null && !wsHeader.isBlank() ? wsHeader : null;
        if ((rawOrg == null || rawOrg.isBlank()) && (rawWs == null || rawWs.isBlank())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID organizationId = null;
        UUID workspaceId = null;
        try {
            if (rawOrg != null && !rawOrg.isBlank()) {
                organizationId = UUID.fromString(rawOrg.trim());
            }
            if (rawWs != null && !rawWs.isBlank()) {
                workspaceId = UUID.fromString(rawWs.trim());
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        if (!isAuthorizedOperatorOrAdmin(rolesHeader, permissionsHeader)) {
            log.warn("Unauthorized audit events access: user lacks operator/admin privileges (roles={}, permissions={})",
                    rolesHeader, permissionsHeader);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<AuditEventEntity> events;
        if (organizationId != null) {
            events = auditService.getRecentEventsForTenant(organizationId, limit);
        } else {
            events = auditService.getRecentEventsForWorkspace(workspaceId, limit);
        }
        return ResponseEntity.ok(events);
    }

    public ResponseEntity<Map<String, Object>> getOutboxStats(
            String orgHeader,
            String altOrgHeader,
            String internalHeader) {
        return getOutboxStats(orgHeader, altOrgHeader, null, internalHeader);
    }

    public ResponseEntity<List<OutboxEventEntity>> getDeadLetterEvents(
            String orgHeader,
            String altOrgHeader,
            String internalHeader,
            int page,
            int size) {
        return getDeadLetterEvents(orgHeader, altOrgHeader, null, internalHeader, page, size);
    }

    public ResponseEntity<Map<String, Object>> replayOutboxEvent(
            UUID id,
            String orgHeader,
            String altOrgHeader,
            String userId,
            String rolesHeader,
            String permissionsHeader,
            String internalHeader) {
        return replayOutboxEvent(id, orgHeader, altOrgHeader, null, userId, rolesHeader, permissionsHeader, internalHeader);
    }

    public ResponseEntity<List<AuditEventEntity>> getRecentAuditEvents(
            String orgHeader,
            String altOrgHeader,
            String rolesHeader,
            String permissionsHeader,
            String internalHeader,
            int limit) {
        return getRecentAuditEvents(orgHeader, altOrgHeader, null, rolesHeader, permissionsHeader, internalHeader, limit);
    }
}
