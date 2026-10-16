package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.ConfidenceLevel;
import com.forgeai.project.domain.model.LinkOrigin;
import com.forgeai.project.domain.model.TraceabilityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateTraceabilityLinkRequest(
        @NotNull(message = "Source type is required")
        TraceabilityType sourceType,
        @NotBlank(message = "Source ID is required")
        String sourceId,
        @NotNull(message = "Target type is required")
        TraceabilityType targetType,
        @NotBlank(message = "Target ID is required")
        String targetId,
        UUID repositoryId,
        String externalReference,
        LinkOrigin linkOrigin,
        ConfidenceLevel confidence,
        String evidence
) {}
