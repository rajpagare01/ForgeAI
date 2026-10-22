package com.forgeai.identity.domain.model;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private UUID id;
    private WorkspaceType type;
    private UUID ownerUserId;
    private UUID organizationId;
    private String name;
    private Instant createdAt;
    private Instant updatedAt;
}
