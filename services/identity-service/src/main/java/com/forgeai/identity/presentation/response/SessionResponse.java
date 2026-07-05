package com.forgeai.identity.presentation.response;

import java.util.UUID;
import java.time.Instant;

public record SessionResponse(
    UUID id,
    String deviceInfo,
    String ipAddress,
    String userAgent,
    Instant createdAt,
    Instant lastAccessed
) {}
