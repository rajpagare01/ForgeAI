package com.forgeai.identity.application.exception;

/**
 * Thrown when a session-related operation fails
 * (e.g., revoking an already-revoked session, refreshing an expired token).
 */
public final class SessionOperationException extends ApplicationException {

    public SessionOperationException(String message) {
        super("SESSION_OPERATION_FAILED", message);
    }

    public SessionOperationException(String message, Throwable cause) {
        super("SESSION_OPERATION_FAILED", message, cause);
    }
}
