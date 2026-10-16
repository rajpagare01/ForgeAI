package com.forgeai.project.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_repository_links", uniqueConstraints = {
    @UniqueConstraint(name = "uq_project_repo_link", columnNames = {"organization_id", "project_id", "repository_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectRepositoryLinkJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "repository_name", length = 255)
    private String repositoryName;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
