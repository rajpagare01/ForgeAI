package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class SessionCreatedEvent extends DomainEvent {
    private final UUID userId;
    public SessionCreatedEvent(UUID sessionId, UUID userId) {
        super(sessionId);
        this.userId = userId;
    }
    public UUID getUserId() { return userId; }
}
