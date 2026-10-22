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
public class Requirement {
    private UUID id;
    private UUID projectId;
    private UUID workspaceId;
    private UUID organizationId;
    private String identifier; // e.g. REQ-001
    private String title;
    private String description;
    private RequirementStatus status;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
