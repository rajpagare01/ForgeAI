package com.forgeai.identity.application.command;

import java.util.Objects;

/**
 * Command to verify a user's email address using a one-time token.
 *
 * @param token the verification token sent to the user's email
 */
public record VerifyEmailCommand(String token) {

    public VerifyEmailCommand {
        Objects.requireNonNull(token, "token must not be null");
    }
}
