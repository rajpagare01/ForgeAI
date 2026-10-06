package com.forgeai.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
        UUID id,
        String name,
        String slug,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
