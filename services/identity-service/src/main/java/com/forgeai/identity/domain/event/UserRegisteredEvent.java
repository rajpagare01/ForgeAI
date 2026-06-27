package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class UserRegisteredEvent extends DomainEvent {
    public UserRegisteredEvent(UUID aggregateId) { super(aggregateId); }
}
