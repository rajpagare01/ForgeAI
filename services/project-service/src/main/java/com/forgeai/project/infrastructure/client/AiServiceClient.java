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

/**
 * AiServiceClient — resilient HTTP client for the AI Service.
 * <p>
 * Resilience: synchronous with a 5-second per-request timeout, plus an in-process
 * {@link SimpleCircuitBreaker} that fast-fails after 3 consecutive failures and probes
 * after 30 seconds. When the circuit is OPEN, all methods return empty collections /
 * Optional.empty(). Callers treat this as "AI evidence UNAVAILABLE" and must not silently
 * treat missing AI analyses as zero risk or passing merge readiness.
 */
@Slf4j
@Component
public class AiServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    // 3 consecutive failures → open; re-probe after 30 seconds
    private final SimpleCircuitBreaker circuitBreaker =
            new SimpleCircuitBreaker("ai-service", 3, Duration.ofSeconds(30));

    public AiServiceClient(
            @Value("${services.ai.url:http://localhost:8084}") String baseUrl,
            ObjectMapper objectMapper) {
        this(baseUrl, objectMapper, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiServiceClient(
            @Value("${services.ai.url:http://localhost:8084}") String baseUrl,
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
                    state -> metrics.updateCircuitBreakerState("ai-service", state == SimpleCircuitBreaker.State.OPEN ? 2 : state == SimpleCircuitBreaker.State.HALF_OPEN ? 1 : 0)
            );
        }
    }

    /**
     * Returns true when the downstream circuit is open (unavailable).
     * Traceability callers use this to distinguish "genuinely no AI data" from
     * "AI service unavailable" — the latter must be reflected in evidenceStatus.
     */
    public boolean isUnavailable() {
        return circuitBreaker.isOpen();
    }

    public List<RemoteAiAnalysisDto> getAnalysesForPullRequest(UUID organizationId, UUID repositoryId, int prNumber) {
        if (organizationId == null || repositoryId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            String entityId = repositoryId + "#" + prNumber;
            String encodedEntityId = URLEncoder.encode(entityId, StandardCharsets.UTF_8);
            HttpRequest request = buildRequest("/api/v1/ai/analyses/PR/" + encodedEntityId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteAiAnalysisDto>>() {});
            }
            log.warn("AI-service getAnalysesForPullRequest returned status {}", response.statusCode());
            return Collections.<RemoteAiAnalysisDto>emptyList();
        }, Collections.emptyList());
    }

    public Optional<RemoteMergeReadinessDto> getMergeReadiness(UUID organizationId, UUID analysisId) {
        if (organizationId == null || analysisId == null) {
            return Optional.empty();
        }
        return circuitBreaker.execute(() -> {
            HttpRequest request = buildRequest("/api/v1/ai/merge-readiness/" + analysisId, organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readValue(response.body(), RemoteMergeReadinessDto.class));
            }
            log.warn("AI-service getMergeReadiness returned status {}", response.statusCode());
            return Optional.<RemoteMergeReadinessDto>empty();
        }, Optional.empty());
    }

    public List<RemoteEngineeringActionDto> getEngineeringActions(UUID organizationId, UUID analysisId) {
        if (organizationId == null || analysisId == null) {
            return Collections.emptyList();
        }
        return circuitBreaker.execute(() -> {
            HttpRequest request = buildRequest("/api/v1/ai/analyses/" + analysisId + "/actions", organizationId);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), new TypeReference<List<RemoteEngineeringActionDto>>() {});
            }
            log.warn("AI-service getEngineeringActions returned status {}", response.statusCode());
            return Collections.<RemoteEngineeringActionDto>emptyList();
        }, Collections.emptyList());
    }

    private HttpRequest buildRequest(String path, UUID organizationId) throws Exception {
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
                .header("X-Org-Id", organizationId.toString())
                .header("X-Organization-Id", organizationId.toString())
                .header("X-Correlation-Id", correlationId)
                .header("traceparent", traceparent)
                .timeout(Duration.ofSeconds(5))
                .GET();
        return builder.build();
    }
}
