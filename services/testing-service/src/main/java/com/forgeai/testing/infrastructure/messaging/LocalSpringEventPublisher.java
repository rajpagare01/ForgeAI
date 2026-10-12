package com.forgeai.testing.infrastructure.messaging;

import com.forgeai.testing.domain.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalSpringEventPublisher implements EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(EventEnvelope<?> envelope) {
        log.info("Publishing event to Spring application context: type={}, aggregateId={}",
                envelope.getEventType(), envelope.getAggregateId());
        applicationEventPublisher.publishEvent(envelope);
    }
}
