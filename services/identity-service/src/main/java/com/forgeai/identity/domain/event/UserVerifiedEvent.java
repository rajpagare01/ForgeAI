package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class UserVerifiedEvent extends DomainEvent {
    public UserVerifiedEvent(UUID aggregateId) { super(aggregateId); }
}
