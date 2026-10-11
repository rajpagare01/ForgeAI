package com.forgeai.sourcecontrol.domain.event;

import java.time.Instant;

public interface DomainEvent {
    String getEventType();
    Instant getTimestamp();
}
