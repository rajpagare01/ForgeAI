package com.forgeai.identity.infrastructure.adapter.out.persistence.repository;

import com.forgeai.identity.infrastructure.adapter.out.persistence.entity.AuditEventEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SpringDataAuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {

    List<AuditEventEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId, Pageable pageable);

    List<AuditEventEntity> findByOrganizationIdAndEntityTypeOrderByCreatedAtDesc(UUID organizationId, String entityType, Pageable pageable);

    @Override
    default void delete(AuditEventEntity entity) {
        throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
    }

    @Override
    default void deleteById(UUID id) {
        throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
    }

    @Override
    default void deleteAll() {
        throw new UnsupportedOperationException("Audit events are immutable records and cannot be deleted");
    }
}
