package com.forgeai.project.infrastructure.persistence.adapter;

import com.forgeai.project.domain.model.TraceabilitySnapshot;
import com.forgeai.project.domain.repository.TraceabilitySnapshotRepository;
import com.forgeai.project.infrastructure.persistence.entity.TraceabilitySnapshotJpaEntity;
import com.forgeai.project.infrastructure.persistence.mapper.EntityDomainMapper;
import com.forgeai.project.infrastructure.persistence.repository.SpringDataTraceabilitySnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class TraceabilitySnapshotRepositoryAdapter implements TraceabilitySnapshotRepository {

    private final SpringDataTraceabilitySnapshotRepository springDataRepository;

    @Override
    public TraceabilitySnapshot save(TraceabilitySnapshot snapshot) {
        TraceabilitySnapshotJpaEntity entity = EntityDomainMapper.toEntity(snapshot);
        TraceabilitySnapshotJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<TraceabilitySnapshot> findById(UUID organizationId, UUID id) {
        return springDataRepository.findByOrganizationIdAndId(organizationId, id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<TraceabilitySnapshot> findByProjectId(UUID organizationId, UUID projectId) {
        return springDataRepository.findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(organizationId, projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }
}
