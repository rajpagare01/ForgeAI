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
public class Repository {
    private UUID id;
    private UUID organizationId;
    private Long githubRepoId;
    private Long installationId;
    private String name;
    private String fullName;
    private String defaultBranch;
    private Boolean isPrivate;
    private String latestObservedSha;
    private String latestIndexedSha;
    private RepositorySyncStatus syncStatus;
    private Instant lastSyncedAt;
    private String lastError;
    private String lastDeliveryId;
    private Instant createdAt;
    private Instant updatedAt;
}
