package com.forgeai.sourcecontrol.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class AiServiceClient {

    private final String aiServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiServiceClient(
            @Value("${forgeai.services.ai-service.url:http://ai-service:8084}") String aiServiceUrl,
            ObjectMapper objectMapper) {
        this.aiServiceUrl = aiServiceUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record ChangedFilePayload(String file_path, String status, String content) {}

    public record PushSyncPayload(
            UUID repository_id,
            String commit_sha,
            String ref,
            List<ChangedFilePayload> files
    ) {}

    /**
     * Sends an incremental push synchronization request to AI Intelligence Service.
     * Enforces tenant isolation by attaching the organization_id in the X-Org-Id header.
     */
    public boolean syncPush(UUID organizationId, UUID repositoryId, String commitSha, String ref, List<ChangedFilePayload> files) {
        if (organizationId == null || repositoryId == null || commitSha == null) {
            log.warn("[AI Client] Skipping push sync due to missing parameters: org={}, repo={}, sha={}", organizationId, repositoryId, commitSha);
            return false;
        }

        try {
            PushSyncPayload payload = new PushSyncPayload(repositoryId, commitSha, ref, files);
            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiServiceUrl + "/api/v1/ai/sync/push"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("X-Org-Id", organizationId.toString())
                    .header("X-Workspace-Id", organizationId.toString())
                    .header("X-ForgeAI-Internal", "attested")
                    .header("X-Trace-Id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[AI Client] Incremental sync succeeded for repo {} sha {} (HTTP {})", repositoryId, commitSha, response.statusCode());
                return true;
            } else {
                log.error("[AI Client] Incremental sync failed for repo {} sha {} (HTTP {}): {}", repositoryId, commitSha, response.statusCode(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.error("[AI Client] Exception sending push sync to AI Service: {}", e.getMessage(), e);
            return false;
        }
    }
}
