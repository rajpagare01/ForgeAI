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
public class GitHubInstallation {
    private UUID id;
    private UUID organizationId;
    private UUID workspaceId;
    private String scope;
    private Long installationId;
    private Long githubAppId;
    private Long accountId;

    private String accountLogin;
    private String accountType;
    @Builder.Default
    private String repositorySelection = "ALL";
    private UUID claimedByUserId;
    private InstallationStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastSyncedAt;
}
