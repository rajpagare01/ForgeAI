package com.forgeai.project.infrastructure.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.project.infrastructure.client.dto.RemoteCommitDto;
import com.forgeai.project.infrastructure.client.dto.RemotePullRequestDto;
import com.forgeai.project.infrastructure.client.dto.RemoteRepositoryDto;
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
public class SourceControlClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SourceControlClient(
            @Value("${services.source-control.url:http://localhost:8082}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public List<RemoteRepositoryDto> listRepositories(UUID organizationId) {
        if (organizationId == null) {
            return Collections.emptyList();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/repositories", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteRepositoryDto>>() {});
            }
            log.warn("Source-control listRepositories returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch repositories from source-control-service: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    public Optional<RemoteRepositoryDto> getRepository(UUID organizationId, UUID repositoryId) {
        if (organizationId == null || repositoryId == null) {
            return Optional.empty();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/repositories/" + repositoryId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteRepositoryDto.class));
            }
            log.warn("Source-control getRepository returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch repository {} from source-control-service: {}", repositoryId, e.getMessage());
        }
        return Optional.empty();
    }

    public List<RemotePullRequestDto> listPullRequests(UUID organizationId, UUID repositoryId) {
        if (organizationId == null) {
            return Collections.emptyList();
        }
        try {
            String path = repositoryId != null
                    ? "/api/v1/repositories/" + repositoryId + "/pull-requests"
                    : "/api/v1/pull-requests";
            HttpRequest request = buildRequest(path, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemotePullRequestDto>>() {});
            }
            log.warn("Source-control listPullRequests returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch pull requests from source-control-service: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    public List<RemoteCommitDto> listCommits(UUID organizationId, UUID repositoryId) {
        if (organizationId == null || repositoryId == null) {
            return Collections.emptyList();
        }
        try {
            HttpRequest request = buildRequest("/api/v1/repositories/" + repositoryId + "/commits", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteCommitDto>>() {});
            }
            log.warn("Source-control listCommits returned status {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to fetch commits from source-control-service: {}", e.getMessage());
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
