package com.forgeai.testing.application.service;

import com.forgeai.testing.application.dto.RequirementTraceabilityResponse;
import com.forgeai.testing.domain.model.TestCase;
import com.forgeai.testing.domain.model.TestResultStatus;
import com.forgeai.testing.domain.repository.TestCaseRepository;
import com.forgeai.testing.infrastructure.persistence.entity.TestResultJpaEntity;
import com.forgeai.testing.infrastructure.persistence.repository.SpringDataTestResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementTraceabilityService {

    private final TestCaseRepository testCaseRepository;
    private final SpringDataTestResultRepository springDataTestResultRepository;

    @Transactional(readOnly = true)
    public RequirementTraceabilityResponse getTraceabilityForRequirement(UUID organizationId, String requirementKey) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }

        List<TestCase> testCases = testCaseRepository.findAllByOrganizationIdAndRequirementKey(organizationId, requirementKey.toUpperCase());

        int passedCount = 0;
        int failedCount = 0;
        List<RequirementTraceabilityResponse.TestCaseTraceSummary> summaries = new ArrayList<>();

        for (TestCase tc : testCases) {
            List<TestResultJpaEntity> latestResults = springDataTestResultRepository.findLatestByTestCaseId(organizationId, tc.getId());

            TestResultStatus latestStatus = null;
            Long latestDuration = null;
            UUID latestRunId = null;
            String errorMessage = null;

            if (!latestResults.isEmpty()) {
                TestResultJpaEntity latest = latestResults.get(0);
                latestStatus = TestResultStatus.valueOf(latest.getStatus());
                latestDuration = latest.getDurationMs();
                latestRunId = latest.getTestRunId();
                errorMessage = latest.getErrorMessage();

                if (latestStatus == TestResultStatus.PASSED) {
                    passedCount++;
                } else if (latestStatus == TestResultStatus.FAILED) {
                    failedCount++;
                }
            }

            summaries.add(RequirementTraceabilityResponse.TestCaseTraceSummary.builder()
                    .testCaseId(tc.getId())
                    .testName(tc.getName())
                    .classname(tc.getClassname())
                    .latestStatus(latestStatus)
                    .latestDurationMs(latestDuration)
                    .latestRunId(latestRunId)
                    .latestErrorMessage(errorMessage)
                    .build());
        }

        return RequirementTraceabilityResponse.builder()
                .requirementKey(requirementKey.toUpperCase())
                .totalTests(testCases.size())
                .passedTests(passedCount)
                .failedTests(failedCount)
                .tests(summaries)
                .build();
    }
}
