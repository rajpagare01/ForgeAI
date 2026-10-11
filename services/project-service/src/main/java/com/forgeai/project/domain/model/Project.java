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
public class Project {
    private UUID id;
    private UUID organizationId;
    private String name;
    private String key;
    private String description;
    private ProjectStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
