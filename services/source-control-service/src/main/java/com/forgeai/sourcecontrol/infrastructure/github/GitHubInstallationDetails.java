package com.forgeai.sourcecontrol.infrastructure.github;

public record GitHubInstallationDetails(
        Long id,
        Long appId,
        Long accountId,
        String accountLogin,
        String accountType,
        String repositorySelection,
        boolean suspended
) {}
