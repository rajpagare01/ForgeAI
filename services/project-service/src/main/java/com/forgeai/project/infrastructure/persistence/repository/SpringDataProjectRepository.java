package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.ProjectJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataProjectRepository extends JpaRepository<ProjectJpaEntity, UUID> {
    Optional<ProjectJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<ProjectJpaEntity> findByOrganizationIdAndKey(UUID organizationId, String key);
    List<ProjectJpaEntity> findAllByOrganizationId(UUID organizationId);
    boolean existsByOrganizationIdAndKey(UUID organizationId, String key);
}
