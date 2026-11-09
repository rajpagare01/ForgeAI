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
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class MemberRoleChangedEvent implements DomainEvent {

    private UUID organizationId;
    private UUID userId;
    private String roleName;
    private String status;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "MEMBER_ROLE_CHANGED";
    }

    @Override
    public String getAggregateType() {
        return "ORGANIZATION_MEMBER";
    }

    @Override
    public String getAggregateId() {
        return organizationId + ":" + userId;
    }
}
