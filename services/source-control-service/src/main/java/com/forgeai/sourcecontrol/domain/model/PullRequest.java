package com.forgeai.sourcecontrol.domain.model;

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
public class PullRequest {
    private UUID id;
    private UUID organizationId;
    private UUID workspaceId;
    private UUID repositoryId;

    private Long githubPrId;
    private Integer number;
    private String title;
    private String body;
    private String sourceBranch;
    private String targetBranch;
    private PullRequestStatus status;
    private String authorGithubId;
    private Instant createdAt;
    private Instant updatedAt;
}
