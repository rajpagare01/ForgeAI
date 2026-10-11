package com.forgeai.project.application.command;

import com.forgeai.project.domain.model.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTaskCommand(
        UUID requirementId,

        @NotBlank(message = "Task key is required")
        @Size(max = 32, message = "Task key cannot exceed 32 characters")
        String taskKey,

        @NotBlank(message = "Title is required")
        @Size(max = 500, message = "Title cannot exceed 500 characters")
        String title,

        String description,

        TaskPriority priority,

        UUID assigneeId
) {}
