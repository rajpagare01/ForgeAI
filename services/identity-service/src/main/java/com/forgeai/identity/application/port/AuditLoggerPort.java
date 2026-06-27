package com.forgeai.identity.application.port;

import java.util.UUID;

/**
 * Output port for recording security-critical audit entries.
 * Implemented in the infrastructure layer to write to WORM-compliant storage.
 */
public interface AuditLoggerPort {

    /**
     * Logs an auditable action.
     *
     * @param userId the user who performed the action
     * @param action a machine-readable action identifier (e.g. "USER_LOGIN")
     * @param detail a human-readable detail string
     */
    void log(UUID userId, String action, String detail);
}
