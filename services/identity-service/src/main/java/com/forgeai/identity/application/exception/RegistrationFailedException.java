package com.forgeai.identity.application.exception;

/**
 * Thrown when user registration fails due to duplicate email
 * or other application-level precondition violations.
 */
public final class RegistrationFailedException extends ApplicationException {

    public RegistrationFailedException(String message) {
        super("REGISTRATION_FAILED", message);
    }

    public RegistrationFailedException(String message, Throwable cause) {
        super("REGISTRATION_FAILED", message, cause);
    }
}
