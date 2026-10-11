package com.forgeai.identity.domain.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("eventId")
    private UUID eventId;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("aggregateType")
    private String aggregateType;

    @JsonProperty("aggregateId")
    private String aggregateId;

    @JsonProperty("timestamp")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    @JsonProperty("traceId")
    private String traceId;

    @JsonProperty("payload")
    private T payload;

    public static <T extends DomainEvent> EventEnvelope<T> from(T event, String traceId) {
        return EventEnvelope.<T>builder()
                .eventId(UUID.randomUUID())
                .eventType(event.getEventType())
                .aggregateType(event.getAggregateType())
                .aggregateId(event.getAggregateId())
                .timestamp(event.getTimestamp() != null ? event.getTimestamp() : Instant.now())
                .traceId(traceId)
                .payload(event)
                .build();
    }
}
