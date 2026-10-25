package com.forgeai.sourcecontrol.domain.model;

/**
 * Explicit lifecycle states for a GitHub App installation.
 */
public enum InstallationStatus {
    DISCOVERED,
    VALIDATING,
    CLAIMABLE,
    CLAIMED,
    ACTIVE,
    QUARANTINED,
    SUSPENDED,
    REMOVED,
    DELETED,
    DISCONNECTED
}
