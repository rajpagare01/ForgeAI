package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubInstallationRepository {
    GitHubInstallation save(GitHubInstallation installation);
    Optional<GitHubInstallation> findByInstallationId(Long installationId);
    /** Returns the installation owned by this organization (status = ACTIVE, orgId != quarantine). */
    Optional<GitHubInstallation> findByOrganizationId(UUID organizationId);
    /** Returns all installations (including quarantined) for diagnostic/admin purposes. */
    List<GitHubInstallation> findAll();
}
