package com.forgeai.sourcecontrol.application.handler;

import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Concurrency-safe repository and installation resolution shared by webhook event handlers.
 */
@Slf4j
final class WebhookRepositoryResolver {

    static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final int MAX_MAP_ENTRIES = 10_000;
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private WebhookRepositoryResolver() {
    }

    static Repository resolveOrCreate(
            RepositoryRepository repositoryRepository,
            GitHubInstallationRepository installationRepository,
            PlatformTransactionManager transactionManager,
            Long githubRepoId,
            Long installationId,
            String repoName,
            String fullName,
            String defaultBranch,
            boolean isPrivate,
            String eventLabel) {

        Optional<Repository> existing = repositoryRepository.findByGithubRepoId(githubRepoId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID orgId = null;
        UUID wsId = null;
        String scope = "ORGANIZATION";
        if (installationId != null && installationId > 0) {
            Optional<GitHubInstallation> instOpt = installationRepository.findByInstallationId(installationId);
            if (instOpt.isPresent()) {
                GitHubInstallation inst = instOpt.get();
                if (!QUARANTINE_ORG_ID.equals(inst.getOrganizationId())) {
                    if ("PERSONAL".equalsIgnoreCase(inst.getScope()) || (inst.getOrganizationId() == null && inst.getWorkspaceId() != null)) {
                        wsId = inst.getWorkspaceId();
                        orgId = null;
                        scope = "PERSONAL";
                    } else if (inst.getOrganizationId() != null) {
                        orgId = inst.getOrganizationId();
                        wsId = inst.getWorkspaceId() != null ? inst.getWorkspaceId() : orgId;
                        scope = "ORGANIZATION";
                    }
                }
            }
        }

        if (orgId == null && wsId == null) {
            log.warn("{} received for unmapped repository/installation {} (repo: {}). Quarantining under tenant {}.",
                    eventLabel, installationId, fullName, QUARANTINE_ORG_ID);
            orgId = QUARANTINE_ORG_ID;
            wsId = QUARANTINE_ORG_ID;
            long effectiveInstId = (installationId != null && installationId > 0) ? installationId : 0L;
            ensureInstallationExists(installationRepository, transactionManager, effectiveInstId, fullName);
            installationId = effectiveInstId;
        }

        final UUID finalOrgId = orgId;
        final UUID finalWsId = wsId;
        final String finalScope = scope;
        final Long finalInstId = installationId;

        if (LOCKS.size() > MAX_MAP_ENTRIES) {
            LOCKS.clear();
        }
        synchronized (LOCKS.computeIfAbsent("repo:" + githubRepoId, k -> new Object())) {
            return repositoryRepository.findByGithubRepoId(githubRepoId).orElseGet(() -> {
                Repository repo = Repository.builder()
                        .organizationId(finalOrgId)
                        .workspaceId(finalWsId)
                        .scope(finalScope)
                        .installationId(finalInstId)
                        .githubRepoId(githubRepoId)
                        .name(repoName)
                        .fullName(fullName)
                        .defaultBranch(defaultBranch)
                        .isPrivate(isPrivate)
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                try {
                    return saveInNewTxIfAvailable(transactionManager, () -> repositoryRepository.save(repo));
                } catch (DataIntegrityViolationException ex) {
                    return repositoryRepository.findByGithubRepoId(githubRepoId).orElseThrow(() -> ex);
                }
            });
        }
    }

    private static void ensureInstallationExists(
            GitHubInstallationRepository installationRepository,
            PlatformTransactionManager transactionManager,
            long effectiveInstId,
            String fullName) {

        if (LOCKS.size() > MAX_MAP_ENTRIES) {
            LOCKS.clear();
        }
        synchronized (LOCKS.computeIfAbsent("inst:" + effectiveInstId, k -> new Object())) {
            if (installationRepository.findByInstallationId(effectiveInstId).isPresent()) {
                return;
            }
            String accountLogin = (fullName != null && fullName.contains("/")) ? fullName.split("/")[0] : "quarantine";
            GitHubInstallation inst = GitHubInstallation.builder()
                    .organizationId(QUARANTINE_ORG_ID)
                    .workspaceId(QUARANTINE_ORG_ID)
                    .scope("ORGANIZATION")
                    .installationId(effectiveInstId)
                    .accountLogin(accountLogin)
                    .accountType("Organization")
                    .status(InstallationStatus.SUSPENDED)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            try {
                saveInNewTxIfAvailable(transactionManager, () -> installationRepository.save(inst));
            } catch (DataIntegrityViolationException ex) {
                installationRepository.findByInstallationId(effectiveInstId).orElseThrow(() -> ex);
            }
        }
    }

    private static <T> T saveInNewTxIfAvailable(PlatformTransactionManager transactionManager, Supplier<T> action) {
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> action.get());
        }
        return action.get();
    }
}

