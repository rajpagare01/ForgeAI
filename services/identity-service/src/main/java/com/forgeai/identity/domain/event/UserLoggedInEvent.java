package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class UserLoggedInEvent extends DomainEvent {
    public UserLoggedInEvent(UUID aggregateId) { super(aggregateId); }
}
