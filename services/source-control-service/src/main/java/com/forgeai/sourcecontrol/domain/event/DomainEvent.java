package com.forgeai.sourcecontrol.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    String getEventType();
    Instant getTimestamp();
    UUID getOrganizationId();
    default UUID getWorkspaceId() {
        return null;
    }
    default String getScope() {
        return getOrganizationId() != null ? "ORGANIZATION" : "PERSONAL";
    }
}

