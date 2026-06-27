package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative command to unlock a previously locked account.
 *
 * @param userId the account to unlock
 */
public record UnlockAccountCommand(UUID userId) {

    public UnlockAccountCommand {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
