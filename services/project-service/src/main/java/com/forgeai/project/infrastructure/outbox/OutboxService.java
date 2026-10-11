package com.forgeai.project.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.event.DomainEvent;
import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Appends a domain event to the outbox table within the currently active transaction.
     * If the enclosing transaction rolls back, this outbox entry will also roll back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends DomainEvent> OutboxEventEntity publish(T event, String traceId) {
        try {
            EventEnvelope<T> envelope = EventEnvelope.from(event, traceId);
            String payloadJson = objectMapper.writeValueAsString(envelope);

            OutboxEventEntity entity = OutboxEventEntity.builder()
                    .aggregateType(event.getAggregateType())
                    .aggregateId(event.getAggregateId())
                    .eventType(event.getEventType())
                    .payload(payloadJson)
                    .traceId(traceId)
                    .attemptCount(0)
                    .build();

            log.debug("Persisting outbox event inside active transaction: type={}, aggregateId={}",
                    event.getEventType(), event.getAggregateId());

            return outboxEventRepository.save(entity);
        } catch (Exception e) {
            log.error("Failed to serialize outbox event payload for type: {}", event.getEventType(), e);
            throw new IllegalStateException("Failed to serialize outbox event", e);
        }
    }
}
