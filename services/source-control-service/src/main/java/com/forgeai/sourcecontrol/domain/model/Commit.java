package com.forgeai.sourcecontrol.domain.model;

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
public class Commit {
    private UUID id;
    private UUID organizationId;
    private UUID workspaceId;
    private UUID repositoryId;

    private String sha;
    private String message;
    private String authorEmail;
    private String authorName;
    private Instant committedAt;
    private Instant createdAt;
}
