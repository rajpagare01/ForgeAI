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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "forgeai.security.enforce-internal-marker=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SEC_03_WorkflowRunIngestionDisabledMarkerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WorkflowRunIngestionService workflowRunIngestionService;

    @Test
    @DisplayName("SEC-03: Allows unattested workflow-run ingestion when forgeai.security.enforce-internal-marker=false")
    void allowsRequestWhenEnforcementDisabled() throws Exception {
        TestRun returned = new TestRun();
        returned.setId(UUID.randomUUID());
        returned.setStatus(TestRunStatus.RUNNING);
        when(workflowRunIngestionService.ingestWorkflowRun(any(WorkflowRunIngestionRequest.class)))
                .thenReturn(returned);

        WorkflowRunIngestionRequest req = WorkflowRunIngestionRequest.builder()
                .organizationId(UUID.randomUUID())
                .repositoryId(UUID.randomUUID())
                .workflowRunId(999L)
                .status("in_progress")
                .build();

        mockMvc.perform(post("/api/v1/internal/workflow-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(returned.getId().toString()));

        verify(workflowRunIngestionService).ingestWorkflowRun(any(WorkflowRunIngestionRequest.class));
    }
}

