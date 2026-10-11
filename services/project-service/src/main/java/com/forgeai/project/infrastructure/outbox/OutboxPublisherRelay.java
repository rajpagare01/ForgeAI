package com.forgeai.project.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisherRelay {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize;

    @Value("${outbox.publisher.max-attempts:5}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${outbox.poll.interval-ms:500}")
    public void pollAndPublish() {
        processBatchInternal();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processBatchInternal() {
        List<OutboxEventEntity> pendingEvents =
                outboxEventRepository.findUnprocessedEventsForUpdate(batchSize, maxAttempts);

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        log.debug("Outbox worker acquired {} locked events for publishing", pendingEvents.size());

        for (OutboxEventEntity eventEntity : pendingEvents) {
            publishSingleEvent(eventEntity);
        }

        return pendingEvents.size();
    }

    private void publishSingleEvent(OutboxEventEntity eventEntity) {
        try {
            EventEnvelope<?> envelope = objectMapper.readValue(eventEntity.getPayload(), EventEnvelope.class);

            eventPublisher.publish(envelope);

            eventEntity.setPublishedAt(Instant.now());
            eventEntity.setLastError(null);
            outboxEventRepository.save(eventEntity);

            log.debug("Successfully published outbox event: id={}, type={}",
                    eventEntity.getId(), eventEntity.getEventType());
        } catch (Exception ex) {
            int currentAttempts = eventEntity.getAttemptCount() + 1;
            eventEntity.setAttemptCount(currentAttempts);
            eventEntity.setLastError(ex.getMessage());
            outboxEventRepository.save(eventEntity);

            if (currentAttempts >= maxAttempts) {
                log.error("HIGH SEVERITY ALERT: Outbox event reached max retry attempts ({}/{}). Marked as POISON_PILL dead letter: id={}, type={}, error={}",
                        currentAttempts, maxAttempts, eventEntity.getId(), eventEntity.getEventType(), ex.getMessage());
            } else {
                log.warn("Failed to dispatch outbox event (attempt {}/{}). Will retry with backoff: id={}, type={}, error={}",
                        currentAttempts, maxAttempts, eventEntity.getId(), eventEntity.getEventType(), ex.getMessage());
            }
        }
    }
}
