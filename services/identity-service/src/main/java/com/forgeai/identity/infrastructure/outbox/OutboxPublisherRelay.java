package com.forgeai.identity.infrastructure.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.domain.event.EmailVerificationRequestedEvent;
import com.forgeai.identity.domain.event.EventEnvelope;
import com.forgeai.identity.domain.event.MemberRoleChangedEvent;
import com.forgeai.identity.domain.event.OrganizationCreatedEvent;
import com.forgeai.identity.domain.event.UserCreatedEvent;
import com.forgeai.identity.domain.event.UserUpdatedEvent;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OutboxEventEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OutboxEventRepository;
import com.forgeai.identity.infrastructure.logging.StructuredLogger;
import com.forgeai.identity.infrastructure.metrics.IdentityMetrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class OutboxPublisherRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final IdentityMetrics identityMetrics;
    private final StructuredLogger structuredLogger;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize = 50;

    @Value("${outbox.publisher.max-attempts:5}")
    private int maxAttempts = 5;

    public OutboxPublisherRelay(OutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper) {
        this(outboxEventRepository, eventPublisher, objectMapper, null, null);
    }

    @Autowired
    public OutboxPublisherRelay(OutboxEventRepository outboxEventRepository,
                                EventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                @Autowired(required = false) IdentityMetrics identityMetrics,
                                @Autowired(required = false) StructuredLogger structuredLogger) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        if (this.objectMapper != null) {
            this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
        this.identityMetrics = identityMetrics;
        this.structuredLogger = structuredLogger;
    }

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
        List<OutboxEventEntity> pendingEvents = outboxEventRepository
                .findUnprocessedEventsForUpdateSkipLocked(batchSize, maxAttempts);

        if (identityMetrics != null) {
            try {
                identityMetrics.updatePendingOutboxCount((int) outboxEventRepository.countByPublishedAtIsNull());
            } catch (Exception ignored) {
                identityMetrics.updatePendingOutboxCount(pendingEvents.size());
            }
        }

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
            JsonNode rootNode = objectMapper.readTree(entity.getPayload());
            while (rootNode != null && rootNode.isTextual()) {
                rootNode = objectMapper.readTree(rootNode.asText());
            }
            String eventType = entity.getEventType();
            JsonNode payloadNode = rootNode != null && rootNode.has("payload") ? rootNode.get("payload") : rootNode;

            DomainEvent domainEvent = switch (eventType) {
                case "USER_CREATED" -> objectMapper.treeToValue(payloadNode, UserCreatedEvent.class);
                case "USER_UPDATED" -> objectMapper.treeToValue(payloadNode, UserUpdatedEvent.class);
                case "ORGANIZATION_CREATED" -> objectMapper.treeToValue(payloadNode, OrganizationCreatedEvent.class);
                case "MEMBER_ROLE_CHANGED" -> objectMapper.treeToValue(payloadNode, MemberRoleChangedEvent.class);
                case "EMAIL_VERIFICATION_REQUESTED" -> objectMapper.treeToValue(payloadNode, EmailVerificationRequestedEvent.class);
                default -> null;
            };

            EventEnvelope<?> envelope;
            if (domainEvent != null) {
                UUID eventId = (rootNode != null && rootNode.has("eventId") && !rootNode.get("eventId").isNull())
                        ? UUID.fromString(rootNode.get("eventId").asText())
                        : entity.getId();
                envelope = EventEnvelope.builder()
                        .eventId(eventId != null ? eventId : UUID.randomUUID())
                        .eventType(domainEvent.getEventType())
                        .aggregateType(domainEvent.getAggregateType())
                        .aggregateId(domainEvent.getAggregateId())
                        .timestamp(domainEvent.getTimestamp() != null ? domainEvent.getTimestamp() : Instant.now())
                        .traceId(entity.getTraceId())
                        .payload(domainEvent)
                        .build();
            } else {
                envelope = objectMapper.readValue(entity.getPayload(), EventEnvelope.class);
            }

            eventPublisher.publish(envelope);

            long latency = System.currentTimeMillis() - start;
            entity.setPublishedAt(Instant.now());
            entity.setStatus("PUBLISHED");
            entity.setLastError(null);
            outboxEventRepository.save(entity);

            if (identityMetrics != null) {
                identityMetrics.recordOutboxSuccess(latency);
            }

            if (structuredLogger != null) {
                structuredLogger.logEvent("INFO", "outbox.event.published", "publish", "SUCCESS", latency, null,
                        "Published identity outbox event " + entity.getId(),
                        Map.of(
                                "eventId", entity.getId().toString(),
                                "eventType", entity.getEventType(),
                                "aggregateType", entity.getAggregateType(),
                                "aggregateId", entity.getAggregateId()
                        ));
            }

            log.debug("Outbox event successfully published: id={}, type={}", entity.getId(), entity.getEventType());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            int newAttemptCount = entity.getAttemptCount() + 1;
            entity.setAttemptCount(newAttemptCount);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            entity.setLastError(errorMsg);

            int effectiveMax = entity.getMaxAttempts() > 0 ? entity.getMaxAttempts() : maxAttempts;

            if (newAttemptCount >= effectiveMax) {
                entity.setStatus("DEAD_LETTER");
                entity.setDeadLetterAt(Instant.now());
                entity.setNextRetryAt(null);

                if (identityMetrics != null) {
                    identityMetrics.recordOutboxDeadLetter();
                }

                if (structuredLogger != null) {
                    structuredLogger.logEvent("ERROR", "outbox.event.dead_letter", "publish", "DEAD_LETTER", latency,
                            e.getClass().getSimpleName(),
                            "Identity outbox event " + entity.getId() + " reached max attempts (" + newAttemptCount + "/" + effectiveMax + "): " + errorMsg,
                            Map.of(
                                    "eventId", entity.getId().toString(),
                                    "eventType", entity.getEventType(),
                                    "attempts", newAttemptCount,
                                    "maxAttempts", effectiveMax
                            ));
                }
            } else {
                entity.setStatus("RETRYING");
                long backoffSeconds = (long) Math.min(300, Math.pow(2, newAttemptCount));
                entity.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));

                if (identityMetrics != null) {
                    identityMetrics.recordOutboxRetry();
                }

                if (structuredLogger != null) {
                    structuredLogger.logEvent("WARN", "outbox.event.retry", "publish", "RETRYING", latency,
                            e.getClass().getSimpleName(),
                            "Identity outbox event " + entity.getId() + " failed attempt " + newAttemptCount + "; retrying in " + backoffSeconds + "s: " + errorMsg,
                            Map.of(
                                    "eventId", entity.getId().toString(),
                                    "eventType", entity.getEventType(),
                                    "attempts", newAttemptCount,
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
}
