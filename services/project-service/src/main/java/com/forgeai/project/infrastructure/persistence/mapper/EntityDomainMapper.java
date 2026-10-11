package com.forgeai.project.infrastructure.persistence.mapper;

import com.forgeai.project.domain.model.*;
import com.forgeai.project.infrastructure.persistence.entity.*;

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

    public static Project toDomain(ProjectJpaEntity entity) {
        if (entity == null) return null;
        return Project.builder()
                .id(entity.getId())
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
                .organizationId(domain.getOrganizationId())
                .taskId(domain.getTaskId())
                .linkType(domain.getLinkType())
                .externalRef(domain.getExternalRef())
                .repositoryId(domain.getRepositoryId())
                .createdBy(domain.getCreatedBy() != null ? domain.getCreatedBy() : "USER")
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
