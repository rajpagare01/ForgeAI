package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.TraceabilityLink;

import java.time.Instant;
import java.util.UUID;

public record TraceabilityLinkDto(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String sourceType,
        String sourceId,
        String targetType,
        String targetId,
        UUID repositoryId,
        String externalReference,
        String linkOrigin,
        String confidence,
        String evidence,
        boolean active,
        Instant createdAt
) {
    public static TraceabilityLinkDto from(TraceabilityLink link) {
        return new TraceabilityLinkDto(
                link.getId(),
                link.getOrganizationId(),
                link.getProjectId(),
                link.getSourceType() != null ? link.getSourceType().name() : null,
                link.getSourceId(),
                link.getTargetType() != null ? link.getTargetType().name() : null,
                link.getTargetId(),
                link.getRepositoryId(),
                link.getExternalReference(),
                link.getLinkOrigin() != null ? link.getLinkOrigin().name() : null,
                link.getConfidence() != null ? link.getConfidence().name() : null,
                link.getEvidence(),
                link.isActive(),
                link.getCreatedAt()
        );
    }
}
