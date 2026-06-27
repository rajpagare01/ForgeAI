package com.forgeai.identity.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.infrastructure.persistence.entity.OutboxEventJpaEntity;
import com.forgeai.identity.infrastructure.persistence.repository.OutboxJpaRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class TransactionalOutboxEventPublisher implements EventPublisherPort {

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TransactionalOutboxEventPublisher(OutboxJpaRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        try {
            OutboxEventJpaEntity outboxEntity = new OutboxEventJpaEntity(
                    event.getEventId(),
                    "User", // Simplified aggregate type
                    event.getAggregateId().toString(),
                    event.getClass().getSimpleName(),
                    objectMapper.writeValueAsString(event),
                    event.getOccurredAt()
            );
            outboxRepository.save(outboxEntity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize domain event for outbox", e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishAll(List<DomainEvent> events) {
        events.forEach(this::publish);
    }
}
