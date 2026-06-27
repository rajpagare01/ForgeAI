package com.forgeai.identity.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve a single session by its identifier.
 *
 * @param sessionId the session's unique identifier
 */
public record GetSessionByIdQuery(UUID sessionId) {

    public GetSessionByIdQuery {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
    }
}
