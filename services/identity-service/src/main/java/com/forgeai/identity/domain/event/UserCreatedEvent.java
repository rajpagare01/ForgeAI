package com.forgeai.identity.domain.event;

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
public class UserCreatedEvent implements DomainEvent {

    private UUID userId;
    private String email;
    private String firstName;
    private String lastName;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "USER_CREATED";
    }

    @Override
    public String getAggregateType() {
        return "USER";
    }

    @Override
    public String getAggregateId() {
        return userId != null ? userId.toString() : "";
    }
}
