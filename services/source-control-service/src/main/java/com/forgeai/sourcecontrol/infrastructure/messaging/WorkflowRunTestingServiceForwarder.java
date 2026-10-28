package com.forgeai.sourcecontrol.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.sourcecontrol.domain.event.EventEnvelope;
import com.forgeai.sourcecontrol.domain.event.WorkflowRunReceivedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Forwards {@link WorkflowRunReceivedEvent} from the Source Control durable outbox
 * to the Testing Service internal ingestion API.
 * <p>
 * If the Testing Service is unavailable or returns an error, an exception is thrown
 * causing the outbox relay to preserve the event for subsequent retry.
 */
@Slf4j
@Component
public class WorkflowRunTestingServiceForwarder {

    private final String testingServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public WorkflowRunTestingServiceForwarder(
            @Value("${forgeai.services.testing-service.url:${TESTING_SERVICE_URL:http://testing-service:8083}}") String testingServiceUrl) {
        this.testingServiceUrl = testingServiceUrl.endsWith("/")
                ? testingServiceUrl.substring(0, testingServiceUrl.length() - 1)
                : testingServiceUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper()
                .findAndRegisterModules();
    }

    @EventListener
    public void onEventEnvelope(EventEnvelope<?> envelope) throws Exception {
        if (envelope == null || !(envelope.getPayload() instanceof WorkflowRunReceivedEvent event)) {
            return;
        }

        log.info("Relaying WorkflowRunReceivedEvent to Testing Service: workflowRunId={}, orgId={}, repoId={}, traceId={}",
                event.getWorkflowRunId(), event.getOrganizationId(), event.getRepositoryId(), envelope.getTraceId());

        String jsonPayload = objectMapper.writeValueAsString(event);
        String targetUrl = testingServiceUrl + "/api/v1/internal/workflow-runs";

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Trace-Id", envelope.getTraceId() != null ? envelope.getTraceId() : "")
                .header("X-ForgeAI-Internal", "attested");

        if (event.getOrganizationId() != null) {
            requestBuilder.header("X-Org-Id", event.getOrganizationId().toString());
        }
        if (event.getWorkspaceId() != null) {
            requestBuilder.header("X-Workspace-Id", event.getWorkspaceId().toString());
        }

        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Successfully delivered workflow_run to Testing Service: workflowRunId={}, httpStatus={}",
                    event.getWorkflowRunId(), response.statusCode());
        } else {
            String errorMsg = String.format("Testing Service rejected workflow_run %s with status %d: %s",
                    event.getWorkflowRunId(), response.statusCode(), response.body());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
}
