package com.forgeai.project.infrastructure.persistence.repository;

import com.forgeai.project.infrastructure.persistence.entity.TraceabilityLinkJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataTraceabilityLinkRepository extends JpaRepository<TraceabilityLinkJpaEntity, UUID> {

    Optional<TraceabilityLinkJpaEntity> findByOrganizationIdAndId(UUID organizationId, UUID id);

    Optional<TraceabilityLinkJpaEntity> findByWorkspaceIdAndId(UUID workspaceId, UUID id);

    List<TraceabilityLinkJpaEntity> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);

    List<TraceabilityLinkJpaEntity> findByWorkspaceIdAndProjectId(UUID workspaceId, UUID projectId);

    List<TraceabilityLinkJpaEntity> findByProjectId(UUID projectId);

    List<TraceabilityLinkJpaEntity> findByOrganizationIdAndSourceTypeAndSourceId(
            UUID organizationId, String sourceType, String sourceId
    );

    List<TraceabilityLinkJpaEntity> findByOrganizationIdAndTargetTypeAndTargetId(
            UUID organizationId, String targetType, String targetId
    );

    boolean existsByOrganizationIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
            UUID organizationId,
            UUID projectId,
            String sourceType,
            String sourceId,
            String targetType,
            String targetId,
            String linkOrigin
    );

    boolean existsByWorkspaceIdAndProjectIdAndSourceTypeAndSourceIdAndTargetTypeAndTargetIdAndLinkOrigin(
            UUID workspaceId,
            UUID projectId,
            String sourceType,
            String sourceId,
            String targetType,
            String targetId,
            String linkOrigin
    );

    void deleteByOrganizationIdAndId(UUID organizationId, UUID id);

    void deleteByWorkspaceIdAndId(UUID workspaceId, UUID id);
}
