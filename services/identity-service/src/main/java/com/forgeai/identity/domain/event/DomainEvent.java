package com.forgeai.identity.domain.event;
import java.time.Instant;
import java.util.UUID;
public abstract class DomainEvent {
    private final UUID eventId;
    private final UUID aggregateId;
    private final Instant occurredAt;
    private final int eventVersion;

    protected DomainEvent(UUID aggregateId) {
        this.eventId = UUID.randomUUID();
        this.aggregateId = aggregateId;
        this.occurredAt = Instant.now();
        this.eventVersion = 1;
    }

    public UUID getEventId() { return eventId; }
    public UUID getAggregateId() { return aggregateId; }
    public Instant getOccurredAt() { return occurredAt; }
    public int getEventVersion() { return eventVersion; }
}
