package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class PasswordChangedEvent extends DomainEvent {
    public PasswordChangedEvent(UUID aggregateId) { super(aggregateId); }
}
