package com.forgeai.identity.application.validator;

import com.forgeai.identity.application.command.VerifyEmailCommand;
import com.forgeai.identity.application.exception.ApplicationException;

/**
 * Validates completeness of an email verification command.
 */
public final class EmailVerificationValidator {

    private EmailVerificationValidator() {
    }

    /**
     * Validates that the verification token is non-blank.
     *
     * @param command the verification command
     */
    public static void validate(VerifyEmailCommand command) {
        if (command.token().isBlank()) {
            throw new EmailVerificationValidationException("Verification token must not be blank.");
        }
    }

    private static final class EmailVerificationValidationException extends ApplicationException {
        EmailVerificationValidationException(String message) {
            super("EMAIL_VERIFICATION_INVALID", message);
        }
    }
}
