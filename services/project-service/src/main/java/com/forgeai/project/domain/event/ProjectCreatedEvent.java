package com.forgeai.project.domain.event;

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
public class ProjectCreatedEvent implements DomainEvent {

    private UUID projectId;
    private UUID organizationId;
    private String key;
    private String name;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "PROJECT_CREATED";
    }

    @Override
    public String getAggregateType() {
        return "PROJECT";
    }

    @Override
    public String getAggregateId() {
        return projectId != null ? projectId.toString() : "";
    }
}
