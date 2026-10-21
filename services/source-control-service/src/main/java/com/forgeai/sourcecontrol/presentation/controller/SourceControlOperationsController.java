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
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader) {
        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        Map<String, Object> stats = new HashMap<>();

        if (rawOrg != null && !rawOrg.isBlank()) {
            try {
                UUID organizationId = UUID.fromString(rawOrg.trim());
                long pending = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "PENDING");
                long retrying = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "RETRYING");
                long deadLetter = outboxEventRepository.countByOrganizationIdAndStatus(organizationId, "DEAD_LETTER");
                stats.put("pending", pending);
                stats.put("retrying", retrying);
                stats.put("deadLetter", deadLetter);
                stats.put("totalUnpublished", pending + retrying);
                stats.put("rateLimitRemaining", metrics.getRateLimitRemaining());
                return ResponseEntity.ok(stats);
            } catch (IllegalArgumentException ignored) {}
        }

        long pending = outboxEventRepository.countByStatus("PENDING");
        long retrying = outboxEventRepository.countByStatus("RETRYING");
        long deadLetter = outboxEventRepository.countByStatus("DEAD_LETTER");
        long totalUnpublished = outboxEventRepository.countByPublishedAtIsNull();

        stats.put("pending", pending);
        stats.put("retrying", retrying);
        stats.put("deadLetter", deadLetter);
        stats.put("totalUnpublished", totalUnpublished);
        stats.put("rateLimitRemaining", metrics.getRateLimitRemaining());

        return ResponseEntity.ok(stats);
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        if (rawOrg == null || rawOrg.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID organizationId;
        try {
            organizationId = UUID.fromString(rawOrg.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        int pageSize = Math.min(size, 50);
        List<OutboxEventEntity> deadLetters = outboxEventRepository.findByOrganizationIdAndStatusOrderByDeadLetterAtDesc(
                organizationId, "DEAD_LETTER", PageRequest.of(page, pageSize));
        return ResponseEntity.ok(deadLetters);
    }

    @PostMapping("/outbox/events/{id}/replay")
    public ResponseEntity<Map<String, Object>> replayOutboxEvent(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Organization-Id", required = false) String orgHeader,
            @RequestHeader(value = "X-Org-Id", required = false) String altOrgHeader,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String rolesHeader,
            @RequestHeader(value = "X-Permissions", required = false) String permissionsHeader) {

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        if (rawOrg == null || rawOrg.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Tenant context (X-Organization-Id) is required for operational replay"));
        }
        UUID organizationId;
        try {
            organizationId = UUID.fromString(rawOrg.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Invalid organization UUID"));
        }

        // Privileged operation authorization check (requires operator or admin role/permission)
        if (!isAuthorizedOperatorOrAdmin(rolesHeader, permissionsHeader)) {
            log.warn("Unauthorized replay attempt: user {} lacks operator/admin privileges (roles={}, permissions={})",
                    userId, rolesHeader, permissionsHeader);
            auditService.recordEvent(
                    organizationId,
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

        // Multi-tenant structured check: verify event belongs to caller's organization
        if (entity.getOrganizationId() != null && !entity.getOrganizationId().equals(organizationId)) {
            log.warn("Cross-tenant replay attempt blocked: caller org {} does not match event org {}",
                    organizationId, entity.getOrganizationId());
            auditService.recordEvent(
                    organizationId,
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
        AuditEventEntity auditEvent = auditService.recordEvent(
                organizationId,
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
                "auditEventId", auditEvent.getId(),
                "message", "Outbox event successfully reset to PENDING and scheduled for immediate processing"
        ));
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
            @RequestParam(defaultValue = "50") int limit) {

        String rawOrg = orgHeader != null && !orgHeader.isBlank() ? orgHeader : altOrgHeader;
        if (rawOrg == null || rawOrg.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        UUID organizationId;
        try {
            organizationId = UUID.fromString(rawOrg.trim());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        List<AuditEventEntity> events = auditService.getRecentEventsForTenant(organizationId, limit);
        return ResponseEntity.ok(events);
    }
}
