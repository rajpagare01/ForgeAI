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
        String repositoryName,
        Integer requirementCount,
        Integer taskCount,
        Double healthScore,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse from(Project project) {
        return from(project, null, 0, 0, null);
    }

    public static ProjectResponse from(
            Project project,
            String repositoryName,
            int requirementCount,
            int taskCount,
            Double healthScore) {
        if (project == null) return null;
        return new ProjectResponse(
                project.getId(),
                project.getWorkspaceId(),
                project.getOrganizationId(),
                project.getName(),
                project.getKey(),
                project.getDescription(),
                project.getStatus(),
                repositoryName,
                requirementCount,
                taskCount,
                healthScore,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
