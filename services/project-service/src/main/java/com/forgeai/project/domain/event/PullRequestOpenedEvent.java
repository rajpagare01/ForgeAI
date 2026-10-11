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
public class PullRequestOpenedEvent implements DomainEvent {
    private UUID pullRequestId;
    private UUID organizationId;
    private UUID repositoryId;
    private Long githubPrId;
    private Integer number;
    private String title;
    private String body;
    private String sourceBranch;
    private String targetBranch;
    private String authorGithubId;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "PULL_REQUEST_OPENED";
    }

    @Override
    public String getAggregateType() {
        return "PULL_REQUEST";
    }

    @Override
    public String getAggregateId() {
        return pullRequestId != null ? pullRequestId.toString() : String.valueOf(number);
    }
}
