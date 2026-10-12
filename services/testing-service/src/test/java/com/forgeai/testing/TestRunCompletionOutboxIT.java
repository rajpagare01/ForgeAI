package com.forgeai.testing;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.command.RecordTestResultItem;
import com.forgeai.testing.application.service.TestIngestionService;
import com.forgeai.testing.application.service.TestRunApplicationService;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestRunStatus;
import com.forgeai.testing.infrastructure.persistence.entity.OutboxEventEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestRunCompletionOutboxIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRunApplicationService testRunApplicationService;

    @Autowired
    private TestIngestionService testIngestionService;

    @Autowired
    private SpringDataOutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("TestRunCompletionOutboxIT: Finalizing a run calculates accurate totals and writes TestRunCompletedEvent to outbox")
    void completeTestRunRollsUpTotalsAndEmitsOutboxEvent() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        // 1. Create run
        TestRun run = testRunApplicationService.createTestRun(organizationId, new CreateTestRunCommand(
                repositoryId,
                "3333333333333333333333333333333333333333",
                45,
                7788L
        ));

        // 2. Ingest 2 passed, 1 skipped
        testIngestionService.ingestBatch(organizationId, run.getId(), List.of(
                new RecordTestResultItem("SuiteA", "testA1", "SuiteA", null, TestResultStatus.PASSED, 10L, null, null),
                new RecordTestResultItem("SuiteA", "testA2", "SuiteA", null, TestResultStatus.PASSED, 20L, null, null),
                new RecordTestResultItem("SuiteB", "testB1", "SuiteB", null, TestResultStatus.SKIPPED, 0L, "Skipped", null)
        ));

        // 3. Complete run via controller
        mockMvc.perform(post("/api/v1/test-runs/{runId}/complete", run.getId())
                        .header("X-Org-Id", organizationId.toString())
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSED"))
                .andExpect(jsonPath("$.totalTests").value(3))
                .andExpect(jsonPath("$.passedTests").value(2))
                .andExpect(jsonPath("$.failedTests").value(0))
                .andExpect(jsonPath("$.skippedTests").value(1))
                .andExpect(jsonPath("$.durationMs").value(30));

        // 4. Verify run status in repository
        TestRun updatedRun = testRunApplicationService.getTestRun(organizationId, run.getId());
        assertThat(updatedRun.getStatus()).isEqualTo(TestRunStatus.PASSED);
        assertThat(updatedRun.getCompletedAt()).isNotNull();

        // 5. Verify outbox event created
        List<OutboxEventEntity> events = outboxEventRepository.findAll();
        boolean foundCompletedEvent = events.stream().anyMatch(e ->
                "TEST_RUN_COMPLETED".equals(e.getEventType()) &&
                run.getId().toString().equals(e.getAggregateId()) &&
                e.getPayload().contains("\"totalTests\":3") &&
                e.getPayload().contains("\"passedTests\":2")
        );
        assertThat(foundCompletedEvent).isTrue();
    }
}
