package com.forgeai.identity.application.validator;

import com.forgeai.identity.application.command.RegisterUserCommand;
import com.forgeai.identity.application.exception.RegistrationFailedException;

/**
 * Application-level validator for registration commands.
 * Validates request completeness only; domain rules are enforced by aggregates.
 */
public final class RegisterUserValidator {

    private RegisterUserValidator() {
    }

    /**
     * Validates that the registration command contains well-formed input.
     *
     * @param command the registration command
     * @throws RegistrationFailedException if input is incomplete
     */
    public static void validate(RegisterUserCommand command) {
        if (command.email().isBlank()) {
            throw new RegistrationFailedException("Email must not be blank.");
        }
        if (command.password().isBlank()) {
            throw new RegistrationFailedException("Password must not be blank.");
        }
    }
}
