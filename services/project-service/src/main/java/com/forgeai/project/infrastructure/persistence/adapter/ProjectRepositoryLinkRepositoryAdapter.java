package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.ProjectRepositoryLink;
import com.forgeai.project.domain.repository.ProjectRepositoryLinkRepository;
import com.forgeai.project.infrastructure.persistence.entity.ProjectRepositoryLinkJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataProjectRepositoryLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class ProjectRepositoryLinkRepositoryAdapter implements ProjectRepositoryLinkRepository {

    private final SpringDataProjectRepositoryLinkRepository springDataRepository;

    @Override
    public ProjectRepositoryLink save(ProjectRepositoryLink link) {
        ProjectRepositoryLinkJpaEntity entity = EntityDomainMapper.toEntity(link);
        ProjectRepositoryLinkJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<ProjectRepositoryLink> findById(UUID organizationId, UUID id) {
        return springDataRepository.findByOrganizationIdAndId(organizationId, id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<ProjectRepositoryLink> findByProjectId(UUID organizationId, UUID projectId) {
        return springDataRepository.findByOrganizationIdAndProjectId(organizationId, projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId) {
        return springDataRepository.existsByOrganizationIdAndProjectIdAndRepositoryId(organizationId, projectId, repositoryId);
    }

    @Override
    public void deleteByProjectIdAndRepositoryId(UUID organizationId, UUID projectId, UUID repositoryId) {
        springDataRepository.deleteByOrganizationIdAndProjectIdAndRepositoryId(organizationId, projectId, repositoryId);
    }
}
