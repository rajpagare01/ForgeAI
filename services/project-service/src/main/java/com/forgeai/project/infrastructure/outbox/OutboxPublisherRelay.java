package com.forgeai.project.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.domain.event.EventEnvelope;
import com.forgeai.project.infrastructure.logging.StructuredLogger;
import com.forgeai.project.infrastructure.metrics.ProjectMetrics;
import com.forgeai.project.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OutboxPublisherRelay {

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ProjectMetrics projectMetrics;
    private final StructuredLogger structuredLogger;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Value("${outbox.publisher.max-attempts:5}")
    private int maxAttempts = 5;

    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper) {
        this(outboxEventRepository, eventPublisher, objectMapper, null, null);
    }

    @Autowired
    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) ProjectMetrics projectMetrics,
                                @Autowired(required = false) StructuredLogger structuredLogger) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.projectMetrics = projectMetrics;
        this.structuredLogger = structuredLogger;
    }

    @Scheduled(fixedDelayString = "${outbox.poll.interval-ms:500}")
    public void pollAndPublish() {
        processBatchInternal();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processBatchInternal() {
        List<OutboxEventEntity> pendingEvents;
        try {
            pendingEvents = outboxEventRepository.findUnprocessedEventsForUpdate(batchSize, maxAttempts);
        } catch (Exception e) {
            pendingEvents = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        }

        if (projectMetrics != null) {
            projectMetrics.updatePendingOutboxCount(pendingEvents.size());
        }

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
        String traceId = eventEntity.getTraceId();
        String correlationId = eventEntity.getCorrelationId();
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put("correlationId", correlationId);
        }
        long start = System.currentTimeMillis();

        try {
            EventEnvelope<?> envelope = objectMapper.readValue(eventEntity.getPayload(), EventEnvelope.class);

            eventPublisher.publish(envelope);

            long latency = System.currentTimeMillis() - start;
            eventEntity.setPublishedAt(Instant.now());
            eventEntity.setStatus("PUBLISHED");
            eventEntity.setLastError(null);
            outboxEventRepository.save(eventEntity);

            if (projectMetrics != null) {
                projectMetrics.recordOutboxSuccess(latency);
            }
            if (structuredLogger != null) {
                structuredLogger.logEvent("INFO", "outbox.event.published", "publishSingleEvent", "SUCCESS",
                        latency, null, "Published outbox event id=" + eventEntity.getId(),
                        Map.of("eventId", eventEntity.getId().toString(), "eventType", eventEntity.getEventType()));
            }

            log.debug("Successfully published outbox event: id={}, type={}",
                    eventEntity.getId(), eventEntity.getEventType());
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - start;
            int currentAttempts = eventEntity.getAttemptCount() + 1;
            eventEntity.setAttemptCount(currentAttempts);
            eventEntity.setLastError(ex.getMessage());

            if (currentAttempts >= maxAttempts) {
                eventEntity.setStatus("DEAD_LETTER");
                eventEntity.setDeadLetterAt(Instant.now());
                outboxEventRepository.save(eventEntity);

                log.error("HIGH SEVERITY ALERT: Outbox event reached max retry attempts ({}/{}). Transitioned to DEAD_LETTER: id={}, type={}, error={}",
                        currentAttempts, maxAttempts, eventEntity.getId(), eventEntity.getEventType(), ex.getMessage());

                if (projectMetrics != null) {
                    projectMetrics.recordOutboxDeadLetter();
                }
                if (structuredLogger != null) {
                    structuredLogger.logEvent("ERROR", "outbox.event.dead_letter", "publishSingleEvent", "DEAD_LETTER",
                            latency, ex.getClass().getSimpleName(), "Outbox event moved to dead-letter: " + ex.getMessage(),
                            Map.of("eventId", eventEntity.getId().toString(), "eventType", eventEntity.getEventType(),
                                    "attempts", currentAttempts, "maxAttempts", maxAttempts));
                }
            } else {
                eventEntity.setStatus("RETRYING");
                long backoffSeconds = (long) Math.min(300, Math.pow(2, currentAttempts));
                eventEntity.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                outboxEventRepository.save(eventEntity);

                log.warn("Failed to dispatch outbox event (attempt {}/{}). Will retry with backoff: id={}, type={}, error={}",
                        currentAttempts, maxAttempts, eventEntity.getId(), eventEntity.getEventType(), ex.getMessage());

                if (projectMetrics != null) {
                    projectMetrics.recordOutboxRetry();
                }
                if (structuredLogger != null) {
                    structuredLogger.logEvent("WARN", "outbox.event.retry", "publishSingleEvent", "RETRYING",
                            latency, ex.getClass().getSimpleName(), "Outbox event scheduled for retry: " + ex.getMessage(),
                            Map.of("eventId", eventEntity.getId().toString(), "eventType", eventEntity.getEventType(),
                                    "attempts", currentAttempts, "nextRetrySeconds", backoffSeconds));
                }
            }
        } finally {
            MDC.remove("traceId");
            MDC.remove("correlationId");
        }
    }
}
