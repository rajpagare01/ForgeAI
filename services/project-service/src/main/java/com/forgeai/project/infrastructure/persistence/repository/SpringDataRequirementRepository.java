package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.RequirementJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataRequirementRepository extends JpaRepository<RequirementJpaEntity, UUID> {
    Optional<RequirementJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<RequirementJpaEntity> findByProjectIdAndIdentifier(UUID projectId, String identifier);
    List<RequirementJpaEntity> findAllByProjectId(UUID projectId);
    List<RequirementJpaEntity> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);
    boolean existsByProjectIdAndIdentifier(UUID projectId, String identifier);
}
