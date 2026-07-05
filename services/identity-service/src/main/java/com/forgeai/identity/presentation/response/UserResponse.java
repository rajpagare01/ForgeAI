package com.forgeai.identity.presentation.response;

import java.util.UUID;
import java.time.Instant;

public record UserResponse(
    UUID id,
    String email,
    String status,
    boolean mfaEnabled,
    Instant createdAt,
    Instant updatedAt
) {}
