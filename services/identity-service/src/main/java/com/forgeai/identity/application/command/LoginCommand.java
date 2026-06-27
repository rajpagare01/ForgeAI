package com.forgeai.identity.application.command;

import java.util.Objects;

/**
 * Immutable command carrying credentials and device metadata
 * required to authenticate a user and create a new session.
 *
 * @param email     the user's email address
 * @param password  the raw password to verify
 * @param ipAddress the IP address of the requesting client
 * @param userAgent the User-Agent header from the requesting client
 * @param deviceInfo a human-readable device description
 */
public record LoginCommand(
        String email,
        String password,
        String ipAddress,
        String userAgent,
        String deviceInfo) {

    public LoginCommand {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(password, "password must not be null");
    }
}
