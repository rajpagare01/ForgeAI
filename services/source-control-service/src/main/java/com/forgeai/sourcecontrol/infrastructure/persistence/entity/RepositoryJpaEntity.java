package com.forgeai.sourcecontrol.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repositories", indexes = {
        @Index(name = "idx_repo_org", columnList = "organization_id"),
        @Index(name = "idx_repo_workspace", columnList = "workspace_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryJpaEntity {

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

    @Column(name = "github_repo_id", nullable = false, unique = true)
    private Long githubRepoId;


    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "default_branch", nullable = false, length = 100)
    @Builder.Default
    private String defaultBranch = "main";

    @Column(name = "is_private", nullable = false)
    @Builder.Default
    private Boolean isPrivate = true;

    @Column(name = "latest_observed_sha", length = 64)
    private String latestObservedSha;

    @Column(name = "latest_indexed_sha", length = 64)
    private String latestIndexedSha;

    @Column(name = "sync_status", nullable = false, length = 32)
    @Builder.Default
    private String syncStatus = "SYNCED";

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "last_delivery_id", length = 128)
    private String lastDeliveryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        enforceScopeIntegrity();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
        enforceScopeIntegrity();
    }

    private void enforceScopeIntegrity() {
        if (organizationId != null) {
            scope = "ORGANIZATION";
            workspaceId = organizationId;
        } else {
            scope = "PERSONAL";
            organizationId = null;
        }
    }
}
