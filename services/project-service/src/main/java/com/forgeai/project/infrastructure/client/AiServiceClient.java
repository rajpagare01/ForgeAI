package com.forgeai.project.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.infrastructure.client.dto.RemoteAiAnalysisDto;
import com.forgeai.project.infrastructure.client.dto.RemoteEngineeringActionDto;
import com.forgeai.project.infrastructure.client.dto.RemoteMergeReadinessDto;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class AiServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiServiceClient(
            @Value("${services.ai.url:http://localhost:8084}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public List<RemoteAiAnalysisDto> getAnalysesForPullRequest(UUID organizationId, UUID repositoryId, int prNumber) {
        if (organizationId == null || repositoryId == null) {
            return Collections.emptyList();
        }
        try {
            String entityId = repositoryId + "#" + prNumber;
            String encodedEntityId = URLEncoder.encode(entityId, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest("/api/v1/ai/analyses/PULL_REQUEST/" + encodedEntityId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteAiAnalysisDto>>() {});
            }
            log.warn("AI-service getAnalysesForPullRequest returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch analyses for repo {} PR {}: {}", repositoryId, prNumber, e.getMessage());
        }
        return Collections.emptyList();
    }

    public Optional<RemoteMergeReadinessDto> getMergeReadiness(UUID organizationId, UUID analysisId) {
        if (organizationId == null || analysisId == null) {
            return Optional.empty();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/ai/merge-readiness/" + analysisId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteMergeReadinessDto.class));
            }
            log.warn("AI-service getMergeReadiness returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch merge readiness for analysis {}: {}", analysisId, e.getMessage());
        }
        return Optional.empty();
    }

    public List<RemoteEngineeringActionDto> getEngineeringActions(UUID organizationId, UUID analysisId) {
        if (organizationId == null || analysisId == null) {
            return Collections.emptyList();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/ai/analyses/" + analysisId + "/actions", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteEngineeringActionDto>>() {});
            }
            log.warn("AI-service getEngineeringActions returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch engineering actions for analysis {}: {}", analysisId, e.getMessage());
        }
        return Collections.emptyList();
    }

    private HttpRequest buildRequest(String path, UUID organizationId) {
        String traceId = MDC.get("traceId");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-Org-Id", organizationId.toString())
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (traceId != null && !traceId.isBlank()) {
            builder.header("X-Correlation-Id", traceId);
        }
        return builder.build();
    }
}
