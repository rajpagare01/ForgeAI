package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.CodeLink;
import com.forgeai.project.domain.model.CodeLinkType;

import java.time.Instant;
import java.util.UUID;

public record CodeLinkResponse(
        UUID id,
        UUID organizationId,
        UUID taskId,
        CodeLinkType linkType,
        String externalRef,
        UUID repositoryId,
        String createdBy,
        Instant createdAt
) {
    public static CodeLinkResponse from(CodeLink link) {
        return new CodeLinkResponse(
                link.getId(),
                link.getOrganizationId(),
                link.getTaskId(),
                link.getLinkType(),
                link.getExternalRef(),
                link.getRepositoryId(),
                link.getCreatedBy(),
                link.getCreatedAt()
        );
    }
}
