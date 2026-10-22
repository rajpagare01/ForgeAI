package com.forgeai.project.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateProjectCommand(
        @NotBlank(message = "Project name is required")
        @Size(max = 255, message = "Project name cannot exceed 255 characters")
        String name,

        @NotBlank(message = "Project key is required")
        @Size(min = 2, max = 16, message = "Project key must be between 2 and 16 characters")
        @Pattern(regexp = "^[A-Z0-9]+$", message = "Project key must be alphanumeric uppercase")
        String key,

        String description,

        UUID workspaceId
) {
    public CreateProjectCommand(String name, String key, String description) {
        this(name, key, description, null);
    }
}
