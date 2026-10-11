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
        domain.setFamilyId(entity.getFamilyId());
        domain.setTokenHash(entity.getTokenHash());
        domain.setIssuedAt(entity.getIssuedAt());
        domain.setExpiresAt(entity.getExpiresAt());
        domain.setRevokedAt(entity.getRevokedAt());
        domain.setGracePeriodUntil(entity.getGracePeriodUntil());
        domain.setReplacedBy(entity.getReplacedBy());
        domain.setCreatedAt(entity.getCreatedAt());

        return domain;
    }

    public RefreshTokenJpaEntity toEntity(RefreshToken domain) {
        if (domain == null) {
            return null;
        }

        RefreshTokenJpaEntity entity = new RefreshTokenJpaEntity();
        entity.setId(domain.getId());
        entity.setSessionId(domain.getSessionId());
        entity.setFamilyId(domain.getFamilyId());
        entity.setTokenHash(domain.getTokenHash());
        entity.setIssuedAt(domain.getIssuedAt());
        entity.setExpiresAt(domain.getExpiresAt());
        entity.setRevokedAt(domain.getRevokedAt());
        entity.setGracePeriodUntil(domain.getGracePeriodUntil());
        entity.setReplacedBy(domain.getReplacedBy());
        if (domain.getCreatedAt() != null) {
            entity.setCreatedAt(domain.getCreatedAt());
        }

        return entity;
    }
}
