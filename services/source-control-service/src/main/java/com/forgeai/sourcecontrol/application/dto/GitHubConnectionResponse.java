package com.forgeai.sourcecontrol.application.dto;

import java.util.UUID;

/**
 * Unified representation of a user's GitHub connection status,
 * combining GitHub OAuth user authentication, GitHub App installation status,
 * and workspace-scoped repository discovery metrics.
 */
public record GitHubConnectionResponse(
        boolean githubUserConnected,
        Long githubUserId,
        String githubLogin,
        String githubName,
        String githubEmail,
        String githubAvatarUrl,
        String installationStatus,
        boolean installationRequired,
        Long installationId,
        String repositorySelection,
        String appInstallUrl,
        String appSlug,
        UUID workspaceId,
        String workspaceScope,
        int repositoriesAvailable,
        int repositoriesConnected,
        String connectionState,
        String message
) {}

