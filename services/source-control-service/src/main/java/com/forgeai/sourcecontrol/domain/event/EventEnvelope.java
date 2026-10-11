package com.forgeai.sourcecontrol.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventEnvelope<T> {
    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String traceId;
    private Instant timestamp;
    private T payload;

    public static <T extends DomainEvent> EventEnvelope<T> from(T event, String traceId, String aggregateType, String aggregateId) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(event.getEventType())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                .timestamp(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                .payload(event)
                .build();
    }
}
