package com.forgeai.testing.application.service;

import com.forgeai.testing.application.command.RecordTestResultItem;
import com.forgeai.testing.domain.exception.ResourceNotFoundException;
import com.forgeai.testing.domain.model.TestCase;
import com.forgeai.testing.domain.model.TestResult;
import com.forgeai.testing.domain.model.TestRun;
import com.forgeai.testing.domain.model.TestSuite;
import com.forgeai.testing.domain.repository.TestCaseRepository;
import com.forgeai.testing.domain.repository.TestResultRepository;
import com.forgeai.testing.domain.repository.TestRunRepository;
import com.forgeai.testing.domain.repository.TestSuiteRepository;
import com.forgeai.testing.infrastructure.parser.JunitXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TestIngestionService {

    private final TestRunRepository testRunRepository;
    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final JunitXmlParser junitXmlParser;

    @Transactional
    public int ingestBatch(UUID organizationId, UUID runId, List<RecordTestResultItem> items) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must be provided via tenant context");
        }
        if (items == null || items.isEmpty()) {
            return 0;
        }

        TestRun run = testRunRepository.findByIdAndOrganizationId(runId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Test run not found: " + runId));

        Map<String, TestSuite> suiteCache = new HashMap<>();
        Map<String, TestCase> caseCache = new HashMap<>();
        List<TestResult> resultsToSave = new ArrayList<>(items.size());
        Instant now = Instant.now();

        for (RecordTestResultItem item : items) {
            String suiteName = (item.suiteName() != null && !item.suiteName().isBlank())
                    ? item.suiteName()
                    : "default-suite";

            // 1. Get or create TestSuite
            TestSuite suite = suiteCache.computeIfAbsent(suiteName, name ->
                    testSuiteRepository.findByOrganizationIdAndRepositoryIdAndName(organizationId, run.getRepositoryId(), name)
                            .orElseGet(() -> testSuiteRepository.save(TestSuite.builder()
                                    .organizationId(organizationId)
                                    .repositoryId(run.getRepositoryId())
                                    .name(name)
                                    .framework("JUNIT")
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build()))
            );

            // 2. Get or create TestCase
            String caseKey = suite.getId() + "#" + item.testName() + "#" + (item.classname() != null ? item.classname() : "");
            TestCase testCase = caseCache.computeIfAbsent(caseKey, k ->
                    testCaseRepository.findBySuiteIdAndNameAndClassname(suite.getId(), item.testName(), item.classname())
                            .map(existing -> {
                                if (item.requirementKey() != null && !item.requirementKey().equals(existing.getRequirementKey())) {
                                    existing.setRequirementKey(item.requirementKey());
                                    return testCaseRepository.save(existing);
                                }
                                return existing;
                            })
                            .orElseGet(() -> testCaseRepository.save(TestCase.builder()
                                    .organizationId(organizationId)
                                    .suiteId(suite.getId())
                                    .name(item.testName())
                                    .classname(item.classname())
                                    .requirementKey(item.requirementKey())
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build()))
            );

            // 3. Assemble TestResult
            TestResult result = TestResult.builder()
                    .organizationId(organizationId)
                    .testRunId(run.getId())
                    .testCaseId(testCase.getId())
                    .status(item.status())
                    .durationMs(item.durationMs() != null ? item.durationMs() : 0L)
                    .errorMessage(item.errorMessage())
                    .stackTrace(item.stackTrace())
                    .createdAt(now)
                    .build();

            resultsToSave.add(result);
        }

        testResultRepository.saveAll(resultsToSave);
        log.info("Ingested {} test results for runId={}, orgId={}", resultsToSave.size(), runId, organizationId);
        return resultsToSave.size();
    }

    @Transactional
    public int ingestJunitXml(UUID organizationId, UUID runId, byte[] xmlBytes) {
        List<JunitXmlParser.ParsedSuite> parsedSuites = junitXmlParser.parse(xmlBytes);
        List<RecordTestResultItem> items = new ArrayList<>();

        for (JunitXmlParser.ParsedSuite suite : parsedSuites) {
            for (JunitXmlParser.ParsedCase c : suite.getCases()) {
                items.add(new RecordTestResultItem(
                        suite.getName(),
                        c.getName(),
                        c.getClassname(),
                        c.getRequirementKey(),
                        c.getStatus(),
                        c.getDurationMs(),
                        c.getErrorMessage(),
                        c.getStackTrace()
                ));
            }
        }

        return ingestBatch(organizationId, runId, items);
    }
}
