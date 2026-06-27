package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to rotate a refresh token and issue a new token pair.
 *
 * @param sessionId        the session that owns the refresh token
 * @param currentTokenHash the hashed value of the current refresh token
 */
public record RefreshTokenCommand(UUID sessionId, String currentTokenHash) {

    public RefreshTokenCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(currentTokenHash, "currentTokenHash must not be null");
    }
}
