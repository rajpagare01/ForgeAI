package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class AccountLockedEvent extends DomainEvent {
    public AccountLockedEvent(UUID aggregateId) { super(aggregateId); }
}
