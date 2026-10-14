package com.forgeai.testing.infrastructure.persistence.repository;

import com.forgeai.testing.infrastructure.persistence.entity.TestRunJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataTestRunRepository extends JpaRepository<TestRunJpaEntity, UUID> {
    Optional<TestRunJpaEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Optional<TestRunJpaEntity> findByRepositoryIdAndWorkflowRunId(UUID repositoryId, Long workflowRunId);
    List<TestRunJpaEntity> findAllByOrganizationId(UUID organizationId);
    List<TestRunJpaEntity> findAllByOrganizationIdAndRepositoryId(UUID organizationId, UUID repositoryId);
    List<TestRunJpaEntity> findAllByOrganizationIdAndCommitSha(UUID organizationId, String commitSha);
    List<TestRunJpaEntity> findAllByOrganizationIdAndRepositoryIdAndPullRequestNumber(UUID organizationId, UUID repositoryId, Integer pullRequestNumber);
}
