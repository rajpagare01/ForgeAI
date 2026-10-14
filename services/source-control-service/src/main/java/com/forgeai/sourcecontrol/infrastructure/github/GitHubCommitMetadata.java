package com.forgeai.sourcecontrol.infrastructure.github;

import java.time.Instant;

/**
 * Authoritative commit metadata fetched from GitHub REST API.
 */
public record GitHubCommitMetadata(
        String sha,
        String message,
        String authorName,
        String authorEmail,
        Instant committedAt
) {}
