package com.forgeai.testing.infrastructure.persistence.batch;

import com.forgeai.testing.domain.model.TestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcBatchTestResultRepository {

    private static final String SQL_INSERT = """
        INSERT INTO test_results (
            id, organization_id, test_run_id, test_case_id, status, duration_ms, error_message, stack_trace, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public List<TestResult> batchInsert(List<TestResult> results, int batchSize) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        for (TestResult r : results) {
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            if (r.getCreatedAt() == null) {
                r.setCreatedAt(now);
            }
        }

        jdbcTemplate.batchUpdate(
                SQL_INSERT,
                results,
                batchSize > 0 ? batchSize : 500,
                (ps, r) -> {
                    ps.setObject(1, r.getId());
                    ps.setObject(2, r.getOrganizationId());
                    ps.setObject(3, r.getTestRunId());
                    ps.setObject(4, r.getTestCaseId());
                    ps.setString(5, r.getStatus().name());
                    ps.setLong(6, r.getDurationMs() != null ? r.getDurationMs() : 0L);
                    ps.setString(7, r.getErrorMessage());
                    ps.setString(8, r.getStackTrace());
                    ps.setTimestamp(9, Timestamp.from(r.getCreatedAt()));
                }
        );

        log.debug("Batch inserted {} test results using batchSize={}", results.size(), batchSize);
        return results;
    }
}
