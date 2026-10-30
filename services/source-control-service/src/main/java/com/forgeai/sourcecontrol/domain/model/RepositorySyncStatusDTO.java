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
public class RepositorySyncStatusDTO {
    private UUID repositoryId;
    private UUID organizationId;
    private String repositoryFullName;
    private String defaultBranch;
    private String currentGithubHead;
    private String indexedHead;
    private String latestObservedSha;
    private String latestIndexedSha;
    private RepositorySyncStatus syncStatus;
    private Instant lastSyncedAt;
    private String lastError;
}
