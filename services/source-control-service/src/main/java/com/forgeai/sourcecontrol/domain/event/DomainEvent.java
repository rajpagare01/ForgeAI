package com.forgeai.sourcecontrol.domain.event;

import java.time.Instant;
import java.util.UUID;

public interface DomainEvent {
    String getEventType();
    Instant getTimestamp();
    UUID getOrganizationId();
}
