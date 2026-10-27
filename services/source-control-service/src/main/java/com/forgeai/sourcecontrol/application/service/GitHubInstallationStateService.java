package com.forgeai.sourcecontrol.application.service;

import com.forgeai.sourcecontrol.infrastructure.github.GitHubAccessDeniedException;
import com.forgeai.sourcecontrol.infrastructure.metrics.SourceControlMetrics;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.GitHubInstallationStateJpaEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataGitHubInstallationStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Manages secure, single-use server-side state records for GitHub App installation journeys.
 * Prevents CSRF, callback replay attacks, and cross-tenant installation hijacking.
 */
@Slf4j
@Service
public class GitHubInstallationStateService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration STATE_TTL = Duration.ofMinutes(15);

    private final SpringDataGitHubInstallationStateRepository stateRepository;
    private final SourceControlMetrics metrics;

    @Autowired
    public GitHubInstallationStateService(
            SpringDataGitHubInstallationStateRepository stateRepository,
            @Autowired(required = false) SourceControlMetrics metrics) {
        this.stateRepository = stateRepository;
        this.metrics = metrics;
    }

    @Transactional
    public String createInstallationState(UUID userId, UUID workspaceId, UUID organizationId, String scope) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required to initiate GitHub App installation");
        }
        if (workspaceId == null) {
            throw new IllegalArgumentException("Workspace ID is required to initiate GitHub App installation");
        }

        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String stateToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        Instant now = Instant.now();
        Instant expiresAt = now.plus(STATE_TTL);

        GitHubInstallationStateJpaEntity entity = GitHubInstallationStateJpaEntity.builder()
                .state(stateToken)
                .userId(userId)
                .workspaceId(workspaceId)
                .organizationId(organizationId)
                .scope(scope != null ? scope : (organizationId != null ? "ORGANIZATION" : "PERSONAL"))
                .createdAt(now)
                .expiresAt(expiresAt)
                .build();

        stateRepository.save(entity);
        log.info("[GitHub State] Created installation onboarding state for user={}, workspace={}, scope={}",
                userId, workspaceId, entity.getScope());

        return stateToken;
    }

    @Transactional
    public GitHubInstallationStateJpaEntity validateAndConsumeState(
            String stateToken,
            UUID expectedUserId,
            UUID expectedWorkspaceId) {

        if (stateToken == null || stateToken.isBlank()) {
            throw new GitHubAccessDeniedException("Missing required GitHub installation state token.");
        }

        GitHubInstallationStateJpaEntity entity = stateRepository.findByState(stateToken)
                .orElseThrow(() -> new GitHubAccessDeniedException(
                        "Invalid or unknown GitHub installation state token. Flow was not initiated by ForgeAI."));

        if (entity.isConsumed()) {
            log.warn("[Security] Replay attack detected for state token! consumedAt={}", entity.getUsedAt());
            if (metrics != null) {
                metrics.recordInstallationCallbackReplay();
                metrics.recordInstallationFailure();
            }
            throw new GitHubAccessDeniedException(
                    "GitHub installation state token has already been consumed (replay rejected).");
        }

        if (entity.isExpired()) {
            log.warn("[Security] Expired installation state token presented: expiredAt={}", entity.getExpiresAt());
            if (metrics != null) {
                metrics.recordInstallationFailure();
            }
            throw new GitHubAccessDeniedException(
                    "GitHub installation state token has expired. Please initiate connection again.");
        }

        if (expectedUserId != null && !entity.getUserId().equals(expectedUserId)) {
            log.warn("[Security] State token ownership mismatch: stateOwner={}, callerUser={}",
                    entity.getUserId(), expectedUserId);
            if (metrics != null) {
                metrics.recordInstallationOwnershipRejection();
                metrics.recordInstallationFailure();
            }
            throw new GitHubAccessDeniedException(
                    "GitHub installation state token belongs to a different authenticated user.");
        }

        if (expectedWorkspaceId != null && !entity.getWorkspaceId().equals(expectedWorkspaceId)) {
            log.warn("[Security] State token workspace mismatch: stateWorkspace={}, requestWorkspace={}",
                    entity.getWorkspaceId(), expectedWorkspaceId);
            if (metrics != null) {
                metrics.recordInstallationOwnershipRejection();
                metrics.recordInstallationFailure();
            }
            throw new GitHubAccessDeniedException(
                    "GitHub installation state token does not match the active workspace context.");
        }

        // Consume atomically (single-use)
        entity.setUsedAt(Instant.now());
        GitHubInstallationStateJpaEntity saved = stateRepository.save(entity);
        log.info("[GitHub State] Consumed installation onboarding state for user={}, workspace={}",
                saved.getUserId(), saved.getWorkspaceId());

        return saved;
    }
}
