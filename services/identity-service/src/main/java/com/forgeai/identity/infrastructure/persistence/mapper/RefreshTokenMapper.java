package com.forgeai.identity.infrastructure.persistence.mapper;

import com.forgeai.identity.domain.entity.RefreshToken;
import com.forgeai.identity.infrastructure.persistence.entity.RefreshTokenJpaEntity;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

@Component
public class RefreshTokenMapper {

    public RefreshTokenJpaEntity toEntity(RefreshToken domain, UUID sessionId) {
        return new RefreshTokenJpaEntity(
                domain.getTokenId(),
                sessionId,
                domain.getTokenHash(),
                domain.getExpiresAt(),
                domain.getReplacedBy().orElse(null),
                domain.isRevoked()
        );
    }

    public RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        try {
            Constructor<RefreshToken> constructor = RefreshToken.class.getDeclaredConstructor(UUID.class, String.class, Instant.class);
            constructor.setAccessible(true);
            
            RefreshToken token = constructor.newInstance(entity.getId(), entity.getTokenHash(), entity.getExpiresAt());
            
            setField(token, "replacedBy", entity.getReplacedBy());
            setField(token, "revoked", entity.isRevoked());
            
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to map RefreshTokenJpaEntity to RefreshToken domain entity", e);
        }
    }
    
    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
