package com.forgeai.testing.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "test_cases", uniqueConstraints = {
        @UniqueConstraint(name = "uq_test_cases_suite_name_class", columnNames = {"suite_id", "name", "classname"})
}, indexes = {
        @Index(name = "idx_test_cases_req", columnList = "organization_id, requirement_key")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCaseJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "classname", length = 500)
    private String classname;

    @Column(name = "requirement_key", length = 32)
    private String requirementKey;

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
