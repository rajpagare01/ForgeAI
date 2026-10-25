package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.OAuthState;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OAuthStateJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class OAuthStateMapper {

    public OAuthState toDomain(OAuthStateJpaEntity entity) {
        if (entity == null) {
            return null;
        }
        return OAuthState.builder()
                .state(entity.getState())
                .userId(entity.getUserId())
                .redirectUrl(entity.getRedirectUrl())
                .expiresAt(entity.getExpiresAt())
                .used(entity.isUsed())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public OAuthStateJpaEntity toEntity(OAuthState domain) {
        if (domain == null) {
            return null;
        }
        OAuthStateJpaEntity entity = new OAuthStateJpaEntity();
        entity.setState(domain.getState());
        entity.setUserId(domain.getUserId());
        entity.setRedirectUrl(domain.getRedirectUrl());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setUsed(domain.isUsed());
        if (domain.getCreatedAt() != null) {
            entity.setCreatedAt(domain.getCreatedAt());
        }
        return entity;
    }
}
