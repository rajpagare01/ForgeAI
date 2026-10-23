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
public class TraceabilityLink {
    private UUID id;
    private UUID workspaceId;
    private UUID organizationId;
    private UUID projectId;
    private TraceabilityType sourceType;
    private String sourceId;
    private TraceabilityType targetType;
    private String targetId;
    private UUID repositoryId;
    private String externalReference;
    private LinkOrigin linkOrigin;
    private ConfidenceLevel confidence;
    private String evidence;
    private boolean active;
    private Instant createdAt;
    private Instant updatedAt;
}
