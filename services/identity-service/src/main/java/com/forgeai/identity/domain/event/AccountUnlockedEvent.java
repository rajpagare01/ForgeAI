package com.forgeai.identity.domain.event;
import java.util.UUID;
public final class AccountUnlockedEvent extends DomainEvent {
    public AccountUnlockedEvent(UUID aggregateId) { super(aggregateId); }
}
