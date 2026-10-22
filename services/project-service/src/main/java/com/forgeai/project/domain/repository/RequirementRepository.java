package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.Requirement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementRepository {
    Requirement save(Requirement requirement);
    Optional<Requirement> findById(UUID id);
    Optional<Requirement> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<Requirement> findByProjectIdAndIdentifier(UUID projectId, String identifier);
    List<Requirement> findAllByProjectId(UUID projectId);
    List<Requirement> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);
    boolean existsByProjectIdAndIdentifier(UUID projectId, String identifier);
}
