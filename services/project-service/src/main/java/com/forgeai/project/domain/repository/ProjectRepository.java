package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.Project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository {
    Project save(Project project);
    Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<Project> findByOrganizationIdAndKey(UUID organizationId, String key);
    List<Project> findAllByOrganizationId(UUID organizationId);
    boolean existsByOrganizationIdAndKey(UUID organizationId, String key);
}
