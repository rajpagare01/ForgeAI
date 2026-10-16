package com.forgeai.project.domain.repository;

import com.forgeai.project.domain.model.TraceabilityLink;
import com.forgeai.project.domain.model.TraceabilityType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TraceabilityLinkRepository {
    TraceabilityLink save(TraceabilityLink link);
    List<TraceabilityLink> saveAll(List<TraceabilityLink> links);
    Optional<TraceabilityLink> findById(UUID organizationId, UUID id);
    List<TraceabilityLink> findByProjectId(UUID organizationId, UUID projectId);
    List<TraceabilityLink> findBySource(UUID organizationId, TraceabilityType sourceType, String sourceId);
    List<TraceabilityLink> findByTarget(UUID organizationId, TraceabilityType targetType, String targetId);
    boolean exists(UUID organizationId, UUID projectId, TraceabilityType sourceType, String sourceId, TraceabilityType targetType, String targetId, String linkOrigin);
    void deleteById(UUID organizationId, UUID id);
}
