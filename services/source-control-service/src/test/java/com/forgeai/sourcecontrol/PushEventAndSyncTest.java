package com.forgeai.sourcecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.domain.model.Repository;
import com.forgeai.sourcecontrol.domain.model.RepositorySyncStatus;
import com.forgeai.sourcecontrol.domain.repository.CommitRepository;
import com.forgeai.sourcecontrol.domain.repository.GitHubInstallationRepository;
import com.forgeai.sourcecontrol.domain.repository.RepositoryRepository;
import com.forgeai.sourcecontrol.domain.repository.WebhookDeliveryRepository;
import com.forgeai.sourcecontrol.infrastructure.github.GitHubSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.test.mock.mockito.MockBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushEventAndSyncTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GitHubSignatureVerifier signatureVerifier;

    @Autowired
    private WebhookDeliveryRepository webhookDeliveryRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private CommitRepository commitRepository;

    @Autowired
    private GitHubInstallationRepository installationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private com.forgeai.sourcecontrol.infrastructure.ai.AiServiceClient aiServiceClient;

    @Test
    @DisplayName("Push webhook processes commits, updates sync state, and maintains delivery idempotency")
    void pushWebhookProcessesCommitsAndUpdatesSyncState() throws Exception {
        when(aiServiceClient.syncPush(any(), any(), any(), any(), any())).thenReturn(true);
        when(aiServiceClient.syncPush(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(true);

        UUID orgId = UUID.randomUUID();
        Long githubRepoId = 12345678L;
        String repoName = "test-repo-" + UUID.randomUUID().toString().substring(0, 8);
        String fullName = "forgeai/" + repoName;
        String defaultBranch = "main";
        String afterSha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        String deliveryId = "deliv-push-" + UUID.randomUUID();

        // Seed repository belonging to orgId
        Repository repo = Repository.builder()
                .organizationId(orgId)
                .installationId(999L)
                .githubRepoId(githubRepoId)
                .name(repoName)
                .fullName(fullName)
                .defaultBranch(defaultBranch)
                .isPrivate(false)
                .syncStatus(RepositorySyncStatus.SYNCED)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        repo = repositoryRepository.save(repo);

        String pushPayload = String.format("""
            {
                "ref": "refs/heads/%s",
                "before": "0000000000000000000000000000000000000000",
                "after": "%s",
                "repository": {
                    "id": %d,
                    "name": "%s",
                    "full_name": "%s",
                    "default_branch": "%s",
                    "private": false
                },
                "installation": {
                    "id": 999
                },
                "commits": [
                    {
                        "id": "%s",
                        "message": "feat: add payment gateway",
                        "timestamp": "2026-10-15T10:00:00Z",
                        "author": {
                            "name": "Forge Developer",
                            "email": "dev@forgeai.com"
                        },
                        "added": ["src/PaymentGateway.java"],
                        "modified": [],
                        "removed": []
                    }
                ]
            }
            """, defaultBranch, afterSha, githubRepoId, repoName, fullName, defaultBranch, afterSha);

        byte[] payloadBytes = pushPayload.getBytes(StandardCharsets.UTF_8);
        String signatureHeader = signatureVerifier.computeSignature(payloadBytes);

        // 1. First delivery of push event -> HTTP 202 PROCESSED
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", signatureHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.idempotentHit").value(false));

        // Verify repository synchronization state updated
        Repository updatedRepo = repositoryRepository.findByIdAndOrganizationId(repo.getId(), orgId).orElseThrow();
        assertThat(updatedRepo.getLatestObservedSha()).isEqualTo(afterSha);
        assertThat(updatedRepo.getLatestIndexedSha()).isEqualTo(afterSha);
        assertThat(updatedRepo.getSyncStatus()).isEqualTo(RepositorySyncStatus.SYNCED);
        assertThat(updatedRepo.getLastSyncedAt()).isNotNull();

        // Verify commit was saved
        assertThat(commitRepository.existsByRepositoryIdAndSha(repo.getId(), afterSha)).isTrue();

        // 2. Duplicate delivery -> HTTP 202 IDEMPOTENT_IGNORED
        mockMvc.perform(post("/api/v1/webhooks/github")
                        .header("X-GitHub-Delivery", deliveryId)
                        .header("X-GitHub-Event", "push")
                        .header("X-Hub-Signature-256", signatureHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadBytes))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("IDEMPOTENT_IGNORED"))
                .andExpect(jsonPath("$.idempotentHit").value(true));

        // 3. Query GET /api/v1/repositories/{id}/sync-status with authorized org context
        mockMvc.perform(get("/api/v1/repositories/" + repo.getId() + "/sync-status")
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repo.getId().toString()))
                .andExpect(jsonPath("$.currentGithubHead").value(afterSha))
                .andExpect(jsonPath("$.indexedHead").value(afterSha))
                .andExpect(jsonPath("$.syncStatus").value("SYNCED"));

        // 4. Query GET /api/v1/repositories/{id}/sync-status with another tenant -> 404 (Isolation)
        UUID otherOrgId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/repositories/" + repo.getId() + "/sync-status")
                        .header("X-Organization-Id", otherOrgId.toString()))
                .andExpect(status().isNotFound());

        // 5. Query POST /api/v1/repositories/{id}/reconcile
        mockMvc.perform(post("/api/v1/repositories/" + repo.getId() + "/reconcile")
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoryId").value(repo.getId().toString()))
                .andExpect(jsonPath("$.syncStatus").value("SYNCED"));
    }
}
