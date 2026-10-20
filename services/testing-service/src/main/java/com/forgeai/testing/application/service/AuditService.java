package com.forgeai.testing.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.infrastructure.logging.StructuredLogger;
import com.forgeai.testing.infrastructure.persistence.entity.AuditEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataAuditEventRepository;
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
@RequiredArgsConstructor
public class AuditService {

    private final SpringDataAuditEventRepository auditEventRepository;
    private final StructuredLogger structuredLogger;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventEntity recordEvent(
            UUID organizationId,
            String actorUserId,
            String eventType,
            String entityType,
            String entityId,
            String action,
            String outcome,
            Map<String, Object> metadata) {

        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");

        String metadataJson = null;
        if (metadata != null && !metadata.isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(metadata);
            } catch (Exception e) {
                log.warn("Failed to serialize audit metadata: {}", e.getMessage());
            }
        }

        AuditEventEntity entity = AuditEventEntity.builder()
                .organizationId(organizationId)
                .actorUserId(actorUserId)
                .eventType(eventType)
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .outcome(outcome)
                .correlationId(correlationId)
                .traceId(traceId)
                .metadata(metadataJson)
                .createdAt(Instant.now())
                .build();

        AuditEventEntity saved = auditEventRepository.save(entity);

        structuredLogger.info("audit.event.recorded", Map.of(
                "auditEventId", saved.getId().toString(),
                "eventType", eventType,
                "entityType", entityType,
                "entityId", entityId,
                "action", action,
                "outcome", outcome
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public List<AuditEventEntity> getRecentEventsForTenant(UUID organizationId, int limit) {
        return auditEventRepository.findByOrganizationIdOrderByCreatedAtDesc(
                organizationId, PageRequest.of(0, Math.min(limit, 100)));
    }
}
