package com.forgeai.identity.application.port.out;

import com.forgeai.identity.domain.model.GitHubIdentity;

import java.util.Optional;
import java.util.UUID;

public interface GitHubIdentityRepository {
    Optional<GitHubIdentity> findByGithubUserId(Long githubUserId);
    Optional<GitHubIdentity> findByUserId(UUID userId);
    Optional<GitHubIdentity> findByGithubLogin(String githubLogin);
    boolean existsByGithubUserId(Long githubUserId);
    boolean existsByUserId(UUID userId);
    GitHubIdentity save(GitHubIdentity identity);
    void deleteByUserId(UUID userId);
}
