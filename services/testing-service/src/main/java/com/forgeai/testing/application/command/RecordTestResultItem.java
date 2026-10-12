package com.forgeai.testing.application.command;

import com.forgeai.testing.domain.model.TestResultStatus;

public record RecordTestResultItem(
        String suiteName,
        String testName,
        String classname,
        String requirementKey,
        TestResultStatus status,
        Long durationMs,
        String errorMessage,
        String stackTrace
) {}
