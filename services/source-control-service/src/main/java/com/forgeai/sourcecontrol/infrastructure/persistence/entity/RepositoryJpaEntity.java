package com.forgeai.sourcecontrol.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "repositories", indexes = {
        @Index(name = "idx_repo_org", columnList = "organization_id")
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

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

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

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
