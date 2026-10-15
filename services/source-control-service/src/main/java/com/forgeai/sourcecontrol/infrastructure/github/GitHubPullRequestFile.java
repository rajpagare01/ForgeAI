package com.forgeai.sourcecontrol.infrastructure.github;

/**
 * Metadata and diff details for an individual changed file within a pull request.
 */
public record GitHubPullRequestFile(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        String rawUrl,
        String contentsUrl,
        String content
) {}
