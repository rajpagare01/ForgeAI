package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GitHubInstallationRepository {
    GitHubInstallation save(GitHubInstallation installation);
    Optional<GitHubInstallation> findByInstallationId(Long installationId);
    /** Returns the installation owned by this organization (status = ACTIVE, orgId != quarantine). */
    Optional<GitHubInstallation> findByOrganizationId(UUID organizationId);
    Optional<GitHubInstallation> findByWorkspaceId(UUID workspaceId);

    default Optional<GitHubInstallation> findByAccountId(Long accountId) {
        if (accountId == null) return Optional.empty();
        return findAll().stream()
                .filter(i -> accountId.equals(i.getAccountId()))
                .findFirst();
    }

    default List<GitHubInstallation> findByAccountLogin(String accountLogin) {
        if (accountLogin == null) return List.of();
        return findAll().stream()
                .filter(i -> accountLogin.equalsIgnoreCase(i.getAccountLogin()))
                .toList();
    }

    default List<GitHubInstallation> findByStatus(InstallationStatus status) {
        if (status == null) return List.of();
        return findAll().stream()
                .filter(i -> status == i.getStatus())
                .toList();
    }

    default List<GitHubInstallation> findByClaimedByUserId(UUID userId) {
        if (userId == null) return List.of();
        return findAll().stream()
                .filter(i -> userId.equals(i.getClaimedByUserId()))
                .toList();
    }

    /** Returns all installations (including quarantined/discovered) for diagnostic/admin purposes. */
    List<GitHubInstallation> findAll();
}
