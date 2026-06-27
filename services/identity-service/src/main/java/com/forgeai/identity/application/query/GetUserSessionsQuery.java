package com.forgeai.identity.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve all active sessions for a specific user.
 *
 * @param userId the user whose sessions to retrieve
 */
public record GetUserSessionsQuery(UUID userId) {

    public GetUserSessionsQuery {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
