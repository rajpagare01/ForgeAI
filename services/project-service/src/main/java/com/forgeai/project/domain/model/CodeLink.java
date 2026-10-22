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
public class CodeLink {
    private UUID id;
    private UUID workspaceId;
    private UUID organizationId;
    private UUID taskId;
    private CodeLinkType linkType;
    private String externalRef;
    private UUID repositoryId;
    private String createdBy;
    private Instant createdAt;
}
