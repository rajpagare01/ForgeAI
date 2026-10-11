package com.forgeai.sourcecontrol;

import com.forgeai.sourcecontrol.domain.model.PullRequest;
import com.forgeai.sourcecontrol.domain.model.PullRequestStatus;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.repository.PullRequestRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import com.forgeai.sourcecontrol.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.sourcecontrol.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PullRequestIngestionIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitHubSignatureVerifier signatureVerifier;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("PullRequestIngestionIT: Ingesting a pull_request.opened webhook updates PR tables and creates an outbox event")
    void ingestingPROpenedUpdatesTablesAndOutbox() throws Exception {
        String deliveryId = "pr-deliv-" + UUID.randomUUID();
        long githubRepoId = 123456L;
        int prNumber = 42;
        String prTitle = "Feature: Implement OAuth2 login";
        String prBody = "Implements authentication flow TASK-42";
        String sourceBranch = "feat/oauth2-login";
        String targetBranch = "main";

        String payload = """
            {
                "action": "opened",
                "repository": {
                    "id": %d,
                    "name": "forgeai-backend",
                    "full_name": "forgeai/forgeai-backend",
                    "default_branch": "main",
                    "private": true
                },
                "pull_request": {
                    "id": 987654,
                    "number": %d,
                    "title": "%s",
                    "body": "%s",
                    "head": {"ref": "%s"},
                    "base": {"ref": "%s"},
                    "user": {"login": "alice_dev"},
                    "merged": false
                }
            }
            """.formatted(githubRepoId, prNumber, prTitle, prBody, sourceBranch, targetBranch);

        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String signature = signatureVerifier.computeSignature(payloadBytes);

        mockMvc.perform(post("/api/v1/webhooks/github")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "pull_request")
                        .header("X-Hub-Signature-256", signature)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"));

        // 1. Verify Repository was persisted
        Optional<Repository> repoOpt = repositoryRepository.findByGithubRepoId(githubRepoId);
        assertThat(repoOpt).isPresent();
        Repository repository = repoOpt.get();
        assertThat(repository.getFullName()).isEqualTo("forgeai/forgeai-backend");

        // 2. Verify Pull Request was persisted
        Optional<PullRequest> prOpt = pullRequestRepository.findByRepositoryIdAndNumber(repository.getId(), prNumber);
        assertThat(prOpt).isPresent();
        PullRequest pr = prOpt.get();
        assertThat(pr.getTitle()).isEqualTo(prTitle);
        assertThat(pr.getBody()).isEqualTo(prBody);
        assertThat(pr.getSourceBranch()).isEqualTo(sourceBranch);
        assertThat(pr.getTargetBranch()).isEqualTo(targetBranch);
        assertThat(pr.getStatus()).isEqualTo(PullRequestStatus.OPEN);
        assertThat(pr.getAuthorGithubId()).isEqualTo("alice_dev");

        // 3. Verify Outbox event was generated
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        boolean foundPROpenedEvent = outboxEvents.stream().anyMatch(e ->
                "PULL_REQUEST_OPENED".equals(e.getEventType()) &&
                e.getPayload().contains(prTitle) &&
                e.getPayload().contains(sourceBranch)
        );
        assertThat(foundPROpenedEvent).isTrue();
    }
}
