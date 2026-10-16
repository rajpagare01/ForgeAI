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

/**
 * TestingServiceClient — resilient HTTP client for the Testing Service.
 * <p>
 * Resilience: synchronous with a 5-second per-request timeout, plus an in-process
 * {@link SimpleCircuitBreaker} that fast-fails after 3 consecutive failures and probes
 * after 30 seconds. When the circuit is OPEN, all methods return empty collections /
 * Optional.empty(). Callers treat this as "downstream UNAVAILABLE" and must not interpret
 * it as "no test evidence found" (which would silently pass verification gates).
 */
@Slf4j
@Component
public class TestingServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    // 3 consecutive failures → open; re-probe after 30 seconds
    private final SimpleCircuitBreaker circuitBreaker =
            new SimpleCircuitBreaker("testing-service", 3, Duration.ofSeconds(30));

    public TestingServiceClient(
            @Value("${services.testing.url:http://localhost:8083}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    /**
     * Returns true when the downstream circuit is open (unavailable).
     * Traceability callers use this to distinguish "genuinely no data" from "unavailable downstream".
     */
    public boolean isUnavailable() {
        return circuitBreaker.isOpen();
    }

    public List<RemoteTestRunDto> listTestRuns(UUID organizationId, UUID repositoryId, String commitSha) {
        if (organizationId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
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
            return Collections.<RemoteTestRunDto>emptyList();
        }, Collections.emptyList());
    }

    public List<RemoteTestResultDto> getTestResults(UUID organizationId, UUID runId) {
        if (organizationId == null || runId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            HttpRequest request = buildRequest("/api/v1/test-runs/" + runId + "/results", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteTestResultDto>>() {});
            }
            log.warn("Testing-service getTestResults returned status {}", response.statusCode());
            return Collections.<RemoteTestResultDto>emptyList();
        }, Collections.emptyList());
    }

    public Optional<RemoteRequirementTestTraceDto> getTraceabilityForRequirement(UUID organizationId, String requirementKey) {
        if (organizationId == null || requirementKey == null || requirementKey.isBlank()) {
            return Optional.empty();
        }
        return circuitBreaker.execute(() -> {
            HttpRequest request = buildRequest(
                    "/api/v1/traceability/requirements/" + requirementKey + "/tests", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteRequirementTestTraceDto.class));
            }
            log.warn("Testing-service getTraceabilityForRequirement returned status {}", response.statusCode());
            return Optional.<RemoteRequirementTestTraceDto>empty();
        }, Optional.empty());
    }

    private HttpRequest buildRequest(String path, UUID organizationId) throws Exception {
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
