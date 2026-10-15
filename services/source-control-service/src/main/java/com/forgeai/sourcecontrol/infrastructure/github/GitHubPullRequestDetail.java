package com.forgeai.sourcecontrol.infrastructure.github;

import java.time.Instant;
import java.util.List;

/**
 * Detailed pull request metadata from GitHub including base/head SHAs and changed files list.
 */
public record GitHubPullRequestDetail(
        Long githubPrId,
        int number,
        String title,
        String body,
        String state,
        String sourceBranch,
        String targetBranch,
        String baseSha,
        String headSha,
        String authorLogin,
        int additions,
        int deletions,
        int changedFilesCount,
        Instant createdAt,
        Instant updatedAt,
        List<GitHubPullRequestFile> files
) {}
