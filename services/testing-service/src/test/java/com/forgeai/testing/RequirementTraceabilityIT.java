package com.forgeai.testing;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.command.RecordTestResultItem;
import com.forgeai.testing.application.service.TestIngestionService;
import com.forgeai.testing.application.service.TestRunApplicationService;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RequirementTraceabilityIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRunApplicationService testRunApplicationService;

    @Autowired
    private TestIngestionService testIngestionService;

    @Test
    @DisplayName("RequirementTraceabilityIT: Mapping test case to REQ-101 across passed and failed runs returns accurate latest execution status")
    void traceabilityQueryReturnsLatestStatusForRequirement() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        // 1. First run: test fails
        TestRun run1 = testRunApplicationService.createTestRun(organizationId, new CreateTestRunCommand(
                repositoryId,
                "1111111111111111111111111111111111111111",
                null,
                1001L
        ));

        testIngestionService.ingestBatch(organizationId, run1.getId(), List.of(
                new RecordTestResultItem(
                        "com.forgeai.auth.SignupTest",
                        "testUserSignup_REQ-101",
                        "com.forgeai.auth.SignupTest",
                        "REQ-101",
                        TestResultStatus.FAILED,
                        50L,
                        "Signup database deadlock",
                        "DeadlockException details"
                )
        ));
        testRunApplicationService.completeTestRun(organizationId, run1.getId());

        // 2. Second run: bug fixed, test passes
        TestRun run2 = testRunApplicationService.createTestRun(organizationId, new CreateTestRunCommand(
                repositoryId,
                "2222222222222222222222222222222222222222",
                null,
                1002L
        ));

        testIngestionService.ingestBatch(organizationId, run2.getId(), List.of(
                new RecordTestResultItem(
                        "com.forgeai.auth.SignupTest",
                        "testUserSignup_REQ-101",
                        "com.forgeai.auth.SignupTest",
                        "REQ-101",
                        TestResultStatus.PASSED,
                        25L,
                        null,
                        null
                )
        ));
        testRunApplicationService.completeTestRun(organizationId, run2.getId());

        // 3. Query traceability endpoint for REQ-101
        mockMvc.perform(get("/api/v1/traceability/requirements/{reqKey}/tests", "REQ-101")
                        .header("X-Org-Id", organizationId.toString())
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementKey").value("REQ-101"))
                .andExpect(jsonPath("$.totalTests").value(1))
                .andExpect(jsonPath("$.passedTests").value(1))
                .andExpect(jsonPath("$.failedTests").value(0))
                .andExpect(jsonPath("$.tests[0].testName").value("testUserSignup_REQ-101"))
                .andExpect(jsonPath("$.tests[0].latestStatus").value("PASSED"))
                .andExpect(jsonPath("$.tests[0].latestRunId").value(run2.getId().toString()))
                .andExpect(jsonPath("$.tests[0].latestDurationMs").value(25));
    }
}
