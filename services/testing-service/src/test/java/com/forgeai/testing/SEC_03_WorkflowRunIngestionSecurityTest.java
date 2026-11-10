package com.forgeai.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.application.dto.WorkflowRunIngestionRequest;
import com.forgeai.testing.application.service.WorkflowRunIngestionService;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestRunStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "forgeai.security.enforce-internal-marker=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SEC_03_WorkflowRunIngestionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRunIngestionService workflowRunIngestionService;

    private WorkflowRunIngestionRequest sampleRequest() {
        return WorkflowRunIngestionRequest.builder()
                .organizationId(UUID.randomUUID())
                .repositoryId(UUID.randomUUID())
                .workspaceId(UUID.randomUUID())
                .scope("ORGANIZATION")
                .workflowRunId(424242L)
                .workflowName("CI")
                .runNumber(7)
                .status("completed")
                .conclusion("success")
                .headBranch("main")
                .build();
    }

    @Test
    @DisplayName("SEC-03: Rejects internal workflow-run ingestion without X-ForgeAI-Internal header with 403")
    void rejectsMissingInternalMarkerWith403() throws Exception {
        mockMvc.perform(post("/api/v1/internal/workflow-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(workflowRunIngestionService);
    }

    @Test
    @DisplayName("SEC-03: Rejects internal workflow-run ingestion with spoofed X-ForgeAI-Internal header with 403")
    void rejectsSpoofedInternalMarkerWith403() throws Exception {
        mockMvc.perform(post("/internal/workflow-runs")
                        .header("X-ForgeAI-Internal", "unverified-spoof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(workflowRunIngestionService);
    }

    @Test
    @DisplayName("SEC-03: Accepts internal workflow-run ingestion with X-ForgeAI-Internal: attested")
    void acceptsAttestedInternalMarker() throws Exception {
        WorkflowRunIngestionRequest req = sampleRequest();
        TestRun returned = new TestRun();
        returned.setId(UUID.randomUUID());
        returned.setStatus(TestRunStatus.PASSED);
        when(workflowRunIngestionService.ingestWorkflowRun(any(WorkflowRunIngestionRequest.class)))
                .thenReturn(returned);

        mockMvc.perform(post("/api/v1/internal/workflow-runs")
                        .header("X-ForgeAI-Internal", "attested")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(returned.getId().toString()));

        verify(workflowRunIngestionService).ingestWorkflowRun(any(WorkflowRunIngestionRequest.class));
    }
}
