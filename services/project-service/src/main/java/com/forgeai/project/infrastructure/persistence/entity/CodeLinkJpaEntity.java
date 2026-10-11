package com.forgeai.project.infrastructure.persistence.entity;

import com.forgeai.project.domain.model.CodeLinkType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "code_links", uniqueConstraints = {
    @UniqueConstraint(name = "uq_code_links_task_type_ref", columnNames = {"task_id", "link_type", "external_ref"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodeLinkJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 32)
    private CodeLinkType linkType;

    @Column(name = "external_ref", nullable = false, length = 500)
    private String externalRef;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "created_by", nullable = false, length = 32)
    @Builder.Default
    private String createdBy = "USER";

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
