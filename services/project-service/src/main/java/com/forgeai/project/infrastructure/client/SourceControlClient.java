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

/**
 * SourceControlClient — resilient HTTP client for Source Control Service.
 * <p>
 * Resilience: synchronous with a 5-second per-request timeout, plus an in-process
 * {@link SimpleCircuitBreaker} that fast-fails after 3 consecutive failures and probes
 * after 30 seconds. This prevents cascading failures when source-control-service is
 * unavailable without introducing uncontrolled retry storms.
 * <p>
 * When the circuit is OPEN, all methods return empty collections / Optional.empty().
 * Callers should treat this as "downstream UNAVAILABLE" and set evidenceStatus accordingly.
 */
@Slf4j
@Component
public class SourceControlClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    // 3 consecutive failures → open; re-probe after 30 seconds
    private final SimpleCircuitBreaker circuitBreaker =
            new SimpleCircuitBreaker("source-control-service", 3, Duration.ofSeconds(30));

    public SourceControlClient(
            @Value("${services.source-control.url:http://localhost:8082}") String baseUrl,
            ObjectMapper objectMapper) {
        this(baseUrl, objectMapper, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SourceControlClient(
            @Value("${services.source-control.url:http://localhost:8082}") String baseUrl,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false) CircuitBreakerRegistry registry,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.forgeai.project.infrastructure.metrics.ProjectMetrics metrics) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        if (registry != null) {
            registry.register(circuitBreaker);
        }
        if (metrics != null) {
            circuitBreaker.setListeners(
                    metrics::recordCircuitBreakerOpen,
                    metrics::recordCircuitBreakerFastFail,
                    state -> metrics.updateCircuitBreakerState("source-control-service", state == SimpleCircuitBreaker.State.OPEN ? 2 : state == SimpleCircuitBreaker.State.HALF_OPEN ? 1 : 0)
            );
        }
    }

    /**
     * Returns true when the downstream circuit is open (unavailable).
     * Traceability callers use this to distinguish "genuinely no data" from "unavailable downstream".
     */
    public boolean isUnavailable() {
        return circuitBreaker.isOpen();
    }

    public List<RemoteRepositoryDto> listRepositories(UUID organizationId) {
        return listRepositories(organizationId, organizationId);
    }

    public List<RemoteRepositoryDto> listRepositories(UUID workspaceId, UUID organizationId) {
        if (workspaceId == null && organizationId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            String path = "/api/v1/repositories";
            if (organizationId == null && workspaceId != null) {
                path += "?workspaceId=" + workspaceId;
            }
            HttpRequest request = buildRequest(path, workspaceId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteRepositoryDto>>() {});
            }
            log.warn("Source-control listRepositories returned status {}", response.statusCode());
            return Collections.<RemoteRepositoryDto>emptyList();
        }, Collections.emptyList());
    }

    public Optional<RemoteRepositoryDto> getRepository(UUID organizationId, UUID repositoryId) {
        return getRepository(organizationId, organizationId, repositoryId);
    }

    public Optional<RemoteRepositoryDto> getRepository(UUID workspaceId, UUID organizationId, UUID repositoryId) {
        if ((workspaceId == null && organizationId == null) || repositoryId == null) {
            return Optional.empty();
        }
        return circuitBreaker.execute(() -> {
            String path = "/api/v1/repositories/" + repositoryId;
            if (organizationId == null && workspaceId != null) {
                path += "?workspaceId=" + workspaceId;
            }
            HttpRequest request = buildRequest(path, workspaceId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteRepositoryDto.class));
            }
            log.warn("Source-control getRepository returned status {}", response.statusCode());
            return Optional.<RemoteRepositoryDto>empty();
        }, Optional.empty());
    }

    public List<RemotePullRequestDto> listPullRequests(UUID organizationId, UUID repositoryId) {
        return listPullRequests(organizationId, organizationId, repositoryId);
    }

    public List<RemotePullRequestDto> listPullRequests(UUID workspaceId, UUID organizationId, UUID repositoryId) {
        if (workspaceId == null && organizationId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            StringBuilder path = new StringBuilder();
            if (repositoryId != null) {
                path.append("/api/v1/repositories/").append(repositoryId).append("/pull-requests");
                if (organizationId == null && workspaceId != null) {
                    path.append("?workspaceId=").append(workspaceId);
                }
            } else {
                path.append("/api/v1/pull-requests");
                if (organizationId == null && workspaceId != null) {
                    path.append("?workspaceId=").append(workspaceId);
                }
            }
            HttpRequest request = buildRequest(path.toString(), workspaceId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemotePullRequestDto>>() {});
            }
            log.warn("Source-control listPullRequests returned status {}", response.statusCode());
            return Collections.<RemotePullRequestDto>emptyList();
        }, Collections.emptyList());
    }

    public List<RemoteCommitDto> listCommits(UUID organizationId, UUID repositoryId) {
        return listCommits(organizationId, organizationId, repositoryId);
    }

    public List<RemoteCommitDto> listCommits(UUID workspaceId, UUID organizationId, UUID repositoryId) {
        if ((workspaceId == null && organizationId == null) || repositoryId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            String path = "/api/v1/repositories/" + repositoryId + "/commits";
            if (organizationId == null && workspaceId != null) {
                path += "?workspaceId=" + workspaceId;
            }
            HttpRequest request = buildRequest(path, workspaceId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteCommitDto>>() {});
            }
            log.warn("Source-control listCommits returned status {}", response.statusCode());
            return Collections.<RemoteCommitDto>emptyList();
        }, Collections.emptyList());
    }

    private HttpRequest buildRequest(String path, UUID workspaceId, UUID organizationId) throws Exception {
        String traceId = MDC.get("traceId");
        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = (traceId != null && !traceId.isBlank()) ? traceId : UUID.randomUUID().toString();
        }
        if (traceId == null || traceId.isBlank()) {
            String clean = correlationId.replace("-", "").toLowerCase();
            traceId = clean.length() >= 32 ? clean.substring(0, 32) : String.format("%32s", clean).replace(' ', '0');
        }
        String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String traceparent = String.format("00-%s-%s-01", traceId, spanId);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("X-ForgeAI-Internal", "attested")
                .header("X-Correlation-Id", correlationId)
                .header("traceparent", traceparent)
                .timeout(Duration.ofSeconds(5))
                .GET();

        if (organizationId != null) {
            builder.header("X-Org-Id", organizationId.toString());
            builder.header("X-Organization-Id", organizationId.toString());
            builder.header("X-Workspace-Id", (workspaceId != null ? workspaceId : organizationId).toString());
            builder.header("X-Workspace-Scope", "ORGANIZATION");
        } else if (workspaceId != null) {
            builder.header("X-Workspace-Id", workspaceId.toString());
            builder.header("X-Workspace-Scope", "PERSONAL");
        }

        UUID userId = com.forgeai.project.infrastructure.security.TenantContext.getUserId();
        if (userId != null) {
            builder.header("X-User-Id", userId.toString());
        }

        return builder.build();
    }
}
