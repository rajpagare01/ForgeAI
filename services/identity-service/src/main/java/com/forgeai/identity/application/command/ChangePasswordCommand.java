package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to change the password for an authenticated user.
 *
 * @param userId      the user requesting the change
 * @param oldPassword the current password for verification
 * @param newPassword the desired new password
 */
public record ChangePasswordCommand(UUID userId, String oldPassword, String newPassword) {

    public ChangePasswordCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(oldPassword, "oldPassword must not be null");
        Objects.requireNonNull(newPassword, "newPassword must not be null");
    }
}
