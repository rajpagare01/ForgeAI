package com.forgeai.identity.infrastructure.adapter.out.persistence.mapper;

import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RefreshTokenJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenMapper {

    public RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        if (entity == null) {
            return null;
        }

        RefreshToken domain = new RefreshToken();
        domain.setId(entity.getId());
        domain.setSessionId(entity.getSessionId());
        domain.setTokenHash(entity.getTokenHash());
        domain.setIssuedAt(entity.getIssuedAt());
        domain.setExpiresAt(entity.getExpiresAt());
        domain.setRevokedAt(entity.getRevokedAt());
        domain.setReplacedBy(entity.getReplacedBy());

        return domain;
    }

    public RefreshTokenJpaEntity toEntity(RefreshToken domain) {
        if (domain == null) {
            return null;
        }

        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        entity.setId(domain.getId());
        entity.setSessionId(domain.getSessionId());
        entity.setTokenHash(domain.getTokenHash());
        entity.setIssuedAt(domain.getIssuedAt());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setRevokedAt(domain.getRevokedAt());
        entity.setReplacedBy(domain.getReplacedBy());

        return entity;
    }
}
