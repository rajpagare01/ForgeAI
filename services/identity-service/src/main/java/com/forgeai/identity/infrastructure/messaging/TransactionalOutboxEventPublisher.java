package com.forgeai.identity.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.application.port.EventPublisherPort;
import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.infrastructure.messaging.event.ApplicationEvent;
import com.forgeai.identity.infrastructure.messaging.mapper.EventMapper;
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
    private final EventMapper eventMapper;

    public TransactionalOutboxEventPublisher(OutboxJpaRepository outboxRepository, ObjectMapper objectMapper, EventMapper eventMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.eventMapper = eventMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        try {
            ApplicationEvent applicationEvent = eventMapper.toApplicationEvent(event);
            OutboxEventJpaEntity outboxEntity = new OutboxEventJpaEntity();
            outboxEntity.setId(applicationEvent.getEventId());
            outboxEntity.setAggregateType("User"); // Simplify or map from event
            outboxEntity.setAggregateId(applicationEvent.getAggregateId().toString());
            outboxEntity.setType(applicationEvent.getEventType());
            outboxEntity.setPayload(objectMapper.writeValueAsString(applicationEvent));
            
            outboxRepository.save(outboxEntity);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize application event for outbox", e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishAll(List<DomainEvent> events) {
        events.forEach(this::publish);
    }
}
