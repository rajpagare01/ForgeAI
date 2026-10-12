package com.forgeai.testing.domain.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestRunCompletedEvent implements DomainEvent {
    private UUID testRunId;
    private UUID organizationId;
    private UUID repositoryId;
    private String commitSha;
    private Integer totalTests;
    private Integer passedTests;
    private Integer failedTests;
    private Integer skippedTests;
    private Long durationMs;

    @Builder.Default
    private Instant timestamp = Instant.now();

    @Override
    public String getEventType() {
        return "TEST_RUN_COMPLETED";
    }

    @Override
    public String getAggregateType() {
        return "TEST_RUN";
    }

    @Override
    public String getAggregateId() {
        return testRunId != null ? testRunId.toString() : "";
    }
}
