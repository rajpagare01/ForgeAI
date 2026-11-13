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

    private static final int MAX_MAP_ENTRIES = 10_000;

    private final TestRunRepository testRunRepository;
    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final TestResultRepository testResultRepository;
    private final JunitXmlParser junitXmlParser;
    private final java.util.concurrent.ConcurrentHashMap<String, Object> upsertLocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Transactional
    public int ingestBatch(UUID organizationId, UUID runId, List<RecordTestResultItem> items) {
        return ingestBatch(organizationId, organizationId, "ORGANIZATION", runId, items);
    }

    @Transactional
    public int ingestBatch(UUID workspaceId, UUID organizationId, String scope, UUID runId, List<RecordTestResultItem> items) {
        UUID effectiveWsId = workspaceId != null ? workspaceId : organizationId;
        if (effectiveWsId == null) {
            throw new IllegalArgumentException("Workspace ID or Organization ID must be provided via tenant context");
        }
        String effectiveScope = scope != null ? scope : (organizationId != null ? "ORGANIZATION" : "PERSONAL");

        if (items == null || items.isEmpty()) {
            return 0;
        }

        TestRun run = testRunRepository.findByIdAndWorkspaceId(runId, effectiveWsId)
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
            TestSuite suite = suiteCache.computeIfAbsent(suiteName, name -> {
                TestSuite resolved = resolveOrCreateSuite(effectiveWsId, organizationId, effectiveScope, run.getRepositoryId(), name, now);
                if (resolved != null && resolved.getId() != null) {
                    preloadSuiteTestCases(resolved.getId(), caseCache);
                }
                return resolved;
            });

            // 2. Get or create TestCase
            String effectiveTestName = (item.testName() != null && !item.testName().isBlank())
                    ? item.testName()
                    : "VerificationTestCase";
            String caseKey = suite.getId() + "#" + effectiveTestName + "#" + (item.classname() != null ? item.classname() : "");
            TestCase testCase = caseCache.computeIfAbsent(caseKey, k ->
                    resolveOrCreateTestCase(effectiveWsId, organizationId, effectiveScope, suite.getId(),
                            effectiveTestName, item.classname(), item.requirementKey(), now));
            if (item.requirementKey() != null && !item.requirementKey().equals(testCase.getRequirementKey())) {
                testCase.setRequirementKey(item.requirementKey());
                TestCase updated = testCaseRepository.save(testCase);
                if (updated != null) {
                    testCase = updated;
                    caseCache.put(caseKey, updated);
                }
            }

            // 3. Assemble TestResult
            TestResult result = TestResult.builder()
                    .organizationId(organizationId)
                    .workspaceId(effectiveWsId)
                    .scope(effectiveScope)
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
        log.info("Ingested {} test results for runId={}, wsId={}, orgId={}", resultsToSave.size(), runId, effectiveWsId, organizationId);
        return resultsToSave.size();
    }

    @Transactional
    public int ingestJunitXml(UUID organizationId, UUID runId, byte[] xmlBytes) {
        return ingestJunitXml(organizationId, organizationId, "ORGANIZATION", runId, xmlBytes);
    }

    @Transactional
    public int ingestJunitXml(UUID workspaceId, UUID organizationId, String scope, UUID runId, byte[] xmlBytes) {
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

        return ingestBatch(workspaceId, organizationId, scope, runId, items);
    }

    private TestSuite resolveOrCreateSuite(UUID effectiveWsId, UUID organizationId, String effectiveScope,
                                           UUID repositoryId, String name, Instant now) {
        String lockKey = "suite:" + effectiveWsId + ":" + repositoryId + ":" + name;
        if (upsertLocks.size() > MAX_MAP_ENTRIES) {
            upsertLocks.clear();
        }
        synchronized (upsertLocks.computeIfAbsent(lockKey, k -> new Object())) {
            return testSuiteRepository.findByWorkspaceIdAndRepositoryIdAndName(effectiveWsId, repositoryId, name)
                    .orElseGet(() -> {
                        try {
                            TestSuite toSave = TestSuite.builder()
                                    .organizationId(organizationId)
                                    .workspaceId(effectiveWsId)
                                    .scope(effectiveScope)
                                    .repositoryId(repositoryId)
                                    .name(name)
                                    .framework("JUNIT")
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build();
                            return saveInNewTxIfAvailable(() -> testSuiteRepository.save(toSave));
                        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                            return testSuiteRepository.findByWorkspaceIdAndRepositoryIdAndName(effectiveWsId, repositoryId, name)
                                    .orElseThrow(() -> ex);
                        }
                    });
        }
    }

    private void preloadSuiteTestCases(UUID suiteId, Map<String, TestCase> caseCache) {
        List<TestCase> existingCases = testCaseRepository.findAllBySuiteId(suiteId);
        if (existingCases == null) {
            return;
        }
        for (TestCase tc : existingCases) {
            String key = suiteId + "#" + tc.getName() + "#" + (tc.getClassname() != null ? tc.getClassname() : "");
            caseCache.putIfAbsent(key, tc);
        }
    }

    private TestCase resolveOrCreateTestCase(UUID effectiveWsId, UUID organizationId, String effectiveScope,
                                             UUID suiteId, String effectiveTestName, String classname,
                                             String requirementKey, Instant now) {
        String lockKey = "case:" + suiteId + ":" + effectiveTestName + ":" + (classname != null ? classname : "");
        if (upsertLocks.size() > MAX_MAP_ENTRIES) {
            upsertLocks.clear();
        }
        synchronized (upsertLocks.computeIfAbsent(lockKey, k -> new Object())) {
            Optional<TestCase> existingOpt = testCaseRepository.findBySuiteIdAndNameAndClassname(suiteId, effectiveTestName, classname);
            if (existingOpt.isPresent()) {
                TestCase existing = existingOpt.get();
                if (requirementKey != null && !requirementKey.equals(existing.getRequirementKey())) {
                    existing.setRequirementKey(requirementKey);
                    return testCaseRepository.save(existing);
                }
                return existing;
            }
            try {
                TestCase toSave = TestCase.builder()
                        .organizationId(organizationId)
                        .workspaceId(effectiveWsId)
                        .scope(effectiveScope)
                        .suiteId(suiteId)
                        .name(effectiveTestName)
                        .classname(classname)
                        .requirementKey(requirementKey)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                return saveInNewTxIfAvailable(() -> testCaseRepository.save(toSave));
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                return testCaseRepository.findBySuiteIdAndNameAndClassname(suiteId, effectiveTestName, classname)
                        .orElseThrow(() -> ex);
            }
        }
    }

    private <T> T saveInNewTxIfAvailable(java.util.function.Supplier<T> action) {
        if (transactionManager != null) {
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> action.get());
        }
        return action.get();
    }
}
