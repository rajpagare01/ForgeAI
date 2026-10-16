package com.forgeai.project.infrastructure.client.dto;

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
public class RemotePullRequestDto {
    private UUID id;
    private UUID organizationId;
    private UUID repositoryId;
    private Integer prNumber;
    private String title;
    private String body;
    private String state;
    private String headBranch;
    private String headSha;
    private String baseBranch;
    private String authorLogin;
    private String htmlUrl;
    private Boolean isDraft;
    private Boolean isMerged;
    private Instant mergedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
