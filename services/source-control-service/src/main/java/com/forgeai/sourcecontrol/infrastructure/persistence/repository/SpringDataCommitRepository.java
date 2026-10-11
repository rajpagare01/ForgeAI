package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.CommitJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SpringDataCommitRepository extends JpaRepository<CommitJpaEntity, UUID> {
    Optional<CommitJpaEntity> findByRepositoryIdAndSha(UUID repositoryId, String sha);
    boolean existsByRepositoryIdAndSha(UUID repositoryId, String sha);
}
