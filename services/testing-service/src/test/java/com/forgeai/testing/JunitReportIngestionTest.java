package com.forgeai.testing;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.service.TestRunApplicationService;
import com.forgeai.testing.domain.model.*;
import com.forgeai.testing.domain.repository.TestCaseRepository;
import com.forgeai.testing.domain.repository.TestResultRepository;
import com.forgeai.testing.domain.repository.TestSuiteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JunitReportIngestionTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRunApplicationService testRunApplicationService;

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestResultRepository testResultRepository;

    @Test
    @DisplayName("JunitReportIngestionTest: Uploading standard JUnit XML parses suites, upserts cases, and creates test results with correct states")
    void uploadJunitXmlParsesAndIngestsCorrectly() throws Exception {
        UUID organizationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        // 1. Create a running test run
        TestRun run = testRunApplicationService.createTestRun(organizationId, new CreateTestRunCommand(
                repositoryId,
                "4b825dc642cb6eb9a060e54bf8d69288fbee4904",
                12,
                998877L
        ));

        // 2. Prepare JUnit XML with PASSED, FAILED, and SKIPPED test cases
        String xmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.forgeai.auth.AuthServiceTest" tests="3" failures="1" skipped="1" time="0.057">
                <testcase name="testLoginSuccess_REQ-101" classname="com.forgeai.auth.AuthServiceTest" time="0.045"/>
                <testcase name="testBadPassword_REQ-101" classname="com.forgeai.auth.AuthServiceTest" time="0.012">
                    <failure message="Assertion failed: expected 401 but got 200">org.opentest4j.AssertionFailedError: expected 401</failure>
                </testcase>
                <testcase name="testDisabledUser" classname="com.forgeai.auth.AuthServiceTest" time="0.000">
                    <skipped message="Disabled until next sprint"/>
                </testcase>
            </testsuite>
            """;

        // 3. Post XML report
        mockMvc.perform(post("/api/v1/test-runs/{runId}/reports/junit", run.getId())
                        .contentType(MediaType.APPLICATION_XML)
                        .header("X-Org-Id", organizationId.toString())
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .content(xmlContent.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("INGESTED"))
                .andExpect(jsonPath("$.ingestedCount").value(3));

        // 4. Verify TestSuite created
        assertThat(testSuiteRepository.findByOrganizationIdAndRepositoryIdAndName(
                organizationId, repositoryId, "com.forgeai.auth.AuthServiceTest")).isPresent();

        // 5. Verify TestCases and Requirement tagging
        List<TestCase> req101Cases = testCaseRepository.findAllByOrganizationIdAndRequirementKey(organizationId, "REQ-101");
        assertThat(req101Cases).hasSize(2);
        assertThat(req101Cases).extracting(TestCase::getName)
                .containsExactlyInAnyOrder("testLoginSuccess_REQ-101", "testBadPassword_REQ-101");

        // 6. Verify TestResults statuses
        List<TestResult> results = testResultRepository.findAllByOrganizationIdAndTestRunId(organizationId, run.getId());
        assertThat(results).hasSize(3);

        assertThat(results).anyMatch(r -> r.getStatus() == TestResultStatus.PASSED && r.getDurationMs() == 45L);
        assertThat(results).anyMatch(r -> r.getStatus() == TestResultStatus.FAILED &&
                "Assertion failed: expected 401 but got 200".equals(r.getErrorMessage()));
        assertThat(results).anyMatch(r -> r.getStatus() == TestResultStatus.SKIPPED &&
                "Disabled until next sprint".equals(r.getErrorMessage()));
    }
}
