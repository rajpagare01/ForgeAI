package com.forgeai.project.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class TraceabilitySnapshot {
    private UUID id;
    private UUID workspaceId;
    private UUID organizationId;
    private UUID projectId;
    private String snapshotName;
    private Integer prNumber;
    private String commitSha;
    private UUID repositoryId;
    private String createdBy;
    private String schemaVersion;
    private String snapshotData;
    private Instant createdAt;

    public TraceabilitySnapshot() {}

    public TraceabilitySnapshot(UUID id, UUID workspaceId, UUID organizationId, UUID projectId, String snapshotName,
                                Integer prNumber, String commitSha, UUID repositoryId,
                                String createdBy, String schemaVersion,
                                String snapshotData, Instant createdAt) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.organizationId = organizationId;
        this.projectId = projectId;
        this.snapshotName = snapshotName;
        this.prNumber = prNumber;
        this.commitSha = commitSha;
        this.repositoryId = repositoryId;
        this.createdBy = createdBy;
        this.schemaVersion = schemaVersion != null ? schemaVersion : "1.0";
        this.snapshotData = snapshotData;
        this.createdAt = createdAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID workspaceId) { this.workspaceId = workspaceId; }

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
        private UUID workspaceId;
        private UUID organizationId;
        private UUID projectId;
        private String snapshotName;
        private Integer prNumber;
        private String commitSha;
        private UUID repositoryId;
        private String createdBy;
        private String schemaVersion = "1.0";
        private String snapshotData;
        private Instant createdAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder workspaceId(UUID workspaceId) { this.workspaceId = workspaceId; return this; }
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

        public TraceabilitySnapshot build() {
            return new TraceabilitySnapshot(id, workspaceId, organizationId, projectId, snapshotName, prNumber, commitSha, repositoryId, createdBy, schemaVersion, snapshotData, createdAt);
        }
    }
}
