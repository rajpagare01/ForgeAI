package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative command to lock a user account, preventing login.
 *
 * @param userId the account to lock
 */
public record LockAccountCommand(UUID userId) {

    public LockAccountCommand {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
