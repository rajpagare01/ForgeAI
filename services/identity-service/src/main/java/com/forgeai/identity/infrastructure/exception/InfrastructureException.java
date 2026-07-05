package com.forgeai.identity.infrastructure.exception;

import com.forgeai.identity.application.exception.ApplicationException;

/**
 * Base exception for all infrastructure-related errors.
 * Extends ApplicationException to bridge between infrastructure and application layers.
 */
public class InfrastructureException extends ApplicationException {

    public InfrastructureException(String message) {
        super(message);
    }

    public InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
