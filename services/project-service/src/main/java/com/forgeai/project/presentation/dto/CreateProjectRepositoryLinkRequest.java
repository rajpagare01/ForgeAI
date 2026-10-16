package com.forgeai.project.presentation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateProjectRepositoryLinkRequest(
        @NotNull(message = "Repository ID is required")
        UUID repositoryId,
        String repositoryName
) {}
