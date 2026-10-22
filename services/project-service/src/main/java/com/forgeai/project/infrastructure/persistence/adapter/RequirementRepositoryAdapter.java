package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.repository.RequirementRepository;
import com.forgeai.project.infrastructure.persistence.entity.RequirementJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataRequirementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class RequirementRepositoryAdapter implements RequirementRepository {

    private final SpringDataRequirementRepository springDataRepository;

    @Override
    public Requirement save(Requirement requirement) {
        RequirementJpaEntity entity = EntityDomainMapper.toEntity(requirement);
        RequirementJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<Requirement> findById(UUID id) {
        return springDataRepository.findById(id).map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Requirement> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return springDataRepository.findByIdAndOrganizationId(id, organizationId)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public Optional<Requirement> findByProjectIdAndIdentifier(UUID projectId, String identifier) {
        return springDataRepository.findByProjectIdAndIdentifier(projectId, identifier)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<Requirement> findAllByProjectId(UUID projectId) {
        return springDataRepository.findAllByProjectId(projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<Requirement> findAllByOrganizationIdAndProjectId(UUID organizationId, UUID projectId) {
        return springDataRepository.findAllByOrganizationIdAndProjectId(organizationId, projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByProjectIdAndIdentifier(UUID projectId, String identifier) {
        return springDataRepository.existsByProjectIdAndIdentifier(projectId, identifier);
    }
}
