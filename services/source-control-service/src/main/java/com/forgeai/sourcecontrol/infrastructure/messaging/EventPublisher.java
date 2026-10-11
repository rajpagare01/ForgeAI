package com.forgeai.sourcecontrol.infrastructure.messaging;

import com.forgeai.sourcecontrol.domain.event.EventEnvelope;

public interface EventPublisher {
    void publish(EventEnvelope<?> envelope);
}
