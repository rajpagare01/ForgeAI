package com.forgeai.project.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonAlias({"number", "pr_number", "pullRequestNumber"})
    private Integer prNumber;

    private String title;
    private String body;

    @JsonAlias({"status"})
    private String state;

    @JsonAlias({"sourceBranch", "source_branch"})
    private String headBranch;

    @JsonAlias({"head_sha", "sha"})
    private String headSha;

    @JsonAlias({"targetBranch", "target_branch"})
    private String baseBranch;

    @JsonAlias({"authorGithubId", "author_login", "author"})
    private String authorLogin;

    @JsonAlias({"html_url"})
    private String htmlUrl;

    @JsonAlias({"is_draft"})
    private Boolean isDraft;

    @JsonAlias({"is_merged"})
    private Boolean isMerged;

    private Instant mergedAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Boolean getIsMerged() {
        if (isMerged != null) {
            return isMerged;
        }
        if (state != null) {
            return "MERGED".equalsIgnoreCase(state);
        }
        return Boolean.FALSE;
    }
}
