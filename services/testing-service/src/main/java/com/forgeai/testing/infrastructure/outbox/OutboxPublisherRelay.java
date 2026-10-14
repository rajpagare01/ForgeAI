package com.forgeai.testing.infrastructure.outbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.domain.event.DomainEvent;
import com.forgeai.testing.domain.event.EventEnvelope;
import com.forgeai.testing.domain.event.TestRunCompletedEvent;
import com.forgeai.testing.domain.event.TestRunFailedEvent;
import com.forgeai.testing.infrastructure.messaging.EventPublisher;
import com.forgeai.testing.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class OutboxPublisherRelay {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Scheduled(fixedDelayString = "${forgeai.outbox.poll-interval-ms:500}")
    @Transactional
    public void processOutboxBatch() {
        List<OutboxEventEntity> pendingEvents;
        try {
            pendingEvents = outboxEventRepository.findUnpublishedEventsForProcessing(50);
        } catch (Exception e) {
            pendingEvents = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        }

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEventEntity entity : pendingEvents) {
            try {
                dispatchEntity(entity);
                entity.setPublishedAt(Instant.now());
                entity.setLastError(null);
                outboxEventRepository.save(entity);
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}: {}", entity.getId(), e.getMessage(), e);
                entity.setAttemptCount(entity.getAttemptCount() + 1);
                entity.setLastError(e.getMessage());
                outboxEventRepository.save(entity);
            }
        }
    }

    private void dispatchEntity(OutboxEventEntity entity) throws Exception {
        JsonNode rootNode = objectMapper.readTree(entity.getPayload());
        while (rootNode != null && rootNode.isTextual()) {
            rootNode = objectMapper.readTree(rootNode.asText());
        }
        String eventType = entity.getEventType();
        JsonNode payloadNode = rootNode != null && rootNode.has("payload") ? rootNode.get("payload") : rootNode;

        DomainEvent domainEvent = switch (eventType) {
            case "TEST_RUN_COMPLETED" -> objectMapper.treeToValue(payloadNode, TestRunCompletedEvent.class);
            case "TEST_RUN_FAILED" -> objectMapper.treeToValue(payloadNode, TestRunFailedEvent.class);
            default -> null;
        };

        if (domainEvent != null) {
            EventEnvelope<DomainEvent> envelope = EventEnvelope.from(domainEvent, entity.getTraceId());
            eventPublisher.publish(envelope);
        } else {
            log.warn("Unknown event type encountered in outbox: {}", eventType);
        }
    }
}
