package com.forgeai.sourcecontrol.infrastructure.persistence.mapper;

import com.forgeai.sourcecontrol.domain.model.*;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.*;
import org.springframework.stereotype.Component;

@Component
public class SourceControlEntityMapper {

    public GitHubInstallation toDomain(GitHubInstallationJpaEntity entity) {
        if (entity == null) return null;
        return GitHubInstallation.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .installationId(entity.getInstallationId())
                .accountLogin(entity.getAccountLogin())
                .accountType(entity.getAccountType())
                .status(entity.getStatus() != null ? InstallationStatus.valueOf(entity.getStatus()) : InstallationStatus.ACTIVE)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public GitHubInstallationJpaEntity toEntity(GitHubInstallation domain) {
        if (domain == null) return null;
        return GitHubInstallationJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .installationId(domain.getInstallationId())
                .accountLogin(domain.getAccountLogin())
                .accountType(domain.getAccountType())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "ACTIVE")
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public Repository toDomain(RepositoryJpaEntity entity) {
        if (entity == null) return null;
        return Repository.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .githubRepoId(entity.getGithubRepoId())
                .installationId(entity.getInstallationId())
                .name(entity.getName())
                .fullName(entity.getFullName())
                .defaultBranch(entity.getDefaultBranch())
                .isPrivate(entity.getIsPrivate())
                .latestObservedSha(entity.getLatestObservedSha())
                .latestIndexedSha(entity.getLatestIndexedSha())
                .syncStatus(entity.getSyncStatus() != null ? RepositorySyncStatus.valueOf(entity.getSyncStatus()) : RepositorySyncStatus.SYNCED)
                .lastSyncedAt(entity.getLastSyncedAt())
                .lastError(entity.getLastError())
                .lastDeliveryId(entity.getLastDeliveryId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public RepositoryJpaEntity toEntity(Repository domain) {
        if (domain == null) return null;
        return RepositoryJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .githubRepoId(domain.getGithubRepoId())
                .installationId(domain.getInstallationId())
                .name(domain.getName())
                .fullName(domain.getFullName())
                .defaultBranch(domain.getDefaultBranch())
                .isPrivate(domain.getIsPrivate())
                .latestObservedSha(domain.getLatestObservedSha())
                .latestIndexedSha(domain.getLatestIndexedSha())
                .syncStatus(domain.getSyncStatus() != null ? domain.getSyncStatus().name() : "SYNCED")
                .lastSyncedAt(domain.getLastSyncedAt())
                .lastError(domain.getLastError())
                .lastDeliveryId(domain.getLastDeliveryId())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public PullRequest toDomain(PullRequestJpaEntity entity) {
        if (entity == null) return null;
        return PullRequest.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .repositoryId(entity.getRepositoryId())
                .githubPrId(entity.getGithubPrId())
                .number(entity.getNumber())
                .title(entity.getTitle())
                .body(entity.getBody())
                .sourceBranch(entity.getSourceBranch())
                .targetBranch(entity.getTargetBranch())
                .status(entity.getStatus() != null ? PullRequestStatus.valueOf(entity.getStatus()) : PullRequestStatus.OPEN)
                .authorGithubId(entity.getAuthorGithubId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PullRequestJpaEntity toEntity(PullRequest domain) {
        if (domain == null) return null;
        return PullRequestJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .repositoryId(domain.getRepositoryId())
                .githubPrId(domain.getGithubPrId())
                .number(domain.getNumber())
                .title(domain.getTitle())
                .body(domain.getBody())
                .sourceBranch(domain.getSourceBranch())
                .targetBranch(domain.getTargetBranch())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "OPEN")
                .authorGithubId(domain.getAuthorGithubId())
                .createdAt(domain.getCreatedAt())
                .updatedAt(domain.getUpdatedAt())
                .build();
    }

    public Commit toDomain(CommitJpaEntity entity) {
        if (entity == null) return null;
        return Commit.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .repositoryId(entity.getRepositoryId())
                .sha(entity.getSha())
                .message(entity.getMessage())
                .authorEmail(entity.getAuthorEmail())
                .authorName(entity.getAuthorName())
                .committedAt(entity.getCommittedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public CommitJpaEntity toEntity(Commit domain) {
        if (domain == null) return null;
        return CommitJpaEntity.builder()
                .id(domain.getId())
                .organizationId(domain.getOrganizationId())
                .repositoryId(domain.getRepositoryId())
                .sha(domain.getSha())
                .message(domain.getMessage())
                .authorEmail(domain.getAuthorEmail())
                .authorName(domain.getAuthorName())
                .committedAt(domain.getCommittedAt())
                .createdAt(domain.getCreatedAt())
                .build();
    }

    public WebhookDelivery toDomain(WebhookDeliveryJpaEntity entity) {
        if (entity == null) return null;
        return WebhookDelivery.builder()
                .id(entity.getId())
                .deliveryId(entity.getDeliveryId())
                .eventType(entity.getEventType())
                .signature(entity.getSignature())
                .payload(entity.getPayload())
                .status(entity.getStatus() != null ? WebhookDeliveryStatus.valueOf(entity.getStatus()) : WebhookDeliveryStatus.RECEIVED)
                .processedAt(entity.getProcessedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public WebhookDeliveryJpaEntity toEntity(WebhookDelivery domain) {
        if (domain == null) return null;
        return WebhookDeliveryJpaEntity.builder()
                .id(domain.getId())
                .deliveryId(domain.getDeliveryId())
                .eventType(domain.getEventType())
                .signature(domain.getSignature())
                .payload(domain.getPayload())
                .status(domain.getStatus() != null ? domain.getStatus().name() : "RECEIVED")
                .processedAt(domain.getProcessedAt())
                .createdAt(domain.getCreatedAt())
                .build();
    }
}
