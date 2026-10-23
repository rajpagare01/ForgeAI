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
        if (snapshot.getId() != null && springDataRepository.existsById(snapshot.getId())) {
            throw new UnsupportedOperationException("TraceabilitySnapshot is an immutable governance record and cannot be modified: id=" + snapshot.getId());
        }
        TraceabilitySnapshotJpaEntity entity = EntityDomainMapper.toEntity(snapshot);
        TraceabilitySnapshotJpaEntity saved = springDataRepository.save(entity);
        return EntityDomainMapper.toDomain(saved);
    }

    @Override
    public Optional<TraceabilitySnapshot> findById(UUID organizationId, UUID id) {
        if (organizationId != null) {
            return springDataRepository.findByOrganizationIdAndId(organizationId, id)
                    .map(EntityDomainMapper::toDomain);
        }
        return findById(id);
    }

    @Override
    public Optional<TraceabilitySnapshot> findById(UUID id) {
        return springDataRepository.findById(id)
                .map(EntityDomainMapper::toDomain);
    }

    @Override
    public List<TraceabilitySnapshot> findByProjectId(UUID organizationId, UUID projectId) {
        if (organizationId != null) {
            return springDataRepository.findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(organizationId, projectId).stream()
                    .map(EntityDomainMapper::toDomain)
                    .collect(Collectors.toList());
        }
        return findByProjectId(projectId);
    }

    @Override
    public List<TraceabilitySnapshot> findByProjectId(UUID projectId) {
        return springDataRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(EntityDomainMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void delete(UUID organizationId, UUID id) {
        throw new UnsupportedOperationException("Traceability snapshots are immutable historical governance records and cannot be deleted: id=" + id);
    }
}
