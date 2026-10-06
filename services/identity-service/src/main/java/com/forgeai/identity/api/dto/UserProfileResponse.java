package com.forgeai.identity.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe public representation of the current user's profile.
 * Never includes: passwordHash, refresh tokens, session internals.
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String username,
        String firstName,
        String lastName,
        String status,
        boolean emailVerified,
        String pendingEmail,
        Instant createdAt,
        Instant updatedAt
) {}
