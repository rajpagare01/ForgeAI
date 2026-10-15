package com.forgeai.sourcecontrol.domain.model;

import com.forgeai.sourcecontrol.infrastructure.github.GitHubPullRequestFile;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative change set and metadata for a pull request, grounded in real GitHub state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestChangeSet {
    private UUID repositoryId;
    private String repositoryFullName;
    private int number;
    private String title;
    private String body;
    private String state;
    private String sourceBranch;
    private String targetBranch;
    private String baseSha;
    private String headSha;
    private String author;
    private int additions;
    private int deletions;
    private int changedFilesCount;
    private List<GitHubPullRequestFile> files;
    private Instant createdAt;
    private Instant updatedAt;
}
