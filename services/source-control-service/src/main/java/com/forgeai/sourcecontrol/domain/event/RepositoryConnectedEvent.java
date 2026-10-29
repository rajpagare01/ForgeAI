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
public class RepositoryConnectedEvent implements DomainEvent {
    private UUID repositoryId;
    private UUID organizationId;
    private UUID workspaceId;
    private Long githubRepoId;
    private String fullName;
    private String defaultBranch;
    private Instant timestamp;
    private String scope;

    @Override
    public String getScope() {
        if (scope != null && !scope.isBlank()) {
            return scope;
        }
        return organizationId != null ? "ORGANIZATION" : "PERSONAL";
    }

    @Override
    public UUID getWorkspaceId() {
        return workspaceId != null ? workspaceId : organizationId;
    }

    @Override
    public String getEventType() {
        return "REPOSITORY_CONNECTED";
    }

}
