package com.forgeai.identity.application.query;

import java.util.Objects;
import java.util.UUID;

/**
 * Query to retrieve the currently authenticated user's profile.
 *
 * @param userId the authenticated user's ID (extracted from JWT)
 */
public record GetCurrentUserQuery(UUID userId) {

    public GetCurrentUserQuery {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
