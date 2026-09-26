package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.RoleRepository;
import com.forgeai.identity.domain.model.Role;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.RoleJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RoleRepositoryAdapter implements RoleRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.RoleRepository jpaRepository;
    private final RoleMapper mapper;

    @Override
    public Optional<Role> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Role save(Role entity) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(entity)));
    }

    // Placeholder for other methods based on interfaces...
}
