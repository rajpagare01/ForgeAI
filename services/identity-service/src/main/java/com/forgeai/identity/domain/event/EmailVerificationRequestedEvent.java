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
public class EmailVerificationRequestedEvent implements DomainEvent {

    private UUID userId;
    private String newEmail;
    private String verificationToken;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "EMAIL_VERIFICATION_REQUESTED";
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
