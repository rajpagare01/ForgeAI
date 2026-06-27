package com.forgeai.identity.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve a user by their unique identifier.
 *
 * @param userId the user's unique identifier
 */
public record GetUserByIdQuery(UUID userId) {

    public GetUserByIdQuery {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
