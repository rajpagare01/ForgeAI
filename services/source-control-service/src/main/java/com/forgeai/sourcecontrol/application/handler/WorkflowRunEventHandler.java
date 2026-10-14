package com.forgeai.sourcecontrol.application.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.forgeai.sourcecontrol.domain.event.WorkflowRunReceivedEvent;
import com.forgeai.sourcecontrol.domain.model.GitHubInstallation;
import com.forgeai.sourcecontrol.domain.model.InstallationStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles GitHub {@code workflow_run} webhook events within the Source Control thin-ingest layer.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Resolve the GitHub repository ID to a ForgeAI {@link Repository} record.</li>
 *   <li>Derive the {@code organizationId} from the trusted repository ↔ installation mapping —
 *       never from the webhook JSON.</li>
 *   <li>Publish a {@link WorkflowRunReceivedEvent} to the outbox for durable downstream
 *       delivery to the Testing Service.</li>
 * </ol>
 *
 * <p>This handler does NOT perform synchronous cross-service calls. It remains thin:
 * verify signature (done upstream) → parse → publish → return.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRunEventHandler {

    private static final UUID QUARANTINE_ORG_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final RepositoryRepository repositoryRepository;
    private final GitHubInstallationRepository installationRepository;
    private final OutboxService outboxService;

    @Transactional
    public void handle(JsonNode rootNode, String deliveryId) {
        JsonNode workflowRunNode = rootNode.path("workflow_run");
        JsonNode repoNode = rootNode.path("repository");

        if (workflowRunNode.isMissingNode() || workflowRunNode.isNull()) {
            log.warn("[{}] workflow_run event missing workflow_run node — skipping", deliveryId);
            return;
        }
        if (repoNode.isMissingNode() || repoNode.isNull()) {
            log.warn("[{}] workflow_run event missing repository node — skipping", deliveryId);
            return;
        }

        long githubRepoId = repoNode.path("id").asLong(0L);
        long githubWorkflowRunId = workflowRunNode.path("id").asLong(0L);

        if (githubWorkflowRunId == 0L) {
            log.warn("[{}] workflow_run.id is missing or zero — skipping", deliveryId);
            return;
        }

        // Resolve ForgeAI repository and organisation from trusted mapping
        Optional<Repository> repoOpt = repositoryRepository.findByGithubRepoId(githubRepoId);
        if (repoOpt.isEmpty()) {
            log.warn("[{}] No ForgeAI repository found for GitHub repo id={} — workflow_run {} skipped",
                    deliveryId, githubRepoId, githubWorkflowRunId);
            return;
        }
        Repository repository = repoOpt.get();
        UUID organizationId = repository.getOrganizationId();

        if (QUARANTINE_ORG_ID.equals(organizationId)) {
            log.warn("[{}] Repository {} is quarantined — discarding workflow_run {}",
                    deliveryId, repository.getId(), githubWorkflowRunId);
            return;
        }

        // Extract safe, non-sensitive workflow metadata
        String workflowName = workflowRunNode.path("name").asText("unknown");
        int runNumber = workflowRunNode.path("run_number").asInt(0);
        String status = workflowRunNode.path("status").asText("unknown");
        String conclusion = workflowRunNode.path("conclusion").isNull() ? null
                : workflowRunNode.path("conclusion").asText(null);
        String headBranch = workflowRunNode.path("head_branch").asText(null);
        String headSha = workflowRunNode.path("head_sha").asText(null);
        String triggerEvent = workflowRunNode.path("event").asText(null);
        String htmlUrl = workflowRunNode.path("html_url").asText(null);

        Instant runStartedAt = parseInstant(workflowRunNode.path("run_started_at").asText(null));
        Instant completedAt = parseInstant(workflowRunNode.path("updated_at").asText(null));
        if (!"completed".equalsIgnoreCase(status)) {
            completedAt = null;
        }

        WorkflowRunReceivedEvent event = WorkflowRunReceivedEvent.builder()
                .repositoryId(repository.getId())
                .organizationId(organizationId)
                .workflowRunId(githubWorkflowRunId)
                .workflowName(workflowName)
                .runNumber(runNumber)
                .status(status)
                .conclusion(conclusion)
                .headBranch(headBranch)
                .headSha(headSha)
                .triggerEvent(triggerEvent)
                .runStartedAt(runStartedAt)
                .completedAt(completedAt)
                .htmlUrl(htmlUrl)
                .timestamp(Instant.now())
                .build();

        outboxService.publish(event, deliveryId, "WorkflowRun", String.valueOf(githubWorkflowRunId));

        log.info("[{}] workflow_run event published to outbox: repo={}, orgId={}, workflowRunId={}, status={}, conclusion={}",
                deliveryId, repository.getId(), organizationId, githubWorkflowRunId, status, conclusion);
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.debug("Could not parse workflow_run timestamp '{}': {}", value, e.getMessage());
            return null;
        }
    }
}
