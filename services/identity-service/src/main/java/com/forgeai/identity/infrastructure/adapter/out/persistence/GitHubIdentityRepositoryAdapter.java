package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.GitHubIdentityRepository;
import com.forgeai.identity.domain.model.GitHubIdentity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.GitHubIdentityJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.GitHubIdentityMapper;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.GitHubIdentityJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GitHubIdentityRepositoryAdapter implements GitHubIdentityRepository {

    private final GitHubIdentityJpaRepository jpaRepository;
    private final GitHubIdentityMapper mapper;

    @Override
    public Optional<GitHubIdentity> findByGithubUserId(Long githubUserId) {
        return jpaRepository.findByGithubUserId(githubUserId).map(mapper::toDomain);
    }

    @Override
    public Optional<GitHubIdentity> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).map(mapper::toDomain);
    }

    @Override
    public Optional<GitHubIdentity> findByGithubLogin(String githubLogin) {
        return jpaRepository.findByGithubLoginIgnoreCase(githubLogin).map(mapper::toDomain);
    }

    @Override
    public boolean existsByGithubUserId(Long githubUserId) {
        return jpaRepository.existsByGithubUserId(githubUserId);
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        return jpaRepository.existsByUserId(userId);
    }

    @Override
    public GitHubIdentity save(GitHubIdentity identity) {
        GitHubIdentityJpaEntity entity = mapper.toEntity(identity);
        GitHubIdentityJpaEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public void deleteByUserId(UUID userId) {
        jpaRepository.deleteByUserId(userId);
    }
}
