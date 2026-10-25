package com.forgeai.sourcecontrol.application.dto;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;

import java.util.List;

public record InstallationDiscoveryResponse(
        GitHubInstallation currentInstallation,
        List<InstallationCandidateDto> candidates,
        String userGitHubLogin,
        Long userGitHubId,
        boolean gitHubConnected,
        String appInstallUrl,
        String message
) {}
