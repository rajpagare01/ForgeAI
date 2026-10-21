package com.forgeai.sourcecontrol.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.domain.event.*;
import com.forgeai.sourcecontrol.infrastructure.logging.StructuredLogger;
import com.forgeai.sourcecontrol.infrastructure.messaging.EventPublisher;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OutboxPublisherRelay {

    public static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final SpringDataOutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SourceControlMetrics metrics;
    private final StructuredLogger structuredLogger;

    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher) {
        this(outboxEventRepository, eventPublisher, null, null);
    }

    @Autowired
    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                @Autowired(required = false) SourceControlMetrics metrics,
                                @Autowired(required = false) StructuredLogger structuredLogger) {
        this(outboxEventRepository, eventPublisher, new ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
                metrics, structuredLogger);
    }

    public OutboxPublisherRelay(SpringDataOutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                SourceControlMetrics metrics,
                                StructuredLogger structuredLogger) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper()
                .findAndRegisterModules()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.metrics = metrics;
        this.structuredLogger = structuredLogger;
    }

    @Scheduled(fixedDelayString = "${forgeai.outbox.poll-interval-ms:500}")
    @Transactional
    public void processOutboxBatch() {
        List<OutboxEventEntity> pendingEvents;
        try {
            pendingEvents = outboxEventRepository.findUnpublishedEventsForProcessing(50);
        } catch (Exception e) {
            // Fallback for H2 or environments not supporting native FOR UPDATE SKIP LOCKED
            try {
                pendingEvents = outboxEventRepository.findTop100PendingOrRetrying();
            } catch (Exception ex) {
                pendingEvents = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();
            }
        }

        if (metrics != null) {
            metrics.updatePendingOutboxCount(pendingEvents.size());
        }

        if (pendingEvents.isEmpty()) {
            return;
        }

        for (OutboxEventEntity entity : pendingEvents) {
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

                if (metrics != null) {
                    metrics.recordOutboxSuccess(latency);
                }
                if (structuredLogger != null) {
                    structuredLogger.logEvent("INFO", "outbox.event.published", "dispatchEntity", "SUCCESS",
                            latency, null, "Published outbox event id=" + entity.getId(),
                            Map.of("eventId", entity.getId().toString(), "eventType", entity.getEventType(),
                                    "aggregateType", entity.getAggregateType(), "aggregateId", entity.getAggregateId()));
                }
            } catch (Exception e) {
                long latency = System.currentTimeMillis() - start;
                int attempts = (entity.getAttemptCount() != null ? entity.getAttemptCount() : 0) + 1;
                entity.setAttemptCount(attempts);
                entity.setLastError(e.getMessage());
                int maxAttempts = entity.getMaxAttempts() != null ? entity.getMaxAttempts() : DEFAULT_MAX_ATTEMPTS;

                if (attempts >= maxAttempts) {
                    entity.setStatus("DEAD_LETTER");
                    entity.setDeadLetterAt(Instant.now());
                    entity.setNextRetryAt(null);
                    outboxEventRepository.save(entity);

                    log.error("Outbox event id={} exceeded max attempts ({}/{}). Transitioned to DEAD_LETTER: {}",
                            entity.getId(), attempts, maxAttempts, e.getMessage());

                    if (metrics != null) {
                        metrics.recordOutboxDeadLetter();
                    }
                    if (structuredLogger != null) {
                        structuredLogger.logEvent("ERROR", "outbox.event.dead_letter", "dispatchEntity", "DEAD_LETTER",
                                latency, e.getClass().getSimpleName(), "Outbox event moved to dead-letter: " + e.getMessage(),
                                Map.of("eventId", entity.getId().toString(), "eventType", entity.getEventType(),
                                        "aggregateType", entity.getAggregateType(), "aggregateId", entity.getAggregateId(),
                                        "attempts", attempts, "maxAttempts", maxAttempts));
                    }
                } else {
                    entity.setStatus("RETRYING");
                    long backoffSeconds = (long) Math.min(300, Math.pow(2, attempts));
                    entity.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));
                    outboxEventRepository.save(entity);

                    log.warn("Outbox event id={} failed attempt {}/{}. Scheduled retry in {}s: {}",
                            entity.getId(), attempts, maxAttempts, backoffSeconds, e.getMessage());

                    if (metrics != null) {
                        metrics.recordOutboxRetry();
                    }
                    if (structuredLogger != null) {
                        structuredLogger.logEvent("WARN", "outbox.event.retry", "dispatchEntity", "RETRYING",
                                latency, e.getClass().getSimpleName(), "Outbox event scheduled for retry: " + e.getMessage(),
                                Map.of("eventId", entity.getId().toString(), "eventType", entity.getEventType(),
                                        "attempts", attempts, "nextRetrySeconds", backoffSeconds));
                    }
                }
            } finally {
                MDC.remove("traceId");
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
            case "REPOSITORY_CONNECTED" -> objectMapper.treeToValue(payloadNode, RepositoryConnectedEvent.class);
            case "BRANCH_CREATED" -> objectMapper.treeToValue(payloadNode, BranchCreatedEvent.class);
            case "COMMIT_CREATED" -> objectMapper.treeToValue(payloadNode, CommitCreatedEvent.class);
            case "PULL_REQUEST_OPENED" -> objectMapper.treeToValue(payloadNode, PullRequestOpenedEvent.class);
            case "PULL_REQUEST_MERGED" -> objectMapper.treeToValue(payloadNode, PullRequestMergedEvent.class);
            case "WORKFLOW_RUN_RECEIVED" -> objectMapper.treeToValue(payloadNode, WorkflowRunReceivedEvent.class);
            default -> null;
        };

        if (domainEvent == null) {
            throw new IllegalArgumentException("Unsupported or unmapped outbox event type: " + eventType);
        }

        EventEnvelope<DomainEvent> envelope = EventEnvelope.from(
                domainEvent,
                entity.getTraceId(),
                entity.getAggregateType(),
                entity.getAggregateId()
        );
        eventPublisher.publish(envelope);
    }
}
