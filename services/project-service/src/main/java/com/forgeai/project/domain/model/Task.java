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
public class Task {
    private UUID id;
    private UUID projectId;
    private UUID organizationId;
    private UUID requirementId;
    private String taskKey; // e.g. TASK-101
    private String title;
    private String description;
    private TaskStatus status;
    private TaskPriority priority;
    private UUID assigneeId;
    private UUID reporterId;
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;
}
