package com.forgeai.identity.application.exception;

/**
 * Base exception for all application-layer failures.
 * Wraps domain exceptions or signals application-specific errors
 * without coupling to any framework.
 */
public abstract class ApplicationException extends RuntimeException {

    private final String errorCode;

    protected ApplicationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected ApplicationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** Machine-readable error code for API consumers (e.g. "AUTH_FAILED"). */
    public String getErrorCode() {
        return errorCode;
    }
}
