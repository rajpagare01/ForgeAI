package com.forgeai.testing.application.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record RecordTestResultsBatchCommand(
        @NotEmpty(message = "Results list must not be empty")
        List<@Valid RecordTestResultItem> results
) {}
