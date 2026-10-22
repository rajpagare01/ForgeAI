package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.RepositoryJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataRepositoryRepository extends JpaRepository<RepositoryJpaEntity, UUID> {
    Optional<RepositoryJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<RepositoryJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<RepositoryJpaEntity> findByGithubRepoId(Long githubRepoId);
    List<RepositoryJpaEntity> findAllByOrganizationId(UUID organizationId);
    List<RepositoryJpaEntity> findAllByWorkspaceId(UUID workspaceId);
    List<RepositoryJpaEntity> findAllByInstallationId(Long installationId);
}

