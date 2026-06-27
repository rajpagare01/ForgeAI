package com.forgeai.identity.application.command;

import java.util.Objects;

/**
 * Command to initiate a password reset flow by sending a reset link
 * to the user's registered email address.
 *
 * @param email the user's email address
 */
public record ForgotPasswordCommand(String email) {

    public ForgotPasswordCommand {
        Objects.requireNonNull(email, "email must not be null");
    }
}
