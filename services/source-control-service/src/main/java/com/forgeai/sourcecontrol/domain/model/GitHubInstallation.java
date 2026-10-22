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

    private String accountLogin;
    private String accountType;
    private InstallationStatus status;
    private Instant createdAt;
    private Instant updatedAt;
}
