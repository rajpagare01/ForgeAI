package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.Project;
import com.forgeai.project.domain.model.ProjectStatus;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        UUID organizationId,
        String name,
        String key,
        String description,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project) {
        if (project == null) return null;
        return new ProjectResponse(
                project.getId(),
                project.getWorkspaceId(),
                project.getOrganizationId(),
                project.getName(),
                project.getKey(),
                project.getDescription(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
