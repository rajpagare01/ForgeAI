package com.forgeai.sourcecontrol.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.forgeai.sourcecontrol.domain.event.DomainEvent;
import com.forgeai.sourcecontrol.domain.event.EventEnvelope;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class OutboxService {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public OutboxService(SpringDataOutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends DomainEvent> OutboxEventEntity publish(T event, String traceId, String aggregateType, String aggregateId) {
        if (event == null) {
            throw new IllegalArgumentException("Domain event must not be null");
        }
        String scope = event.getScope() != null ? event.getScope() : (event.getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL");
        if ("ORGANIZATION".equalsIgnoreCase(scope)) {
            if (event.getOrganizationId() == null) {
                log.error("Outbox publication rejected: event {} has no organizationId", event.getEventType());
                throw new IllegalArgumentException("Tenant-owned domain event " + event.getEventType() + " requires a non-null organizationId");
            }
        } else if ("PERSONAL".equalsIgnoreCase(scope)) {
            if (event.getWorkspaceId() == null) {
                log.error("Outbox publication rejected: event {} has no workspaceId", event.getEventType());
                throw new IllegalArgumentException("Personal-scoped domain event " + event.getEventType() + " requires a non-null workspaceId");
            }
        } else {
            throw new IllegalArgumentException("Invalid domain event scope: " + scope);
        }

        try {
            // Restore active traceId from MDC first to preserve W3C trace context, then parameter, then random
            String effectiveTraceId = MDC.get("traceId");
            if (effectiveTraceId == null || effectiveTraceId.isBlank()) {
                effectiveTraceId = traceId;
            }
            if (effectiveTraceId == null || effectiveTraceId.isBlank()) {
                effectiveTraceId = UUID.randomUUID().toString().replace("-", "");
            }

            EventEnvelope<T> envelope = EventEnvelope.from(event, effectiveTraceId, aggregateType, aggregateId);
            String payloadJson = objectMapper.writeValueAsString(envelope);

            OutboxEventEntity entity = OutboxEventEntity.builder()
                    .organizationId(event.getOrganizationId())
                    .workspaceId(event.getWorkspaceId() != null ? event.getWorkspaceId() : event.getOrganizationId())
                    .scope(scope.toUpperCase())
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(event.getEventType())
                    .payload(payloadJson)
                    .traceId(effectiveTraceId)
                    .createdAt(Instant.now())
                    .attemptCount(0)
                    .build();


            OutboxEventEntity saved = outboxEventRepository.save(entity);
            log.debug("Persisted outbox event: type={}, aggregateId={}, traceId={}",
                    event.getEventType(), aggregateId, effectiveTraceId);
            return saved;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize domain event: {}", event.getEventType(), e);
            throw new RuntimeException("Error serializing domain event for outbox", e);
        }
    }
}
