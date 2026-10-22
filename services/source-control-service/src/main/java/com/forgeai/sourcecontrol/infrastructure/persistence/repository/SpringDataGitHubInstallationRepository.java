package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataGitHubInstallationRepository extends JpaRepository<GitHubInstallationJpaEntity, UUID> {
    Optional<GitHubInstallationJpaEntity> findByInstallationId(Long installationId);
    Optional<GitHubInstallationJpaEntity> findByOrganizationId(UUID organizationId);
    Optional<GitHubInstallationJpaEntity> findByWorkspaceId(UUID workspaceId);
}

