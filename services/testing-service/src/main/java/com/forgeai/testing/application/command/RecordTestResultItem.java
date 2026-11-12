package com.forgeai.testing.application.command;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.forgeai.testing.domain.model.TestResultStatus;
import jakarta.validation.constraints.NotNull;

public record RecordTestResultItem(
        @JsonAlias({"suite_name"})
        String suiteName,

        @JsonAlias({"testCaseName", "name", "test_name"})
        String testName,

        @JsonAlias({"className", "class_name"})
        String classname,

        @JsonAlias({"requirement_key"})
        String requirementKey,

        @NotNull(message = "Test result status is required")
        TestResultStatus status,

        @JsonAlias({"duration_ms"})
        Long durationMs,

        @JsonAlias({"error_message", "failureMessage"})
        String errorMessage,

        @JsonAlias({"stack_trace"})
        String stackTrace
) {}
