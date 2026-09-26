package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.SessionRepository;
import com.forgeai.identity.domain.model.Session;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.SessionJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.SessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SessionRepositoryAdapter implements SessionRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.SessionRepository jpaRepository;
    private final SessionMapper mapper;

    @Override
    public Optional<Session> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Session save(Session entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    // Placeholder for other methods based on interfaces...
}
