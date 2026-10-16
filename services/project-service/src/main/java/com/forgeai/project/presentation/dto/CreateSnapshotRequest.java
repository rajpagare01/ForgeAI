package com.forgeai.project.presentation.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CreateSnapshotRequest(
        @NotBlank(message = "Snapshot name is required")
        String snapshotName,
        Integer prNumber,
        String commitSha,
        UUID repositoryId
) {}
