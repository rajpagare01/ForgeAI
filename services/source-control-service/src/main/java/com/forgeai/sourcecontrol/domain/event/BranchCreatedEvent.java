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
public class BranchCreatedEvent implements DomainEvent {
    private UUID repositoryId;
    private UUID organizationId;
    private String branchName;
    private String ref;
    private Instant timestamp;

    @Override
    public String getEventType() {
        return "BRANCH_CREATED";
    }
}
