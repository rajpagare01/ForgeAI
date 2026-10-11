package com.forgeai.project.application.command;

import com.forgeai.project.domain.model.CodeLinkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record LinkCodeArtifactCommand(
        @NotNull(message = "Link type is required")
        CodeLinkType linkType,

        @NotBlank(message = "External reference is required")
        @Size(max = 500, message = "External reference cannot exceed 500 characters")
        String externalRef,

        @NotNull(message = "Repository ID is required")
        UUID repositoryId,

        String createdBy
) {}
