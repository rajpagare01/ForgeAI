package com.forgeai.identity.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve all non-revoked, non-expired sessions for a user.
 *
 * @param userId the user whose active sessions to retrieve
 */
public record GetActiveSessionsQuery(UUID userId) {

    public GetActiveSessionsQuery {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
