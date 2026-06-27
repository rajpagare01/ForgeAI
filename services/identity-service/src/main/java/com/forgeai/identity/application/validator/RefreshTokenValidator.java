package com.forgeai.identity.application.validator;

import com.forgeai.identity.application.command.RefreshTokenCommand;
import com.forgeai.identity.application.exception.SessionOperationException;

/**
 * Validates completeness of a refresh token command.
 */
public final class RefreshTokenValidator {

    private RefreshTokenValidator() {
    }

    /**
     * Validates that the refresh token command contains well-formed input.
     *
     * @param command the refresh token command
     */
    public static void validate(RefreshTokenCommand command) {
        if (command.currentTokenHash().isBlank()) {
            throw new SessionOperationException("Refresh token must not be blank.");
        }
    }
}
