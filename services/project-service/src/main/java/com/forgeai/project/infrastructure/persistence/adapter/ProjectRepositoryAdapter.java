package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.repository.ProjectRepository;
import com.forgeai.project.infrastructure.persistence.entity.ProjectJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ProjectRepositoryAdapter implements ProjectRepository {

    private final SpringDataProjectRepository springDataRepository;

    @Override
    public Project save(Project project) {
        ProjectJpaEntity entity = EntityDomainMapper.toEntity(project);
        ProjectJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<Project> findById(UUID id) {
        return springDataRepository.findById(id).map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Project> findByIdAndWorkspaceId(UUID id, UUID workspaceId) {
        return springDataRepository.findByIdAndWorkspaceId(id, workspaceId)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Project> findByWorkspaceIdAndKey(UUID workspaceId, String key) {
        return springDataRepository.findByWorkspaceIdAndKey(workspaceId, key)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<Project> findAllByWorkspaceId(UUID workspaceId) {
        return springDataRepository.findAllByWorkspaceId(workspaceId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByWorkspaceIdAndKey(UUID workspaceId, String key) {
        return springDataRepository.existsByWorkspaceIdAndKey(workspaceId, key);
    }

    @Override
    public Optional<Project> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return springDataRepository.findByIdAndOrganizationId(id, organizationId)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Project> findByOrganizationIdAndKey(UUID organizationId, String key) {
        return springDataRepository.findByOrganizationIdAndKey(organizationId, key)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<Project> findAllByOrganizationId(UUID organizationId) {
        return springDataRepository.findAllByOrganizationId(organizationId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByOrganizationIdAndKey(UUID organizationId, String key) {
        return springDataRepository.existsByOrganizationIdAndKey(organizationId, key);
    }
}
