package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.GitHubIdentity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.GitHubIdentityJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class GitHubIdentityMapper {

    public GitHubIdentity toDomain(GitHubIdentityJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return GitHubIdentity.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .githubUserId(entity.getGithubUserId())
                .githubLogin(entity.getGithubLogin())
                .name(entity.getName())
                .email(entity.getEmail())
                .avatarUrl(entity.getAvatarUrl())
                .profileUrl(entity.getProfileUrl())
                .encryptedAccessToken(entity.getEncryptedAccessToken())
                .tokenType(entity.getTokenType())
                .scope(entity.getScope())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastSyncedAt(entity.getLastSyncedAt())
                .build();
    }

    public GitHubIdentityJpaEntity toEntity(GitHubIdentity domain) {
        if (domain == null) {
            return null;
        }
        GitHubIdentityJpaEntity entity = new GitHubIdentityJpaEntity();
        entity.setId(domain.getId());
        entity.setUserId(domain.getUserId());
        entity.setGithubUserId(domain.getGithubUserId());
        entity.setGithubLogin(domain.getGithubLogin());
        entity.setName(domain.getName());
        entity.setEmail(domain.getEmail());
        entity.setAvatarUrl(domain.getAvatarUrl());
        entity.setProfileUrl(domain.getProfileUrl());
        entity.setEncryptedAccessToken(domain.getEncryptedAccessToken());
        entity.setTokenType(domain.getTokenType());
        entity.setScope(domain.getScope());
        if (domain.getCreatedAt() != null) {
            entity.setCreatedAt(domain.getCreatedAt());
        }
        if (domain.getUpdatedAt() != null) {
            entity.setUpdatedAt(domain.getUpdatedAt());
        }
        if (domain.getLastSyncedAt() != null) {
            entity.setLastSyncedAt(domain.getLastSyncedAt());
        }
        return entity;
    }
}
