package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Command to terminate a user session by revoking it.
 *
 * @param sessionId the session to revoke
 * @param userId    the user who owns the session (for authorization)
 */
public record LogoutCommand(UUID sessionId, UUID userId) {

    public LogoutCommand {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
