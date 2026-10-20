package com.forgeai.testing.infrastructure.outbox;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.domain.event.DomainEvent;
import com.forgeai.testing.domain.event.EventEnvelope;
import com.forgeai.testing.domain.event.TestRunCompletedEvent;
import com.forgeai.testing.domain.event.TestRunFailedEvent;
import com.forgeai.testing.infrastructure.logging.StructuredLogger;
import com.forgeai.testing.infrastructure.messaging.EventPublisher;
import com.forgeai.testing.infrastructure.metrics.TestingMetrics;
import com.forgeai.testing.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
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
    private final TestingMetrics testingMetrics;
    private final StructuredLogger structuredLogger;

    @Value("${forgeai.outbox.batch-size:50}")
    private int batchSize = 50;

    @Value("${forgeai.outbox.max-attempts:5}")
    private int maxAttempts = 5;

    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher) {
        this(outboxEventRepository, eventPublisher, null, null);
    }

    @Autowired
    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                @Autowired(required = false) TestingMetrics testingMetrics,
                                @Autowired(required = false) StructuredLogger structuredLogger) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.testingMetrics = testingMetrics;
        this.structuredLogger = structuredLogger;
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Scheduled(fixedDelayString = "${forgeai.outbox.poll-interval-ms:500}")
    public void pollAndPublish() {
        processOutboxBatch();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processOutboxBatch() {
        List<OutboxEventEntity> pendingEvents;
        try {
            pendingEvents = outboxEventRepository.findUnpublishedEventsForProcessing(batchSize);
        } catch (Exception e) {
            pendingEvents = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
        }

        if (testingMetrics != null) {
            try {
                testingMetrics.updatePendingOutboxCount((int) outboxEventRepository.countByPublishedAtIsNull());
            } catch (Exception ignored) {
                testingMetrics.updatePendingOutboxCount(pendingEvents.size());
            }
        }

        if (pendingEvents.isEmpty()) {
            return 0;
        }

        for (OutboxEventEntity entity : pendingEvents) {
            publishSingleEntity(entity);
        }

        return pendingEvents.size();
    }

    private void publishSingleEntity(OutboxEventEntity entity) {
        String traceId = entity.getTraceId();
        String correlationId = entity.getCorrelationId();
        if (traceId != null && !traceId.isBlank()) {
            MDC.put("traceId", traceId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            MDC.put("correlationId", correlationId);
        }

        long start = System.currentTimeMillis();

        try {
            dispatchEntity(entity);

            long latency = System.currentTimeMillis() - start;
            entity.setPublishedAt(Instant.now());
            entity.setStatus("PUBLISHED");
            entity.setLastError(null);
            outboxEventRepository.save(entity);

            if (testingMetrics != null) {
                testingMetrics.recordOutboxSuccess(latency);
            }

            if (structuredLogger != null) {
                structuredLogger.logEvent("INFO", "outbox.event.published", "publish", "SUCCESS", latency, null,
                        "Published testing outbox event " + entity.getId(),
                        Map.of(
                                "eventId", entity.getId().toString(),
                                "eventType", entity.getEventType(),
                                "aggregateType", entity.getAggregateType(),
                                "aggregateId", entity.getAggregateId()
                        ));
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            int attempts = (entity.getAttemptCount() != null ? entity.getAttemptCount() : 0) + 1;
            entity.setAttemptCount(attempts);
            entity.setLastError(e.getMessage());

            int configuredMax = entity.getMaxAttempts() != null && entity.getMaxAttempts() > 0
                    ? entity.getMaxAttempts() : maxAttempts;

            if (attempts >= configuredMax) {
                entity.setStatus("DEAD_LETTER");
                entity.setDeadLetterAt(Instant.now());
                entity.setNextRetryAt(null);

                if (testingMetrics != null) {
                    testingMetrics.recordOutboxDeadLetter();
                }

                if (structuredLogger != null) {
                    structuredLogger.logEvent("ERROR", "outbox.event.dead_letter", "publish", "DEAD_LETTER", latency,
                            e.getClass().getSimpleName(),
                            "Testing outbox event " + entity.getId() + " moved to dead letter after " + attempts + " attempts: " + e.getMessage(),
                            Map.of(
                                    "eventId", entity.getId().toString(),
                                    "eventType", entity.getEventType(),
                                    "attempts", attempts,
                                    "maxAttempts", configuredMax
                            ));
                }
            } else {
                entity.setStatus("RETRYING");
                long backoffSeconds = (long) Math.min(300, Math.pow(2, attempts));
                entity.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));

                if (testingMetrics != null) {
                    testingMetrics.recordOutboxRetry();
                }

                if (structuredLogger != null) {
                    structuredLogger.logEvent("WARN", "outbox.event.retry", "publish", "RETRYING", latency,
                            e.getClass().getSimpleName(),
                            "Testing outbox event " + entity.getId() + " failed attempt " + attempts + "; retrying in " + backoffSeconds + "s: " + e.getMessage(),
                            Map.of(
                                    "eventId", entity.getId().toString(),
                                    "eventType", entity.getEventType(),
                                    "attempts", attempts,
                                    "nextRetryInSeconds", backoffSeconds
                            ));
                }
            }

            outboxEventRepository.save(entity);
        } finally {
            if (traceId != null && !traceId.isBlank()) {
                MDC.remove("traceId");
            }
            if (correlationId != null && !correlationId.isBlank()) {
                MDC.remove("correlationId");
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
