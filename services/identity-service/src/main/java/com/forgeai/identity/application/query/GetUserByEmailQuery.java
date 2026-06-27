package com.forgeai.identity.application.query;

import java.util.Objects;

/**
 * Query to retrieve a user by their email address.
 *
 * @param email the user's email address
 */
public record GetUserByEmailQuery(String email) {

    public GetUserByEmailQuery {
        Objects.requireNonNull(email, "email must not be null");
    }
}
