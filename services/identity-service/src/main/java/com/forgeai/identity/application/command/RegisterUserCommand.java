package com.forgeai.identity.application.command;

import java.util.Objects;

/**
 * Immutable command carrying the validated input data required
 * to register a new user in the ForgeAI platform.
 *
 * @param email    the user's email address (will be normalized by the domain)
 * @param password the raw password (will be hashed before persistence)
 */
public record RegisterUserCommand(String email, String password) {

    public RegisterUserCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(password, "password must not be null");
    }
}
