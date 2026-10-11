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
public class BranchCreatedEvent implements DomainEvent {
    private UUID repositoryId;
    private UUID organizationId;
    private String branchName;
    private String ref;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "BRANCH_CREATED";
    }

    @Override
    public String getAggregateType() {
        return "BRANCH";
    }

    @Override
    public String getAggregateId() {
        return branchName != null ? branchName : "";
    }
}
