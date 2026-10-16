package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.ProjectRepositoryLinkJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataProjectRepositoryLinkRepository extends JpaRepository<ProjectRepositoryLinkJpaEntity, UUID> {

    Optional<ProjectRepositoryLinkJpaEntity> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<ProjectRepositoryLinkJpaEntity> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);

    boolean existsByOrganizationIdAndProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId);

    void deleteByOrganizationIdAndProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId);
}
