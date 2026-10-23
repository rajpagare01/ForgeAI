package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.TraceabilityLink;
import com.forgeai.project.domain.model.TraceabilityType;
import com.forgeai.project.domain.repository.TraceabilityLinkRepository;
import com.forgeai.project.infrastructure.persistence.entity.TraceabilityLinkJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataTraceabilityLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class TraceabilityLinkRepositoryAdapter implements TraceabilityLinkRepository {

    private final SpringDataTraceabilityLinkRepository springDataRepository;

    @Override
    public TraceabilityLink save(TraceabilityLink link) {
        TraceabilityLinkJpaEntity entity = EntityDomainMapper.toEntity(link);
        TraceabilityLinkJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public List<TraceabilityLink> saveAll(List<TraceabilityLink> links) {
        List<TraceabilityLinkJpaEntity> entities = links.stream()
                .map(EntityDomainMapper::toEntity)
                .collect(Collectors.toList());
        return springDataRepository.saveAll(entities).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<TraceabilityLink> findById(UUID organizationId, UUID id) {
        if (organizationId != null) {
            return springDataRepository.findByOrganizationIdAndId(organizationId, id)
                    .map(EntityDomainMapper::toDomain);
        }
        return findById(id);
    }

    @Override
    public Optional<TraceabilityLink> findById(UUID id) {
        return springDataRepository.findById(id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<TraceabilityLink> findByProjectId(UUID organizationId, UUID projectId) {
        if (organizationId != null) {
            return springDataRepository.findByOrganizationIdAndProjectId(organizationId, projectId).stream()
                    .map(EntityDomainMapper::toDomain)
                    .collect(Collectors.toList());
        }
        return findByProjectId(projectId);
    }

    @Override
    public List<TraceabilityLink> findByProjectId(UUID projectId) {
        return springDataRepository.findByProjectId(projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<TraceabilityLink> findBySource(UUID organizationId, TraceabilityType sourceType, String sourceId) {
        return springDataRepository.findByOrganizationIdAndSourceTypeAndSourceId(organizationId, sourceType.name(), sourceId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<TraceabilityLink> findByTarget(UUID organizationId, TraceabilityType targetType, String targetId) {
        return springDataRepository.findByOrganizationIdAndTargetTypeAndTargetId(organizationId, targetType.name(), targetId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean exists(UUID organizationId, UUID projectId, TraceabilityType sourceType, String sourceId, TraceabilityType targetType, String targetId, String linkOrigin) {
        if (organizationId != null) {
            return springDataRepository.existsByOrganizationIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
                    organizationId, projectId, sourceType.name(), sourceId, targetType.name(), targetId, linkOrigin
            );
        }
        return springDataRepository.existsByWorkspaceIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
                projectId, projectId, sourceType.name(), sourceId, targetType.name(), targetId, linkOrigin
        );
    }

    @Override
    public boolean exists(UUID workspaceId, UUID organizationId, UUID projectId, TraceabilityType sourceType, String sourceId, TraceabilityType targetType, String targetId, String linkOrigin) {
        if (organizationId != null) {
            return springDataRepository.existsByOrganizationIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
                    organizationId, projectId, sourceType.name(), sourceId, targetType.name(), targetId, linkOrigin
            );
        }
        return springDataRepository.existsByWorkspaceIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
                workspaceId, projectId, sourceType.name(), sourceId, targetType.name(), targetId, linkOrigin
        );
    }

    @Override
    public void deleteById(UUID organizationId, UUID id) {
        deleteById(null, organizationId, id);
    }

    @Override
    public void deleteById(UUID workspaceId, UUID organizationId, UUID id) {
        if (organizationId != null) {
            springDataRepository.deleteByOrganizationIdAndId(organizationId, id);
        } else if (workspaceId != null) {
            springDataRepository.deleteByWorkspaceIdAndId(workspaceId, id);
        } else {
            springDataRepository.deleteById(id);
        }
    }
}
