package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class PasswordResetCompletedEvent extends DomainEvent {
    public PasswordResetCompletedEvent(UUID aggregateId) { super(aggregateId); }
}
