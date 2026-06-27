package com.forgeai.identity.application.port;

import com.forgeai.identity.domain.event.DomainEvent;

import java.util.List;

/**
 * Output port for publishing domain events.
 * Implemented via the Transactional Outbox pattern in the infrastructure layer.
 */
public interface EventPublisherPort {

    /**
     * Publishes a single domain event.
     *
     * @param event the event to publish
     */
    void publish(DomainEvent event);

    /**
     * Publishes a batch of domain events collected from an aggregate.
     *
     * @param events the events to publish
     */
    void publishAll(List<DomainEvent> events);
}
