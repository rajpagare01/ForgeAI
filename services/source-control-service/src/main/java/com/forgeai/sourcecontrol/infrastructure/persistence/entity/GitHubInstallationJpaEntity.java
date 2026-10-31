package com.forgeai.sourcecontrol.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "github_installations", indexes = {
        @Index(name = "idx_gh_install_org", columnList = "organization_id"),
        @Index(name = "idx_gh_install_workspace", columnList = "workspace_id"),
        @Index(name = "idx_gh_install_account_id", columnList = "account_id"),
        @Index(name = "idx_gh_install_account_login", columnList = "account_login"),
        @Index(name = "idx_gh_install_status", columnList = "status")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "scope", nullable = false, length = 32)
    @Builder.Default
    private String scope = "ORGANIZATION";

    @Column(name = "installation_id", nullable = false, unique = true)
    private Long installationId;

    @Column(name = "github_app_id")
    private Long githubAppId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "account_login", nullable = false, length = 255)
    private String accountLogin;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType;

    @Column(name = "repository_selection", nullable = false, length = 32)
    @Builder.Default
    private String repositorySelection = "ALL";

    @Column(name = "claimed_by_user_id")
    private UUID claimedByUserId;

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (repositorySelection == null) {
            repositorySelection = "ALL";
        }
        enforceScopeIntegrity();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
        enforceScopeIntegrity();
    }

    private void enforceScopeIntegrity() {
        boolean isUnclaimedStatus = "DISCOVERED".equalsIgnoreCase(status)
                || "VALIDATING".equalsIgnoreCase(status)
                || "CLAIMABLE".equalsIgnoreCase(status)
                || "QUARANTINED".equalsIgnoreCase(status);

        if (isUnclaimedStatus && workspaceId == null) {
            return;
        }

        if (organizationId != null) {
            scope = "ORGANIZATION";
            workspaceId = organizationId;
        } else {
            scope = "PERSONAL";
            organizationId = null;
        }
    }
}
