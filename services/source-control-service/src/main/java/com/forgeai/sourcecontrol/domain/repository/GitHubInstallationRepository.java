package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;

import java.util.Optional;
import java.util.UUID;

public interface GitHubInstallationRepository {
    GitHubInstallation save(GitHubInstallation installation);
    Optional<GitHubInstallation> findByInstallationId(Long installationId);
    Optional<GitHubInstallation> findByOrganizationId(UUID organizationId);
}
