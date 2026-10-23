package com.forgeai.testing.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    String getEventType();
    String getAggregateType();
    String getAggregateId();
    Instant getTimestamp();
    default UUID getOrganizationId() { return null; }
    default UUID getWorkspaceId() { return null; }
    default String getScope() { return "ORGANIZATION"; }
}
