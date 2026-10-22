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
public class PullRequestMergedEvent implements DomainEvent {
    private UUID pullRequestId;
    private UUID organizationId;
    private UUID workspaceId;
    private UUID repositoryId;
    private Long githubPrId;
    private Integer number;
    private String title;
    private String mergedBy;
    private Instant mergedAt;
    private Instant timestamp;

    @Override
    public UUID getWorkspaceId() {
        return workspaceId != null ? workspaceId : organizationId;
    }

    @Override
    public String getEventType() {
        return "PULL_REQUEST_MERGED";
    }
}
