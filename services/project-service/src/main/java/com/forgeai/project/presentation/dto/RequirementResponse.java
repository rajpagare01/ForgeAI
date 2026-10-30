package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.Requirement;
import com.forgeai.project.domain.model.RequirementStatus;

import java.time.Instant;
import java.util.UUID;

public record RequirementResponse(
        UUID id,
        UUID projectId,
        UUID workspaceId,
        UUID organizationId,
        String identifier,
        String reqKey,
        String title,
        String description,
        RequirementStatus status,
        Integer version,
        Instant createdAt,
        Instant updatedAt
) {
    public static RequirementResponse from(Requirement requirement) {
        if (requirement == null) return null;
        return new RequirementResponse(
                requirement.getId(),
                requirement.getProjectId(),
                requirement.getWorkspaceId(),
                requirement.getOrganizationId(),
                requirement.getIdentifier(),
                requirement.getIdentifier(),
                requirement.getTitle(),
                requirement.getDescription(),
                requirement.getStatus(),
                requirement.getVersion(),
                requirement.getCreatedAt(),
                requirement.getUpdatedAt()
        );
    }
}
