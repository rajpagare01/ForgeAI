package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.TraceabilitySnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TraceabilitySnapshotRepository {
    TraceabilitySnapshot save(TraceabilitySnapshot snapshot);
    Optional<TraceabilitySnapshot> findById(UUID organizationId, UUID id);
    Optional<TraceabilitySnapshot> findById(UUID id);
    List<TraceabilitySnapshot> findByProjectId(UUID organizationId, UUID projectId);
    List<TraceabilitySnapshot> findByProjectId(UUID projectId);
    void delete(UUID organizationId, UUID id);
}
