package com.forgeai.identity.infrastructure.outbox;

import com.forgeai.identity.domain.event.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope<?> envelope);
}
