package com.forgeai.sourcecontrol.infrastructure.github;

/**
 * Authoritative GitHub repository metadata fetched via the GitHub Apps API.
 *
 * @param id            GitHub's numeric repository ID (not ForgeAI's UUID)
 * @param installationId GitHub App installation ID that grants access
 * @param name          repository short name (e.g. "governance-core")
 * @param fullName      owner/repo (e.g. "acme/governance-core")
 * @param isPrivate     repository visibility
 * @param defaultBranch default branch from GitHub
 * @param ownerLogin    GitHub account login that owns the repository
 */
public record GitHubRepositoryMetadata(
        Long id,
        Long installationId,
        String name,
        String fullName,
        boolean isPrivate,
        String defaultBranch,
        String ownerLogin
) {}
