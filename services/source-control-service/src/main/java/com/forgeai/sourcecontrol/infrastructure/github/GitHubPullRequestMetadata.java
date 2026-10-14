package com.forgeai.sourcecontrol.infrastructure.github;

import java.time.Instant;

/**
 * Authoritative pull request metadata fetched from GitHub REST API.
 */
public record GitHubPullRequestMetadata(
        Long githubPrId,
        int number,
        String title,
        String body,
        String state,
        String sourceBranch,
        String targetBranch,
        String authorLogin,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        Instant mergedAt
) {}
