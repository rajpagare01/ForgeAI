package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.ProjectRepositoryLink;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepositoryLinkDto(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID repositoryId,
        String repositoryName,
        Instant createdAt
) {
    public static ProjectRepositoryLinkDto from(ProjectRepositoryLink link) {
        return new ProjectRepositoryLinkDto(
                link.getId(),
                link.getOrganizationId(),
                link.getProjectId(),
                link.getRepositoryId(),
                link.getRepositoryName(),
                link.getCreatedAt()
        );
    }
}
