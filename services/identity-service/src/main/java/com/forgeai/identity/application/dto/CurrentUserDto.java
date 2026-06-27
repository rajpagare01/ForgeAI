package com.forgeai.identity.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Extended user profile returned for the /users/me endpoint.
 *
 * @param userId     the user's unique identifier
 * @param email      the user's email address
 * @param status     the account status
 * @param mfaEnabled whether MFA is enabled
 * @param createdAt  account creation timestamp
 */
public record CurrentUserDto(
        UUID userId,
        String email,
        String status,
        boolean mfaEnabled,
        Instant createdAt) {
}
