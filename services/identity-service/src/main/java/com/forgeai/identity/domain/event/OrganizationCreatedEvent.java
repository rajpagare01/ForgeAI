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
public class OrganizationCreatedEvent implements DomainEvent {

    private UUID organizationId;
    private String slug;
    private String name;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "ORGANIZATION_CREATED";
    }

    @Override
    public String getAggregateType() {
        return "ORGANIZATION";
    }

    @Override
    public String getAggregateId() {
        return organizationId != null ? organizationId.toString() : "";
    }
}
