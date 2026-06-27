package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class PasswordResetRequestedEvent extends DomainEvent {
    public PasswordResetRequestedEvent(UUID aggregateId) { super(aggregateId); }
}
