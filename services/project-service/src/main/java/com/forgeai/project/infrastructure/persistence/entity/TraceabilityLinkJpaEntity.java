package com.forgeai.project.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "traceability_links", uniqueConstraints = {
    @UniqueConstraint(name = "uq_trace_link", columnNames = {
        "organization_id", "project_id", "source_type", "source_id", "target_type", "target_id", "link_origin"
    })
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TraceabilityLinkJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "source_type", nullable = false, length = 64)
    private String sourceType;

    @Column(name = "source_id", nullable = false, length = 255)
    private String sourceId;

    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 255)
    private String targetId;

    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "external_reference", length = 500)
    private String externalReference;

    @Column(name = "link_origin", nullable = false, length = 64)
    private String linkOrigin;

    @Column(name = "confidence", nullable = false, length = 32)
    @Builder.Default
    private String confidence = "HIGH";

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
