package com.forgeai.identity.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only projection of a User aggregate for API consumers.
 * Domain objects never leave the application boundary; this DTO does.
 *
 * @param userId    the user's unique identifier
 * @param email     the user's normalized email address
 * @param status    the account status (PENDING_VERIFICATION, ACTIVE, SUSPENDED, LOCKED)
 * @param mfaEnabled whether multi-factor authentication is enabled
 * @param createdAt  account creation timestamp
 * @param updatedAt  last modification timestamp
 */
public record UserDto(
        UUID userId,
        String email,
        String status,
        boolean mfaEnabled,
        Instant createdAt,
        Instant updatedAt) {
}
