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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PullRequestJpaEntity pr WHERE pr.repositoryId = :repositoryId")
    void deleteAllByRepositoryId(@org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId);
}
