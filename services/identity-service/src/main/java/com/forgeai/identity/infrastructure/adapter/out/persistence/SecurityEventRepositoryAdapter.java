package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.SecurityEventRepository;
import com.forgeai.identity.domain.model.SecurityEvent;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.SecurityEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SecurityEventRepositoryAdapter implements SecurityEventRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.SecurityEventRepository jpaRepository;
    private final SecurityEventMapper mapper;

    @Override
    public SecurityEvent save(SecurityEvent entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    @Override
    public List<SecurityEvent> findByUserId(UUID userId) {
        return jpaRepository.findByUserId(userId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }

    @Override
    public List<SecurityEvent> findByOrganizationId(UUID organizationId) {
        return jpaRepository.findByOrganizationId(organizationId).stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
