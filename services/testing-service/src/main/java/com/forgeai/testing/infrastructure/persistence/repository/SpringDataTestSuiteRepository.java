package com.forgeai.testing.infrastructure.persistence.repository;

import com.forgeai.testing.infrastructure.persistence.entity.TestSuiteJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataTestSuiteRepository extends JpaRepository<TestSuiteJpaEntity, UUID> {
    Optional<TestSuiteJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestSuiteJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<TestSuiteJpaEntity> findByOrganizationIdAndRepositoryIdAndName(UUID organizationId, UUID repositoryId, String name);
    Optional<TestSuiteJpaEntity> findByWorkspaceIdAndRepositoryIdAndName(UUID workspaceId, UUID repositoryId, String name);
}
