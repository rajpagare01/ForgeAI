package com.forgeai.testing.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.forgeai.testing.domain.event.DomainEvent;
import com.forgeai.testing.domain.event.EventEnvelope;
import com.forgeai.testing.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
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
    public <T extends DomainEvent> OutboxEventEntity publish(T event, String traceId) {
        try {
            EventEnvelope<T> envelope = EventEnvelope.from(event, traceId);
            String payloadJson = objectMapper.writeValueAsString(envelope);

            OutboxEventEntity entity = OutboxEventEntity.builder()
                    .aggregateType(event.getAggregateType())
                    .aggregateId(event.getAggregateId())
                    .eventType(event.getEventType())
                    .payload(payloadJson)
                    .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                    .createdAt(Instant.now())
                    .attemptCount(0)
                    .build();

            OutboxEventEntity saved = outboxEventRepository.save(entity);
            log.debug("Persisted outbox event: type={}, aggregateId={}", event.getEventType(), event.getAggregateId());
            return saved;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize domain event: {}", event.getEventType(), e);
            throw new RuntimeException("Error serializing domain event for outbox", e);
        }
    }
}
