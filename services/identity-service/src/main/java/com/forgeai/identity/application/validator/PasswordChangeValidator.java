package com.forgeai.identity.application.validator;

import com.forgeai.identity.application.command.ChangePasswordCommand;
import com.forgeai.identity.application.exception.ApplicationException;

/**
 * Validates completeness of a password change command.
 */
public final class PasswordChangeValidator {

    private PasswordChangeValidator() {
    }

    /**
     * Validates that old and new passwords differ and are non-blank.
     *
     * @param command the password change command
     */
    public static void validate(ChangePasswordCommand command) {
        if (command.oldPassword().isBlank() || command.newPassword().isBlank()) {
            throw new PasswordChangeValidationException("Passwords must not be blank.");
        }
        if (command.oldPassword().equals(command.newPassword())) {
            throw new PasswordChangeValidationException("New password must differ from old password.");
        }
    }

    private static final class PasswordChangeValidationException extends ApplicationException {
        PasswordChangeValidationException(String message) {
            super("PASSWORD_CHANGE_INVALID", message);
        }
    }
}
