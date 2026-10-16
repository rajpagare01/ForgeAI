package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.ProjectHealth;
import com.forgeai.project.domain.model.TraceabilityGap;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectTraceabilityResponse(
        UUID projectId,
        UUID organizationId,
        ProjectHealth health,
        List<TraceabilityGap> gaps,
        List<TraceabilitySpineChainDto> chains,
        List<ProjectRepositoryLinkDto> repositories,
        List<TraceabilityLinkDto> links,
        Instant generatedAt
) {}
