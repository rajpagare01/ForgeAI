package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.ProjectRepositoryLink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepositoryLinkRepository {
    ProjectRepositoryLink save(ProjectRepositoryLink link);
    Optional<ProjectRepositoryLink> findById(UUID organizationId, UUID id);
    List<ProjectRepositoryLink> findByProjectId(UUID organizationId, UUID projectId);
    boolean existsByProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId);
    void deleteByProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId);
}
