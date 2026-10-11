package com.forgeai.identity.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
public class RefreshToken {
    private UUID id;
    private UUID sessionId;
    private UUID familyId;
    private String tokenHash;
    private Instant issuedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant gracePeriodUntil;
    private UUID replacedBy;
    private Instant createdAt = Instant.now();

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isInGracePeriod() {
        return isRevoked() && gracePeriodUntil != null && Instant.now().isBefore(gracePeriodUntil);
    }
}
