package com.forgeai.sourcecontrol.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "github_installations", indexes = {
        @Index(name = "idx_gh_install_org", columnList = "organization_id"),
        @Index(name = "idx_gh_install_workspace", columnList = "workspace_id")
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


    @Column(name = "account_login", nullable = false, length = 255)
    private String accountLogin;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType;

    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (scope == null) {
            scope = organizationId != null ? "ORGANIZATION" : "PERSONAL";
        }
        if (workspaceId == null && organizationId != null) {
            workspaceId = organizationId;
        }
    }


    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
