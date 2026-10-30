package com.forgeai.sourcecontrol.domain.repository;

import com.forgeai.sourcecontrol.domain.model.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepositoryRepository {
    Repository save(Repository repository);
    Optional<Repository> findById(UUID id);
    Optional<Repository> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<Repository> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<Repository> findByGithubRepoId(Long githubRepoId);
    List<Repository> findAllByOrganizationId(UUID organizationId);
    List<Repository> findAllByWorkspaceId(UUID workspaceId);
    List<Repository> findAllByInstallationId(Long installationId);
    List<Repository> findAll();
}

