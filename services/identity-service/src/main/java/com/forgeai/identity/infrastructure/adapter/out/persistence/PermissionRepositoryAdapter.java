package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.PermissionRepository;
import com.forgeai.identity.domain.model.Permission;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PermissionRepositoryAdapter implements PermissionRepository {

    private final com.forgeai.identity.infrastructure.adapter.out.persistence.repository.PermissionRepository jpaRepository;
    private final PermissionMapper mapper;

    @Override
    public Optional<Permission> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Permission> findByCode(String code) {
        return jpaRepository.findByCode(code).map(mapper::toDomain);
    }
    
    @Override
    public List<Permission> findAll() {
        return jpaRepository.findAll().stream().map(mapper::toDomain).collect(Collectors.toList());
    }
}
