package com.forgeai.testing.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTestRunCommand(
        @NotNull(message = "Repository ID is required")
        UUID repositoryId,

        @NotBlank(message = "Commit SHA is required")
        @Size(min = 7, max = 40, message = "Commit SHA must be between 7 and 40 characters")
        String commitSha,

        Integer pullRequestNumber,
        Long workflowRunId
) {}
