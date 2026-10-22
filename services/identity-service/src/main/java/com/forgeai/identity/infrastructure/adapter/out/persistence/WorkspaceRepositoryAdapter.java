package com.forgeai.identity.infrastructure.adapter.out.persistence;

import com.forgeai.identity.application.port.out.WorkspaceRepository;
import com.forgeai.identity.domain.model.Workspace;
import com.forgeai.identity.domain.model.WorkspaceType;
import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.WorkspaceJpaEntity;
import com.forgeai.identity.infrastructure.adapter.out.persistence.mapper.WorkspaceMapper;
import com.forgeai.identity.infrastructure.adapter.out.persistence.repository.SpringDataWorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WorkspaceRepositoryAdapter implements WorkspaceRepository {

    private final SpringDataWorkspaceRepository jpaRepository;
    private final WorkspaceMapper mapper;

    @Override
    public Optional<Workspace> findById(UUID id) {
        return jpaRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Workspace> findByTypeAndOwnerUserId(WorkspaceType type, UUID ownerUserId) {
        return jpaRepository.findByTypeAndOwnerUserId(type.name(), ownerUserId).map(mapper::toDomain);
    }

    @Override
    public Optional<Workspace> findByTypeAndOrganizationId(WorkspaceType type, UUID organizationId) {
        return jpaRepository.findByTypeAndOrganizationId(type.name(), organizationId).map(mapper::toDomain);
    }

    @Override
    public List<Workspace> findByOwnerUserId(UUID ownerUserId) {
        return jpaRepository.findByOwnerUserId(ownerUserId).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Workspace> findByTypeAndOrganizationIdIn(WorkspaceType type, Collection<UUID> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return List.of();
        }
        return jpaRepository.findByTypeAndOrganizationIdIn(type.name(), organizationIds).stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Workspace save(Workspace workspace) {
        WorkspaceJpaEntity jpaEntity = mapper.toEntity(workspace);
        WorkspaceJpaEntity saved = jpaRepository.save(jpaEntity);
        return mapper.toDomain(saved);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }
}
