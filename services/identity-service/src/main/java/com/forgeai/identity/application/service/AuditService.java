package com.forgeai.identity.application.service;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.AuditEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.SpringDataAuditEventRepository;
import com.forgeai.identity.infrastructure.logging.StructuredLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AuditService {

    private final SpringDataAuditEventRepository auditEventRepository;
    private final StructuredLogger structuredLogger;

    public AuditService(SpringDataAuditEventRepository auditEventRepository, StructuredLogger structuredLogger) {
        this.auditEventRepository = auditEventRepository;
        this.structuredLogger = structuredLogger;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity recordEvent(UUID organizationId,
                                        String actorUserId,
                                        String eventType,
                                        String entityType,
                                        String entityId,
                                        String action,
                                        String outcome,
                                        Map<String, Object> metadata) {
        return recordEvent(organizationId, organizationId, actorUserId, eventType, entityType, entityId, action, outcome, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity recordEvent(UUID organizationId,
                                        UUID workspaceId,
                                        String actorUserId,
                                        String eventType,
                                        String entityType,
                                        String entityId,
                                        String action,
                                        String outcome,
                                        Map<String, Object> metadata) {
        if (organizationId == null && workspaceId == null) {
            log.warn("Attempted to record audit event without organizationId or workspaceId: eventType={}", eventType);
            return null;
        }

        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");

        AuditEventEntity entity = AuditEventEntity.builder()
                .organizationId(organizationId)
                .workspaceId(workspaceId != null ? workspaceId : organizationId)
                .actorUserId(actorUserId)
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .outcome(outcome)
                .traceId(traceId)
                .correlationId(correlationId)
                .metadata(metadata)
                .createdAt(Instant.now())
                .build();

        AuditEventEntity saved = auditEventRepository.save(entity);

        structuredLogger.logEvent("INFO", eventType, action, outcome, null, null,
                "Audit event recorded: " + eventType + " on " + entityType + ":" + entityId,
                Map.of(
                        "auditEventId", saved.getId().toString(),
                        "entityType", entityType,
                        "entityId", entityId,
                        "action", action,
                        "outcome", outcome
                ));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> getRecentEventsForOrg(UUID organizationId, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        return auditEventRepository.findByOrganizationIdOrderByCreatedAtDesc(
                organizationId, PageRequest.of(0, effectiveLimit));
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> getRecentEventsForWorkspace(UUID workspaceId, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        return auditEventRepository.findByWorkspaceIdOrderByCreatedAtDesc(
                workspaceId, PageRequest.of(0, effectiveLimit));
    }
}
