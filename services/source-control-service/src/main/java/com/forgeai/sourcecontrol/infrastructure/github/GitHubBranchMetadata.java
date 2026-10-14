package com.forgeai.sourcecontrol.infrastructure.github;

/**
 * Authoritative branch metadata fetched from GitHub REST API.
 */
public record GitHubBranchMetadata(
        String name,
        String commitSha,
        boolean isProtected
) {}
