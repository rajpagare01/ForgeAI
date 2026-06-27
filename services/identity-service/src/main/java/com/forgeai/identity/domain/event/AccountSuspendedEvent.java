package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class AccountSuspendedEvent extends DomainEvent {
    public AccountSuspendedEvent(UUID aggregateId) { super(aggregateId); }
}
