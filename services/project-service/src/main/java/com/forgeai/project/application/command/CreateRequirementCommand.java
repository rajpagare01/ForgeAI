package com.forgeai.project.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRequirementCommand(
        @NotBlank(message = "Requirement identifier is required")
        @Size(max = 32, message = "Identifier cannot exceed 32 characters")
        String identifier,

        @NotBlank(message = "Title is required")
        @Size(max = 500, message = "Title cannot exceed 500 characters")
        String title,

        @NotBlank(message = "Description is required")
        String description
) {}
