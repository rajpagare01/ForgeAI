package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.OAuthStateRepository;
import com.forgeai.identity.domain.model.OAuthState;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.OAuthStateJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.OAuthStateMapper;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.OAuthStateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OAuthStateRepositoryAdapter implements OAuthStateRepository {

    private final OAuthStateJpaRepository jpaRepository;
    private final OAuthStateMapper mapper;

    @Override
    public Optional<OAuthState> findByState(String state) {
        return jpaRepository.findByState(state).map(mapper::toDomain);
    }

    @Override
    public OAuthState save(OAuthState oauthState) {
        OAuthStateJpaEntity entity = mapper.toEntity(oauthState);
        OAuthStateJpaEntity saved = jpaRepository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public void cleanupExpiredStates(Instant now) {
        jpaRepository.cleanupExpiredStates(now);
    }
}
