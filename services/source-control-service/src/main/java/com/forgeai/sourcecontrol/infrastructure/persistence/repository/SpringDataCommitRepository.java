package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.CommitJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataCommitRepository extends JpaRepository<CommitJpaEntity, UUID> {
    Optional<CommitJpaEntity> findByRepositoryIdAndSha(UUID repositoryId, String sha);
    boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha);
    java.util.List<CommitJpaEntity> findAllByRepositoryIdOrderByCommittedAtDesc(UUID repositoryId);
    java.util.List<CommitJpaEntity> findAllByOrganizationIdOrderByCommittedAtDesc(UUID organizationId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM CommitJpaEntity c WHERE c.repositoryId = :repositoryId")
    void deleteAllByRepositoryId(@org.springframework.data.repository.query.Param("repositoryId") UUID repositoryId);
}
