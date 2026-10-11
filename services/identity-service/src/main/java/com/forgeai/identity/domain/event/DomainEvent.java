package com.forgeai.identity.domain.event;

import java.time.Instant;

public interface DomainEvent {
    String getEventType();
    String getAggregateType();
    String getAggregateId();
    Instant getTimestamp();
}
