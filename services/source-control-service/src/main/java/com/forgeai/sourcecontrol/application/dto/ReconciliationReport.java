package com.forgeai.sourcecontrol.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationReport(
        Long installationId,
        UUID workspaceId,
        String accountLogin,
        int totalAccessible,
        int newlyAdded,
        int removedOrInaccessible,
        Instant timestamp
) {}
