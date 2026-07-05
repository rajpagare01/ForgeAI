package com.forgeai.identity.infrastructure.messaging.event;

import java.util.UUID;

public class UserRegisteredApplicationEvent extends ApplicationEvent {
    private UUID userId;

    public UserRegisteredApplicationEvent() {}

    public UserRegisteredApplicationEvent(UUID eventId, UUID aggregateId, String eventType, java.time.Instant occurredAt, int version, UUID userId) {
        super(eventId, aggregateId, eventType, occurredAt, version);
        this.userId = userId;
    }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
}
