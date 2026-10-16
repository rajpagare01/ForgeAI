package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.TraceabilitySnapshotJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataTraceabilitySnapshotRepository extends JpaRepository<TraceabilitySnapshotJpaEntity, UUID> {

    Optional<TraceabilitySnapshotJpaEntity> findByOrganizationIdAndId(UUID organizationId, UUID id);

    List<TraceabilitySnapshotJpaEntity> findByOrganizationIdAndProjectIdOrderByCreatedAtDesc(UUID organizationId, UUID projectId);
}
