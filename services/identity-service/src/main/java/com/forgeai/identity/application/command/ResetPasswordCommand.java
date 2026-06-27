package com.forgeai.identity.application.command;

import java.util.Objects;

/**
 * Command to set a new password using a valid password-reset token.
 *
 * @param token       the password-reset token
 * @param newPassword the desired new password (raw, unhashed)
 */
public record ResetPasswordCommand(String token, String newPassword) {

    public ResetPasswordCommand {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(newPassword, "newPassword must not be null");
    }
}
