package com.forgeai.project.infrastructure.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "traceability_snapshots")
public class TraceabilitySnapshotJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "snapshot_name", nullable = false, length = 255, updatable = false)
    private String snapshotName;

    @Column(name = "pr_number", updatable = false)
    private Integer prNumber;

    @Column(name = "commit_sha", length = 64, updatable = false)
    private String commitSha;

    @Column(name = "repository_id", updatable = false)
    private UUID repositoryId;

    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    @Column(name = "schema_version", nullable = false, length = 32, updatable = false)
    private String schemaVersion = "1.0";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot_data", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String snapshotData;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TraceabilitySnapshotJpaEntity() {}

    public TraceabilitySnapshotJpaEntity(UUID id, UUID organizationId, UUID projectId, String snapshotName,
                                        Integer prNumber, String commitSha, UUID repositoryId,
                                        String createdBy, String schemaVersion,
                                        String snapshotData, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.snapshotName = snapshotName;
        this.prNumber = prNumber;
        this.commitSha = commitSha;
        this.repositoryId = repositoryId;
        this.createdBy = createdBy;
        this.schemaVersion = schemaVersion != null ? schemaVersion : "1.0";
        this.snapshotData = snapshotData;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID organizationId) { this.organizationId = organizationId; }

    public UUID getProjectId() { return projectId; }
    public void setProjectId(UUID projectId) { this.projectId = projectId; }

    public String getSnapshotName() { return snapshotName; }
    public void setSnapshotName(String snapshotName) { this.snapshotName = snapshotName; }

    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }

    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }

    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getSnapshotData() { return snapshotData; }
    public void setSnapshotData(String snapshotData) { this.snapshotData = snapshotData; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public static class Builder {
        private UUID id;
        private UUID organizationId;
        private UUID projectId;
        private String snapshotName;
        private Integer prNumber;
        private String commitSha;
        private UUID repositoryId;
        private String createdBy;
        private String schemaVersion = "1.0";
        private String snapshotData;
        private Instant createdAt = Instant.now();

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder organizationId(UUID organizationId) { this.organizationId = organizationId; return this; }
        public Builder projectId(UUID projectId) { this.projectId = projectId; return this; }
        public Builder snapshotName(String snapshotName) { this.snapshotName = snapshotName; return this; }
        public Builder prNumber(Integer prNumber) { this.prNumber = prNumber; return this; }
        public Builder commitSha(String commitSha) { this.commitSha = commitSha; return this; }
        public Builder repositoryId(UUID repositoryId) { this.repositoryId = repositoryId; return this; }
        public Builder createdBy(String createdBy) { this.createdBy = createdBy; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder snapshotData(String snapshotData) { this.snapshotData = snapshotData; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public TraceabilitySnapshotJpaEntity build() {
            return new TraceabilitySnapshotJpaEntity(id, organizationId, projectId, snapshotName, prNumber, commitSha, repositoryId, createdBy, schemaVersion, snapshotData, createdAt);
        }
    }
}
