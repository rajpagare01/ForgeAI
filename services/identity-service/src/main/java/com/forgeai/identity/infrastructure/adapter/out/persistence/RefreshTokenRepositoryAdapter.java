package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.domain.model.RefreshToken;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.RefreshTokenMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RefreshTokenRepositoryAdapter implements com.forgeai.identity.application.port.out.RefreshTokenRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.RefreshTokenRepository jpaRepository;
    private final RefreshTokenMapper mapper;

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        var entity = mapper.toEntity(refreshToken);
        var saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<RefreshToken> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(mapper::toDomain);
    }

    @Override
    public Optional<RefreshToken> findByTokenHashWithLock(String tokenHash) {
        return jpaRepository.findByTokenHashWithLock(tokenHash).map(mapper::toDomain);
    }

    @Override
    public void revokeBySessionId(UUID sessionId) {
        jpaRepository.revokeBySessionId(sessionId, Instant.now());
    }

    @Override
    public void revokeByFamilyId(UUID familyId) {
        jpaRepository.revokeByFamilyId(familyId, Instant.now());
    }
}
