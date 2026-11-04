package com.forgeai.project.domain.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchCreatedEvent implements DomainEvent {
    private UUID repositoryId;
    private UUID organizationId;
    private UUID workspaceId;
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
