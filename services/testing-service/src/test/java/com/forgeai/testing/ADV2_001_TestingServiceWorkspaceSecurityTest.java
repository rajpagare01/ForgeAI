package com.forgeai.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.command.RecordTestResultItem;
import com.forgeai.testing.application.command.RecordTestResultsBatchCommand;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.repository.TestRunRepository;
import com.forgeai.testing.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ADV2_001_TestingServiceWorkspaceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TestRunRepository testRunRepository;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("ADV2-001: Personal workspace can create runs, ingest results, and isolate from other workspaces")
    void testPersonalWorkspaceIsolationAndLifecycle() throws Exception {
        UUID personalWsId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        // 1. Create run under personal workspace (X-Workspace-Id + X-Workspace-Scope: PERSONAL, no X-Org-Id)
        CreateTestRunCommand createCmd = new CreateTestRunCommand(
                repoId,
                "4444444444444444444444444444444444444444",
                12,
                1001L
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/test-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCmd))
                        .header("X-Workspace-Id", personalWsId.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userA.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(personalWsId.toString()))
                .andExpect(jsonPath("$.scope").value("PERSONAL"))
                .andExpect(jsonPath("$.organizationId").doesNotExist())
                .andReturn();

        TestRun createdRun = objectMapper.readValue(createResult.getResponse().getContentAsString(), TestRun.class);
        UUID runId = createdRun.getId();
        assertThat(runId).isNotNull();

        // 2. Ingest batch results under personal workspace
        RecordTestResultsBatchCommand batchCmd = new RecordTestResultsBatchCommand(
                List.of(
                        new RecordTestResultItem("UnitSuite", "testLogin", "AuthTests", "REQ-AUTH-1", TestResultStatus.PASSED, 50L, null, null),
                        new RecordTestResultItem("UnitSuite", "testLogout", "AuthTests", "REQ-AUTH-2", TestResultStatus.PASSED, 20L, null, null)
                )
        );

        mockMvc.perform(post("/api/v1/test-runs/{runId}/results/batch", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchCmd))
                        .header("X-Workspace-Id", personalWsId.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userA.toString()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ingestedCount").value(2));

        // 3. Complete run under personal workspace
        mockMvc.perform(post("/api/v1/test-runs/{runId}/complete", runId)
                        .header("X-Workspace-Id", personalWsId.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userA.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.passedTests").value(2))
                .andExpect(jsonPath("$.failedTests").value(0));

        // 4. Verify outbox event written with workspace partitioning
        List<OutboxEventEntity> outboxEvents = outboxEventRepository.findAll();
        OutboxEventEntity outboxEntity = outboxEvents.stream()
                .filter(e -> "TEST_RUN_COMPLETED".equals(e.getEventType()) && runId.toString().equals(e.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Outbox event not found for run " + runId));
        assertThat(outboxEntity.getWorkspaceId()).isEqualTo(personalWsId);
        assertThat(outboxEntity.getScope()).isEqualTo("PERSONAL");
        assertThat(outboxEntity.getOrganizationId()).isNull();

        // 5. Cross-workspace attack: Attacker in personalWsB attempts to access UserA's test run
        UUID personalWsB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        // Attacker GET /api/v1/test-runs/{runId}
        mockMvc.perform(get("/api/v1/test-runs/{runId}", runId)
                        .header("X-Workspace-Id", personalWsB.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isNotFound());

        // Attacker GET /api/v1/test-runs/{runId}/results
        mockMvc.perform(get("/api/v1/test-runs/{runId}/results", runId)
                        .header("X-Workspace-Id", personalWsB.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // Attacker GET /api/v1/test-runs
        mockMvc.perform(get("/api/v1/test-runs")
                        .header("X-Workspace-Id", personalWsB.toString())
                        .header("X-Workspace-Scope", "PERSONAL")
                        .header("X-User-Id", userB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("ADV2-001: Organization workspace correctly scopes and isolates test runs")
    void testOrganizationWorkspaceIsolation() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID repoId = UUID.randomUUID();

        CreateTestRunCommand createCmd = new CreateTestRunCommand(
                repoId,
                "5555555555555555555555555555555555555555",
                25,
                2002L
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/test-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createCmd))
                        .header("X-Org-Id", orgA.toString())
                        .header("X-User-Id", userA.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workspaceId").value(orgA.toString()))
                .andExpect(jsonPath("$.organizationId").value(orgA.toString()))
                .andExpect(jsonPath("$.scope").value("ORGANIZATION"))
                .andReturn();

        TestRun run = objectMapper.readValue(createResult.getResponse().getContentAsString(), TestRun.class);

        // Attacker OrgB attempts to fetch OrgA's test run
        UUID orgB = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/test-runs/{runId}", run.getId())
                        .header("X-Org-Id", orgB.toString())
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }
}
