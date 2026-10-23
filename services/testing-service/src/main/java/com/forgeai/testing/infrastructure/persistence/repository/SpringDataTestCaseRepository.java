package com.forgeai.testing.infrastructure.persistence.repository;

import com.forgeai.testing.infrastructure.persistence.entity.TestCaseJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataTestCaseRepository extends JpaRepository<TestCaseJpaEntity, UUID> {
    Optional<TestCaseJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestCaseJpaEntity> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
    Optional<TestCaseJpaEntity> findBySuiteIdAndNameAndClassname(UUID suiteId, String name, String classname);
    List<TestCaseJpaEntity> findAllByOrganizationIdAndRequirementKey(UUID organizationId, String requirementKey);
    List<TestCaseJpaEntity> findAllByWorkspaceIdAndRequirementKey(UUID workspaceId, String requirementKey);
}
