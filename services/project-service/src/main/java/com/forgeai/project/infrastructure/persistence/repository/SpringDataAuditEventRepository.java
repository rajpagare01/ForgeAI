package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.AuditEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataAuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    List<AuditEventEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    List<AuditEventEntity> findByOrganizationIdAndEntityTypeOrderByCreatedAtDesc(
            UUID organizationId, String entityType, Pageable pageable);

    @Override
    default void delete(AuditEventEntity entity) {
        throw new UnsupportedOperationException("Audit events are immutable and cannot be deleted");
    }

    @Override
    default void deleteById(UUID id) {
        throw new UnsupportedOperationException("Audit events are immutable and cannot be deleted");
    }

    @Override
    default void deleteAll() {
        throw new UnsupportedOperationException("Audit events are immutable and cannot be deleted");
    }
}
