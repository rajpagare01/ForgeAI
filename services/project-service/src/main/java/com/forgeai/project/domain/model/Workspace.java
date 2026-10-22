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
public class Workspace {
    private UUID id;
    private String type;
    private UUID ownerUserId;
    private UUID organizationId;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;
}
