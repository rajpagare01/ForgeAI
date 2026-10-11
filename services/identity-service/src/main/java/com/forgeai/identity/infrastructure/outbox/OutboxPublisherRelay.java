package com.forgeai.identity.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.domain.event.EventEnvelope;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OutboxEventRepository;
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

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Value("${outbox.publisher.max-attempts:5}")
    private int maxAttempts = 5;

    @Scheduled(fixedDelayString = "${outbox.poll.interval-ms:500}")
    public void processOutboxBatch() {
        try {
            processBatchInternal();
        } catch (Exception e) {
            log.error("Unhandled error during outbox batch processing loop", e);
        }
    }

    @Transactional
    public int processBatchInternal() {
        // Fetch up to batchSize records using SELECT ... FOR UPDATE SKIP LOCKED
        List<OutboxEventEntity> pendingEvents = outboxEventRepository
                .findUnprocessedEventsForUpdateSkipLocked(batchSize, maxAttempts);

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        log.debug("Acquired {} outbox events for dispatch", pendingEvents.size());

        for (OutboxEventEntity eventEntity : pendingEvents) {
            processSingleEvent(eventEntity);
        }

        return pendingEvents.size();
    }

    private void processSingleEvent(OutboxEventEntity entity) {
        try {
            EventEnvelope<?> envelope = objectMapper.readValue(entity.getPayload(), EventEnvelope.class);

            // Dispatch through configured EventPublisher abstraction
            eventPublisher.publish(envelope);

            // Mark published upon successful dispatch
            entity.setPublishedAt(Instant.now());
            entity.setLastError(null);
            outboxEventRepository.save(entity);

            log.debug("Outbox event successfully published: id={}, type={}", entity.getId(), entity.getEventType());
        } catch (Exception e) {
            int newAttemptCount = entity.getAttemptCount() + 1;
            entity.setAttemptCount(newAttemptCount);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            entity.setLastError(errorMsg);

            if (newAttemptCount >= maxAttempts) {
                log.error("HIGH SEVERITY ALERT: Outbox event reached max retry attempts ({}/{}). Marked as POISON_PILL dead letter: id={}, type={}, error={}",
                        newAttemptCount, maxAttempts, entity.getId(), entity.getEventType(), errorMsg);
            } else {
                log.warn("Failed to dispatch outbox event (attempt {}/{}). Will retry with backoff: id={}, type={}, error={}",
                        newAttemptCount, maxAttempts, entity.getId(), entity.getEventType(), errorMsg);
            }

            outboxEventRepository.save(entity);
        }
    }
}
