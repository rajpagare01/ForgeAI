package com.forgeai.sourcecontrol.domain.model;

public enum RepositorySyncStatus {
    SYNCED,
    SYNCING,
    STALE,
    FAILED,
    INACCESSIBLE,
    DISCONNECTED
}
