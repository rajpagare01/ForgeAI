package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.Task;
import com.forgeai.project.domain.model.TaskPriority;
import com.forgeai.project.domain.model.TaskStatus;

import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID projectId,
        UUID organizationId,
        UUID requirementId,
        String taskKey,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        UUID assigneeId,
        UUID reporterId,
        Integer version,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getProjectId(),
                task.getOrganizationId(),
                task.getRequirementId(),
                task.getTaskKey(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getAssigneeId(),
                task.getReporterId(),
                task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
