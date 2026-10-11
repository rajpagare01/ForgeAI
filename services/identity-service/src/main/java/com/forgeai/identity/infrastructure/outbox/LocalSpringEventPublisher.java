package com.forgeai.identity.infrastructure.outbox;

import com.forgeai.identity.domain.event.EventEnvelope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnMissingBean(name = "kafkaEventPublisher")
@RequiredArgsConstructor
public class LocalSpringEventPublisher implements EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(EventEnvelope<?> envelope) {
        log.info("[LocalBroker] Dispatching event: type={}, id={}, aggregateId={}",
                envelope.getEventType(), envelope.getEventId(), envelope.getAggregateId());
        applicationEventPublisher.publishEvent(envelope);
    }
}
