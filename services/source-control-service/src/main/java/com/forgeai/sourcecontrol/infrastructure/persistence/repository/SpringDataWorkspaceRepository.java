package com.forgeai.sourcecontrol.infrastructure.persistence.repository;

import com.forgeai.sourcecontrol.infrastructure.persistence.entity.WorkspaceJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataWorkspaceRepository extends JpaRepository<WorkspaceJpaEntity, UUID> {
    Optional<WorkspaceJpaEntity> findByTypeAndOwnerUserId(String type, UUID ownerUserId);
    Optional<WorkspaceJpaEntity> findByOwnerUserIdAndType(UUID ownerUserId, String type);
    Optional<WorkspaceJpaEntity> findByTypeAndOrganizationId(String type, UUID organizationId);
    List<WorkspaceJpaEntity> findByOwnerUserId(UUID ownerUserId);
}
