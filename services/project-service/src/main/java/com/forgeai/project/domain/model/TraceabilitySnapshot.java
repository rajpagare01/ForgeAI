package com.forgeai.project.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TraceabilitySnapshot {
    private UUID id;
    private UUID organizationId;
    private UUID projectId;
    private String snapshotName;
    private Integer prNumber;
    private String commitSha;
    private UUID repositoryId;
    private String snapshotData;
    private Instant createdAt;
}
