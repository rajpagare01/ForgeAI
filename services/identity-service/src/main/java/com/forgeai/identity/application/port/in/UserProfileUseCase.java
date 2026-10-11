package com.forgeai.identity.application.port.in;

import com.forgeai.identity.api.dto.UserProfileResponse;

import java.util.UUID;

/**
 * Use-case interface for authenticated user profile and account management.
 * All methods receive a {@code userId} derived from the authenticated security
 * context — never from a client-supplied request body.
 */
public interface UserProfileUseCase {

    /** Retrieve the current user's public profile. */
    UserProfileResponse getProfile(UUID userId);

    /** Update mutable profile fields (firstName, lastName). */
    UserProfileResponse updateProfile(UUID userId, String firstName, String lastName);

    /** Change the user's username with case-insensitive uniqueness enforcement. */
    UserProfileResponse changeUsername(UUID userId, String newUsername);

    /**
     * Stage an email change by storing {@code newEmail} as {@code pendingEmail},
     * generating a 64-character verification token and emitting an outbox event.
     */
    UserProfileResponse requestEmailChange(UUID userId, String newEmail);

    /**
     * Verify email change token, update active email, mark email_verified = true,
     * and emit UserUpdatedEvent to outbox.
     */
    UserProfileResponse verifyEmailChange(UUID userId, String token);

    /**
     * Change password after verifying the current password.
     * Revokes all existing sessions and forces re-authentication.
     */
    void changePassword(UUID userId, String currentPassword, String newPassword);

    /**
     * Self-initiated account deactivation.
     * Revokes all sessions and refresh tokens; the user can no longer authenticate.
     */
    void deactivateAccount(UUID userId);
}
