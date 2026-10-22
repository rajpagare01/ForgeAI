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
public class CommitCreatedEvent implements DomainEvent {
    private UUID commitId;
    private UUID organizationId;
    private UUID workspaceId;
    private UUID repositoryId;
    private String sha;
    private String message;
    private String authorEmail;
    private String authorName;
    private Instant committedAt;
    private Instant timestamp;

    @Override
    public UUID getWorkspaceId() {
        return workspaceId != null ? workspaceId : organizationId;
    }

    @Override
    public String getEventType() {
        return "COMMIT_CREATED";
    }
}
