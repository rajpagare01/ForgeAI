package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationStateJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataGitHubInstallationStateRepository extends JpaRepository<GitHubInstallationStateJpaEntity, UUID> {

    Optional<GitHubInstallationStateJpaEntity> findByState(String state);

    void deleteAllByExpiresAtBefore(java.time.Instant cutoff);
}

