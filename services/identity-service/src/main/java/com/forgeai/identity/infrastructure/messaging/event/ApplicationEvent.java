package com.forgeai.identity.infrastructure.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base Application Event for versioning and serialization.
 * Translates Domain Events to a format safe for external messaging (Outbox -> Kafka).
 */
public abstract class ApplicationEvent {
    private UUID eventId;
    private UUID aggregateId;
    private String eventType;
    private Instant occurredAt;
    private int version;

    public ApplicationEvent() {
    }

    public ApplicationEvent(UUID eventId, UUID aggregateId, String eventType, Instant occurredAt, int version) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.version = version;
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
