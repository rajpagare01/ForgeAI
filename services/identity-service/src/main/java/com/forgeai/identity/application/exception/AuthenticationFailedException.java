package com.forgeai.identity.application.exception;

/**
 * Thrown when authentication fails due to invalid credentials,
 * unverified email, or locked/suspended accounts.
 */
public final class AuthenticationFailedException extends ApplicationException {

    public AuthenticationFailedException(String message) {
        super("AUTH_FAILED", message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super("AUTH_FAILED", message, cause);
    }
}
