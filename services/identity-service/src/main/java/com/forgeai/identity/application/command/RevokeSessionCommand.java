package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to forcibly revoke a specific user session (e.g., from the
 * "active sessions" management panel).
 *
 * @param sessionId the session to revoke
 * @param userId    the user who owns the session
 */
public record RevokeSessionCommand(UUID sessionId, UUID userId) {

    public RevokeSessionCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
