package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative command to re-activate a suspended or locked account.
 *
 * @param userId the account to activate
 */
public record ActivateAccountCommand(UUID userId) {

    public ActivateAccountCommand {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
