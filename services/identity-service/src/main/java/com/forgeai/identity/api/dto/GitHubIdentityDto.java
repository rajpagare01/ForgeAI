package com.forgeai.identity.api.dto;

import java.time.Instant;

public record GitHubIdentityDto(
    Long githubUserId,
    String githubLogin,
    String name,
    String email,
    String avatarUrl,
    String profileUrl,
    Instant linkedAt,
    Instant lastSyncedAt
) {}
