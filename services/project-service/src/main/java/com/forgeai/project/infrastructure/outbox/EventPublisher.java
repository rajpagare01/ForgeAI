package com.forgeai.project.infrastructure.outbox;

import com.forgeai.project.domain.event.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope<?> envelope);
}
