package com.forgeai.identity.application.command;

import java.util.Objects;
import java.util.UUID;

/**
 * Administrative command to suspend a user account.
 *
 * @param userId the account to suspend
 * @param reason the human-readable reason for suspension
 */
public record SuspendAccountCommand(UUID userId, String reason) {

    public SuspendAccountCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
    }
}
