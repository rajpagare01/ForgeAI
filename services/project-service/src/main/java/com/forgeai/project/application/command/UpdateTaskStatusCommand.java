package com.forgeai.project.application.command;

import com.forgeai.project.domain.model.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusCommand(
        @NotNull(message = "New status is required")
        TaskStatus status,

        Integer version
) {}
