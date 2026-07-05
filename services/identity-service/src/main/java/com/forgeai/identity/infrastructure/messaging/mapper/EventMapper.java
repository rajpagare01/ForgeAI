package com.forgeai.identity.infrastructure.messaging.mapper;

import com.forgeai.identity.domain.event.DomainEvent;
import com.forgeai.identity.domain.event.UserRegisteredEvent;
import com.forgeai.identity.infrastructure.messaging.event.ApplicationEvent;
import com.forgeai.identity.infrastructure.messaging.event.UserRegisteredApplicationEvent;
import org.springframework.stereotype.Component;

@Component
public class EventMapper {

    public ApplicationEvent toApplicationEvent(DomainEvent domainEvent) {
        if (domainEvent instanceof UserRegisteredEvent event) {
            return new UserRegisteredApplicationEvent(
                    event.getEventId(),
                    event.getAggregateId(),
                    "UserRegistered",
                    event.getOccurredAt(),
                    1,
                    event.getUserId()
            );
        }
        // Fallback for other events
        return new ApplicationEvent(
                domainEvent.getEventId(),
                domainEvent.getAggregateId(),
                domainEvent.getClass().getSimpleName(),
                domainEvent.getOccurredAt(),
                1
        ) {};
    }
}
