package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.PullRequestJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataPullRequestRepository extends JpaRepository<PullRequestJpaEntity, UUID> {
    Optional<PullRequestJpaEntity> findByRepositoryIdAndNumber(UUID repositoryId, Integer number);
    Optional<PullRequestJpaEntity> findByGithubPrId(Long githubPrId);
    java.util.List<PullRequestJpaEntity> findAllByOrganizationId(UUID organizationId);
    java.util.List<PullRequestJpaEntity> findAllByRepositoryId(UUID repositoryId);
}
