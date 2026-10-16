package com.forgeai.project.presentation.dto;

import com.forgeai.project.domain.model.TraceabilitySnapshot;

import java.time.Instant;
import java.util.UUID;

public record TraceabilitySnapshotResponse(
        UUID id,
        UUID organizationId,
        UUID projectId,
        String snapshotName,
        Integer prNumber,
        String commitSha,
        UUID repositoryId,
        String snapshotData,
        Instant createdAt
) {
    public static TraceabilitySnapshotResponse from(TraceabilitySnapshot snapshot) {
        return new TraceabilitySnapshotResponse(
                snapshot.getId(),
                snapshot.getOrganizationId(),
                snapshot.getProjectId(),
                snapshot.getSnapshotName(),
                snapshot.getPrNumber(),
                snapshot.getCommitSha(),
                snapshot.getRepositoryId(),
                snapshot.getSnapshotData(),
                snapshot.getCreatedAt()
        );
    }
}
