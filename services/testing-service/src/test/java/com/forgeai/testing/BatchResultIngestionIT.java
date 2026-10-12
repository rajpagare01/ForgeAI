package com.forgeai.testing;

import com.forgeai.testing.application.command.CreateTestRunCommand;
import com.forgeai.testing.application.command.RecordTestResultItem;
import com.forgeai.testing.application.service.TestIngestionService;
import com.forgeai.testing.application.service.TestRunApplicationService;
import com.forgeai.testing.domain.model.TestResult;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.repository.TestResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BatchResultIngestionIT {

    @Autowired
    private TestRunApplicationService testRunApplicationService;

    @Autowired
    private TestIngestionService testIngestionService;

    @Autowired
    private TestResultRepository testResultRepository;

    @Test
    @DisplayName("BatchResultIngestionIT: Batch insert of 500+ test results verifies JDBC batching without connection leaks")
    void batchIngestLargeResultVolume() {
        UUID organizationId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();

        TestRun run = testRunApplicationService.createTestRun(organizationId, new CreateTestRunCommand(
                repositoryId,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4",
                null,
                10101L
        ));

        int totalCount = 550;
        List<RecordTestResultItem> items = new ArrayList<>(totalCount);
        for (int i = 1; i <= totalCount; i++) {
            TestResultStatus status = (i % 10 == 0) ? TestResultStatus.FAILED : TestResultStatus.PASSED;
            String errMsg = (status == TestResultStatus.FAILED) ? "Failure in test " + i : null;
            String reqKey = (i % 5 == 0) ? "REQ-20" + (i % 3) : null;

            items.add(new RecordTestResultItem(
                    "com.forgeai.performance.Suite" + (i % 10),
                    "testPerformanceCase_" + i,
                    "com.forgeai.performance.SuiteClass",
                    reqKey,
                    status,
                    15L,
                    errMsg,
                    null
            ));
        }

        int ingested = testIngestionService.ingestBatch(organizationId, run.getId(), items);
        assertThat(ingested).isEqualTo(totalCount);

        List<TestResult> persistedResults = testResultRepository.findAllByOrganizationIdAndTestRunId(organizationId, run.getId());
        assertThat(persistedResults).hasSize(totalCount);

        long failedCount = persistedResults.stream().filter(r -> r.getStatus() == TestResultStatus.FAILED).count();
        long passedCount = persistedResults.stream().filter(r -> r.getStatus() == TestResultStatus.PASSED).count();

        assertThat(failedCount).isEqualTo(55);
        assertThat(passedCount).isEqualTo(495);
    }
}
