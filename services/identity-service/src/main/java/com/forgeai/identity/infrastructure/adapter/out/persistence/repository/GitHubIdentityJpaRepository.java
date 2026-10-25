package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.GitHubIdentityJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GitHubIdentityJpaRepository extends JpaRepository<GitHubIdentityJpaEntity, UUID> {
    Optional<GitHubIdentityJpaEntity> findByGithubUserId(Long githubUserId);
    Optional<GitHubIdentityJpaEntity> findByUserId(UUID userId);
    Optional<GitHubIdentityJpaEntity> findByGithubLoginIgnoreCase(String githubLogin);
    boolean existsByGithubUserId(Long githubUserId);
    boolean existsByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
