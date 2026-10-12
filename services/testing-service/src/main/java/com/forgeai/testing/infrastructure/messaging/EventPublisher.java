package com.forgeai.testing.infrastructure.messaging;

import com.forgeai.testing.domain.event.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope<?> envelope);
}
