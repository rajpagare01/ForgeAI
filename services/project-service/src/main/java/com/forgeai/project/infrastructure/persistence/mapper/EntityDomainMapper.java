package com.forgeai.project.infrastructure.persistence.mapper;

import com.forgeai.project.domain.model.*;
import com.forgeai.project.infrastructure.persistence.entity.*;

import java.time.Instant;

public final class EntityDomainMapper {

    private EntityDomainMapper() {}

    public static UserDirectory toDomain(UserDirectoryJpaEntity entity) {
        if (entity == null) return null;
        return UserDirectory.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .firstName(entity.getFirstName())
                .lastName(entity.getLastName())
                .status(entity.getStatus())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static UserDirectoryJpaEntity toEntity(UserDirectory domain) {
        if (domain == null) return null;
        return UserDirectoryJpaEntity.builder()
                .id(domain.getId())
                .email(domain.getEmail())
                .firstName(domain.getFirstName())
                .lastName(domain.getLastName())
                .status(domain.getStatus() != null ? domain.getStatus() : "ACTIVE")
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Workspace toDomain(WorkspaceJpaEntity entity) {
        if (entity == null) return null;
        return Workspace.builder()
                .id(entity.getId())
                .type(entity.getType())
                .ownerUserId(entity.getOwnerUserId())
                .organizationId(entity.getOrganizationId())
                .name(entity.getName())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static WorkspaceJpaEntity toEntity(Workspace domain) {
        if (domain == null) return null;
        return WorkspaceJpaEntity.builder()
                .id(domain.getId())
                .type(domain.getType())
                .ownerUserId(domain.getOwnerUserId())
                .organizationId(domain.getOrganizationId())
                .name(domain.getName())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Project toDomain(ProjectJpaEntity entity) {
        if (entity == null) return null;
        return Project.builder()
                .id(entity.getId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .name(entity.getName())
                .key(entity.getKey())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static ProjectJpaEntity toEntity(Project domain) {
        if (domain == null) return null;
        return ProjectJpaEntity.builder()
                .id(domain.getId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .name(domain.getName())
                .key(domain.getKey())
                .description(domain.getDescription())
                .status(domain.getStatus() != null ? domain.getStatus() : ProjectStatus.ACTIVE)
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Requirement toDomain(RequirementJpaEntity entity) {
        if (entity == null) return null;
        return Requirement.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .identifier(entity.getIdentifier())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static RequirementJpaEntity toEntity(Requirement domain) {
        if (domain == null) return null;
        return RequirementJpaEntity.builder()
                .id(domain.getId())
                .projectId(domain.getProjectId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .identifier(domain.getIdentifier())
                .title(domain.getTitle())
                .description(domain.getDescription())
                .status(domain.getStatus() != null ? domain.getStatus() : RequirementStatus.DRAFT)
                .version(domain.getVersion())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static Task toDomain(TaskJpaEntity entity) {
        if (entity == null) return null;
        return Task.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .requirementId(entity.getRequirementId())
                .taskKey(entity.getTaskKey())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .priority(entity.getPriority())
                .assigneeId(entity.getAssigneeId())
                .reporterId(entity.getReporterId())
                .version(entity.getVersion())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static TaskJpaEntity toEntity(Task domain) {
        if (domain == null) return null;
        return TaskJpaEntity.builder()
                .id(domain.getId())
                .projectId(domain.getProjectId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .requirementId(domain.getRequirementId())
                .taskKey(domain.getTaskKey())
                .title(domain.getTitle())
                .description(domain.getDescription())
                .status(domain.getStatus() != null ? domain.getStatus() : TaskStatus.TODO)
                .priority(domain.getPriority() != null ? domain.getPriority() : TaskPriority.MEDIUM)
                .assigneeId(domain.getAssigneeId())
                .reporterId(domain.getReporterId())
                .version(domain.getVersion())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public static CodeLink toDomain(CodeLinkJpaEntity entity) {
        if (entity == null) return null;
        return CodeLink.builder()
                .id(entity.getId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .taskId(entity.getTaskId())
                .linkType(entity.getLinkType())
                .externalRef(entity.getExternalRef())
                .repositoryId(entity.getRepositoryId())
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static CodeLinkJpaEntity toEntity(CodeLink domain) {
        if (domain == null) return null;
        return CodeLinkJpaEntity.builder()
                .id(domain.getId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .taskId(domain.getTaskId())
                .linkType(domain.getLinkType())
                .externalRef(domain.getExternalRef())
                .repositoryId(domain.getRepositoryId())
                .createdBy(domain.getCreatedBy() != null ? domain.getCreatedBy() : "USER")
                .createdAt(domain.getCreatedAt())
                .build();
    }

    public static TraceabilityLink toDomain(TraceabilityLinkJpaEntity entity) {
        if (entity == null) return null;
        return TraceabilityLink.builder()
                .id(entity.getId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .projectId(entity.getProjectId())
                .sourceType(TraceabilityType.valueOf(entity.getSourceType()))
                .sourceId(entity.getSourceId())
                .targetType(TraceabilityType.valueOf(entity.getTargetType()))
                .targetId(entity.getTargetId())
                .repositoryId(entity.getRepositoryId())
                .externalReference(entity.getExternalReference())
                .linkOrigin(LinkOrigin.valueOf(entity.getLinkOrigin()))
                .confidence(ConfidenceLevel.valueOf(entity.getConfidence()))
                .evidence(entity.getEvidence())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static TraceabilityLinkJpaEntity toEntity(TraceabilityLink domain) {
        if (domain == null) return null;
        Instant now = Instant.now();
        return TraceabilityLinkJpaEntity.builder()
                .id(domain.getId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .projectId(domain.getProjectId())
                .sourceType(domain.getSourceType() != null ? domain.getSourceType().name() : TraceabilityType.REQUIREMENT.name())
                .sourceId(domain.getSourceId())
                .targetType(domain.getTargetType() != null ? domain.getTargetType().name() : TraceabilityType.TASK.name())
                .targetId(domain.getTargetId())
                .repositoryId(domain.getRepositoryId())
                .externalReference(domain.getExternalReference())
                .linkOrigin(domain.getLinkOrigin() != null ? domain.getLinkOrigin().name() : LinkOrigin.DERIVED.name())
                .confidence(domain.getConfidence() != null ? domain.getConfidence().name() : ConfidenceLevel.HIGH.name())
                .evidence(domain.getEvidence())
                .active(domain.isActive())
                .createdAt(domain.getCreatedAt() != null ? domain.getCreatedAt() : now)
                .updatedAt(domain.getUpdatedAt() != null ? domain.getUpdatedAt() : now)
                .build();
    }

    public static ProjectRepositoryLink toDomain(ProjectRepositoryLinkJpaEntity entity) {
        if (entity == null) return null;
        return ProjectRepositoryLink.builder()
                .id(entity.getId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .projectId(entity.getProjectId())
                .repositoryId(entity.getRepositoryId())
                .repositoryName(entity.getRepositoryName())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static ProjectRepositoryLinkJpaEntity toEntity(ProjectRepositoryLink domain) {
        if (domain == null) return null;
        return ProjectRepositoryLinkJpaEntity.builder()
                .id(domain.getId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .projectId(domain.getProjectId())
                .repositoryId(domain.getRepositoryId())
                .repositoryName(domain.getRepositoryName())
                .createdAt(domain.getCreatedAt())
                .build();
    }

    public static TraceabilitySnapshot toDomain(TraceabilitySnapshotJpaEntity entity) {
        if (entity == null) return null;
        return TraceabilitySnapshot.builder()
                .id(entity.getId())
                .workspaceId(entity.getWorkspaceId())
                .organizationId(entity.getOrganizationId())
                .projectId(entity.getProjectId())
                .snapshotName(entity.getSnapshotName())
                .prNumber(entity.getPrNumber())
                .commitSha(entity.getCommitSha())
                .repositoryId(entity.getRepositoryId())
                .createdBy(entity.getCreatedBy())
                .schemaVersion(entity.getSchemaVersion())
                .snapshotData(entity.getSnapshotData())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public static TraceabilitySnapshotJpaEntity toEntity(TraceabilitySnapshot domain) {
        if (domain == null) return null;
        return TraceabilitySnapshotJpaEntity.builder()
                .id(domain.getId())
                .workspaceId(domain.getWorkspaceId())
                .organizationId(domain.getOrganizationId())
                .projectId(domain.getProjectId())
                .snapshotName(domain.getSnapshotName())
                .prNumber(domain.getPrNumber())
                .commitSha(domain.getCommitSha())
                .repositoryId(domain.getRepositoryId())
                .createdBy(domain.getCreatedBy())
                .schemaVersion(domain.getSchemaVersion() != null ? domain.getSchemaVersion() : "1.0")
                .snapshotData(domain.getSnapshotData())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
