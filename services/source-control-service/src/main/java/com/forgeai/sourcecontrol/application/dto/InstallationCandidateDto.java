package com.forgeai.sourcecontrol.application.dto;

import com.forgeai.sourcecontrol.domain.model.InstallationStatus;

import java.util.UUID;

public record InstallationCandidateDto(
        Long installationId,
        Long accountId,
        String accountLogin,
        String accountType,
        String repositorySelection,
        boolean alreadyAssociated,
        UUID associatedWorkspaceId,
        InstallationStatus status
) {}
