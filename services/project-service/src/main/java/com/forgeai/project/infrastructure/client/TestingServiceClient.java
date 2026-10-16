package com.forgeai.project.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.infrastructure.client.dto.RemoteRequirementTestTraceDto;
import com.forgeai.project.infrastructure.client.dto.RemoteTestResultDto;
import com.forgeai.project.infrastructure.client.dto.RemoteTestRunDto;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TestingServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public TestingServiceClient(
            @Value("${services.testing.url:http://localhost:8083}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public List<RemoteTestRunDto> listTestRuns(UUID organizationId, UUID repositoryId, String commitSha) {
        if (organizationId == null) {
            return Collections.emptyList();
        }
        try {
            StringBuilder sb = new StringBuilder("/api/v1/test-runs?");
            if (repositoryId != null) {
                sb.append("repositoryId=").append(repositoryId).append("&");
            }
            if (commitSha != null && !commitSha.isBlank()) {
                sb.append("commitSha=").append(commitSha).append("&");
            }
            String path = sb.toString().replaceAll("[?&]$", "");
            HttpRequest request = buildRequest(path, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteTestRunDto>>() {});
            }
            log.warn("Testing-service listTestRuns returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch test runs from testing-service: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    public List<RemoteTestResultDto> getTestResults(UUID organizationId, UUID runId) {
        if (organizationId == null || runId == null) {
            return Collections.emptyList();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/test-runs/" + runId + "/results", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteTestResultDto>>() {});
            }
            log.warn("Testing-service getTestResults returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch test results for run {} from testing-service: {}", runId, e.getMessage());
        }
        return Collections.emptyList();
    }

    public Optional<RemoteRequirementTestTraceDto> getTraceabilityForRequirement(UUID organizationId, String requirementKey) {
        if (organizationId == null || requirementKey == null || requirementKey.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/traceability/requirements/" + requirementKey + "/tests", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteRequirementTestTraceDto.class));
            }
            log.warn("Testing-service getTraceabilityForRequirement returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch requirement tests for {} from testing-service: {}", requirementKey, e.getMessage());
        }
        return Optional.empty();
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
