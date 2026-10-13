package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.domain.event.RepositoryConnectedEvent;
import com.forgeai.sourcecontrol.domain.exception.DuplicateResourceException;
import com.forgeai.sourcecontrol.domain.exception.ResourceNotFoundException;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryApplicationService {

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final com.forgeai.sourcecontrol.domain.repository.PullRequestRepository pullRequestRepository;
    private final OutboxService outboxService;

    @Transactional(readOnly = true)
    public List<com.forgeai.sourcecontrol.domain.model.PullRequest> listPullRequests(UUID organizationId, UUID repositoryId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        if (repositoryId != null) {
            getRepository(organizationId, repositoryId);
            return pullRequestRepository.findAllByRepositoryId(repositoryId);
        }
        return pullRequestRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public List<Repository> listRepositories(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        return repositoryRepository.findAllByOrganizationId(organizationId);
    }

    @Transactional(readOnly = true)
    public Repository getRepository(UUID organizationId, UUID repositoryId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        return repositoryRepository.findByIdAndOrganizationId(repositoryId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found: " + repositoryId));
    }

    @Transactional
    public Repository connectRepository(UUID organizationId, Long githubRepoId, Long installationId, String name, String fullName, String defaultBranch, Boolean isPrivate) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        if (repositoryRepository.findByGithubRepoId(githubRepoId).isPresent()) {
            throw new DuplicateResourceException("Repository with GitHub ID " + githubRepoId + " already connected");
        }

        // Ensure installation exists or create mock installation
        Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
        if (instOpt.isEmpty()) {
            GitHubInstallation inst = GitHubInstallation.builder()
                    .organizationId(organizationId)
                    .installationId(installationId)
                    .accountLogin(fullName.contains("/") ? fullName.split("/")[0] : "forgeai")
                    .accountType("Organization")
                    .status(InstallationStatus.ACTIVE)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            installationRepository.save(inst);
        }

        Repository repo = Repository.builder()
                .organizationId(organizationId)
                .installationId(installationId)
                .githubRepoId(githubRepoId)
                .name(name)
                .fullName(fullName)
                .defaultBranch(defaultBranch != null ? defaultBranch : "main")
                .isPrivate(isPrivate != null ? isPrivate : true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        Repository saved = repositoryRepository.save(repo);

        RepositoryConnectedEvent event = RepositoryConnectedEvent.builder()
                .repositoryId(saved.getId())
                .organizationId(organizationId)
                .githubRepoId(saved.getGithubRepoId())
                .fullName(saved.getFullName())
                .defaultBranch(saved.getDefaultBranch())
                .timestamp(Instant.now())
                .build();

        outboxService.publish(event, "manual-connect-" + saved.getId(), "Repository", saved.getId().toString());
        log.info("Connected repository: id={}, fullName={}, orgId={}", saved.getId(), saved.getFullName(), organizationId);
        return saved;
    }
}
